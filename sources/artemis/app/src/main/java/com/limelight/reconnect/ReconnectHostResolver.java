package com.limelight.reconnect;

import android.content.Context;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Resolves a saved PC after a network change without trusting a replacement PC or certificate. */
public class ReconnectHostResolver {
    private static final long ATTEMPT_TIMEOUT_MS = 12000;
    protected final Context context;
    protected final String pcUuid;
    protected final X509Certificate originalCert;
    private final String uniqueId;
    private final ComputerDetails.AddressTuple fallback;
    private final int fallbackHttpsPort;
    private final Set<NvHTTP> activeRequests = new java.util.HashSet<>();
    private volatile boolean cancelled;
    private volatile ExecutorService probes;

    public static final class Result {
        public final ComputerDetails.AddressTuple address;
        public final int httpsPort;
        public final boolean supportsFastMicHandoff;

        public Result(ComputerDetails.AddressTuple address, int httpsPort) {
            this(address, httpsPort, false);
        }

        public Result(ComputerDetails.AddressTuple address, int httpsPort, boolean supportsFastMicHandoff) {
            this.address = new ComputerDetails.AddressTuple(address.address, address.port);
            this.httpsPort = httpsPort;
            this.supportsFastMicHandoff = supportsFastMicHandoff;
        }
    }

    public static final class TerminalException extends IOException {
        public final int errorCode;
        public TerminalException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    public ReconnectHostResolver(Context context, String pcUuid, X509Certificate originalCert,
                                 String uniqueId, ComputerDetails.AddressTuple fallback,
                                 int fallbackHttpsPort) {
        this.context = context.getApplicationContext();
        this.pcUuid = pcUuid;
        this.originalCert = originalCert;
        this.uniqueId = uniqueId;
        this.fallback = fallback;
        this.fallbackHttpsPort = fallbackHttpsPort;
    }

    /** Call on a worker thread. A resolver is one attempt and must not be reused after cancellation. */
    public Result resolve() throws IOException, InterruptedException {
        checkCancelled();
        if (pcUuid == null || pcUuid.isEmpty() || originalCert == null) {
            throw new TerminalException(401, "Original PC identity and certificate required");
        }
        List<ComputerDetails.AddressTuple> candidates = loadCandidates();
        checkCancelled();
        if (candidates.isEmpty()) throw new IOException("No saved PC addresses");
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(5, candidates.size()), task -> {
            Thread thread = new Thread(task, "ReconnectHostProbe");
            thread.setDaemon(true);
            return thread;
        });
        probes = executor;
        CompletionService<ProbeResult> completed = new ExecutorCompletionService<>(executor);
        List<Future<ProbeResult>> futures = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ATTEMPT_TIMEOUT_MS);
        TerminalException identityFailure = null;
        try {
            checkCancelled();
            for (ComputerDetails.AddressTuple candidate : candidates) {
                checkCancelled();
                futures.add(completed.submit(() -> {
                    try { return new ProbeResult(probe(candidate), null); }
                    catch (TerminalException failure) { return new ProbeResult(null, failure); }
                    catch (IOException | XmlPullParserException failure) { return new ProbeResult(null, null); }
                }));
            }
            for (int remaining = futures.size(); remaining > 0; remaining--) {
                checkCancelled();
                long wait = deadline - System.nanoTime();
                if (wait <= 0) break;
                Future<ProbeResult> future = completed.poll(Math.min(wait, TimeUnit.MILLISECONDS.toNanos(250)),
                        TimeUnit.NANOSECONDS);
                if (future == null) { remaining++; continue; }
                try {
                    ProbeResult result = future.get();
                    checkCancelled();
                    if (result.success != null) return result.success;
                    if (result.identityFailure != null) identityFailure = result.identityFailure;
                } catch (java.util.concurrent.ExecutionException failure) {
                    // A malformed/unreachable candidate must not prevent another valid saved address.
                }
            }
            if (identityFailure != null) throw identityFailure;
            throw new IOException("Saved PC is not currently reachable");
        } finally {
            for (Future<?> future : futures) future.cancel(true);
            cancelRequests();
            executor.shutdownNow();
            probes = null;
        }
    }

    /** Stable local/manual/WAN/IPv6 precedence for submission; probes complete independently. */
    protected List<ComputerDetails.AddressTuple> loadCandidates() {
        ComputerDatabaseManager database = new ComputerDatabaseManager(context);
        try {
            ComputerDetails saved = database.getComputerByUUID(pcUuid);
            return candidateAddresses(saved, fallback);
        } finally {
            database.close();
        }
    }

    static List<ComputerDetails.AddressTuple> candidateAddresses(ComputerDetails saved,
                                                                ComputerDetails.AddressTuple fallback) {
        LinkedHashSet<ComputerDetails.AddressTuple> addresses = new LinkedHashSet<>();
        if (saved != null) {
            addAddress(addresses, saved.localAddress);
            addAddress(addresses, saved.manualAddress);
            addAddress(addresses, saved.remoteAddress);
            addAddress(addresses, saved.ipv6Address);
        }
        addAddress(addresses, fallback);
        return new ArrayList<>(addresses);
    }

    private static void addAddress(Set<ComputerDetails.AddressTuple> addresses,
                                   ComputerDetails.AddressTuple address) {
        if (address != null) addresses.add(new ComputerDetails.AddressTuple(address.address, address.port));
    }

    protected NvHTTP createHttp(ComputerDetails.AddressTuple address) throws IOException {
        int httpsPort = fallback != null && address.equals(fallback) ? fallbackHttpsPort : 0;
        return new NvHTTP(address, httpsPort, uniqueId, originalCert, PlatformBinding.getCryptoProvider(context));
    }

    protected Result probe(ComputerDetails.AddressTuple address) throws IOException, XmlPullParserException,
            InterruptedException {
        checkCancelled();
        NvHTTP http = createHttp(address);
        synchronized (activeRequests) {
            checkCancelled();
            activeRequests.add(http);
        }
        try {
            String serverInfo = http.getServerInfoStrict();
            checkCancelled();
            ComputerDetails details = http.getComputerDetails(serverInfo);
            if (details.uuid == null || !pcUuid.equalsIgnoreCase(details.uuid)) {
                throw new TerminalException(401, "Saved address belongs to a different PC");
            }
            if (details.pairState != PairingManager.PairState.PAIRED) {
                throw new TerminalException(401, "PC pairing must be renewed");
            }
            // This optional capability is trusted only after the same pinned HTTPS response
            // has proved the original PC identity and the client's pairing state.
            return new Result(address, details.httpsPort, supportsFastMicHandoff(serverInfo));
        } catch (HostHttpResponseException failure) {
            if (failure.getErrorCode() == 401 || failure.getErrorCode() == 403) {
                throw new TerminalException(failure.getErrorCode(), "PC authentication rejected");
            }
            throw failure;
        } finally {
            synchronized (activeRequests) { activeRequests.remove(http); }
            http.cancelPendingRequests();
        }
    }

    /** Unknown, duplicated or malformed capability declarations retain the conservative wait. */
    static boolean supportsFastMicHandoff(String serverInfo) {
        if (serverInfo == null) return false;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(serverInfo));
            boolean rootSeen = false, rootClosed = false, supported = false;
            int declarations = 0;
            StringBuilder value = null;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                 event = parser.nextToken()) {
                if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF) return false;
                if (event == XmlPullParser.START_TAG) {
                    String namespace = parser.getNamespace();
                    boolean noNamespace = namespace == null || namespace.isEmpty();
                    if (parser.getDepth() == 1) {
                        if (rootSeen || !"root".equals(parser.getName()) || !noNamespace) return false;
                        rootSeen = true;
                    }
                    if (value != null) return false;
                    if ("ApolloMicResumeHandoff".equals(parser.getName())) {
                        if (++declarations != 1 || parser.getDepth() != 2 || !noNamespace) return false;
                        value = new StringBuilder();
                    }
                } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                    if (value != null) value.append(parser.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    if ("ApolloMicResumeHandoff".equals(parser.getName())) {
                        if (value == null) return false;
                        supported = "1".equals(value.toString().trim());
                        value = null;
                    }
                    if (parser.getDepth() == 1 && "root".equals(parser.getName())) rootClosed = true;
                }
            }
            return rootSeen && rootClosed && declarations == 1 && supported;
        } catch (IOException | XmlPullParserException | RuntimeException malformed) {
            return false;
        }
    }

    public void cancel() {
        cancelled = true;
        cancelRequests();
        ExecutorService executor = probes;
        if (executor != null) executor.shutdownNow();
    }

    private void cancelRequests() {
        synchronized (activeRequests) {
            for (NvHTTP request : activeRequests) request.cancelPendingRequests();
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException("Reconnect cancelled");
    }

    private static final class ProbeResult {
        final Result success;
        final TerminalException identityFailure;
        ProbeResult(Result success, TerminalException identityFailure) {
            this.success = success;
            this.identityFailure = identityFailure;
        }
    }
}
