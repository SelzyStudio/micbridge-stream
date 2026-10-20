package com.limelight.reconnect;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ReconnectHostResolverTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final X509Certificate pin = mock(X509Certificate.class);
    private static ComputerDetails.AddressTuple address(String host) {
        return new ComputerDetails.AddressTuple(host, 47989);
    }

    private NvHTTP response(String uuid, PairingManager.PairState pairing) throws Exception {
        NvHTTP http = mock(NvHTTP.class);
        when(http.getServerInfoStrict()).thenReturn("authenticated response");
        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.pairState = pairing;
        details.httpsPort = 47984;
        when(http.getComputerDetails("authenticated response")).thenReturn(details);
        return http;
    }

    @Test public void candidateListHasStableOrderDeduplicatesAndCopiesAddresses() {
        ComputerDetails saved = new ComputerDetails();
        saved.localAddress = address("local"); saved.manualAddress = address("manual");
        saved.remoteAddress = address("wan"); saved.ipv6Address = address("ipv6");
        List<ComputerDetails.AddressTuple> candidates = ReconnectHostResolver.candidateAddresses(saved, address("manual"));
        assertEquals(Arrays.asList(address("local"), address("manual"), address("wan"), address("ipv6")), candidates);
        saved.localAddress.address = "changed";
        assertEquals("local", candidates.get(0).address);
        assertEquals(Arrays.asList(address("fallback")), ReconnectHostResolver.candidateAddresses(null, address("fallback")));
    }

    @Test public void deadLocalAndWrongPcDoNotPreventAuthenticatedWanResult() throws Exception {
        NvHTTP wrong = response("different-pc", PairingManager.PairState.PAIRED);
        NvHTTP valid = response("ORIGINAL-PC", PairingManager.PairState.PAIRED);
        ReconnectHostResolver resolver = new ReconnectHostResolver(context, "original-pc", pin, "client", address("wan"), 47984) {
            @Override protected List<ComputerDetails.AddressTuple> loadCandidates() {
                return Arrays.asList(address("local"), address("wrong"), address("wan"));
            }
            @Override protected NvHTTP createHttp(ComputerDetails.AddressTuple address) throws IOException {
                if (address.address.equals("local")) throw new IOException("No route");
                return address.address.equals("wrong") ? wrong : valid;
            }
        };
        ReconnectHostResolver.Result result = resolver.resolve();
        assertEquals(address("wan"), result.address);
        assertEquals(47984, result.httpsPort);
        assertFalse(result.supportsFastMicHandoff);
        verify(valid).getServerInfoStrict();
        verify(valid, never()).getServerInfo(anyBoolean());
        verify(valid).cancelPendingRequests();
    }

    @Test public void fastMicCapabilityIsTakenFromTheVerifiedPairedResponseOnly() throws Exception {
        String xml = "<root status_code=\"200\"><ApolloMicResumeHandoff>1</ApolloMicResumeHandoff></root>";
        NvHTTP capable = mock(NvHTTP.class);
        when(capable.getServerInfoStrict()).thenReturn(xml);
        ComputerDetails original = new ComputerDetails();
        original.uuid = "original-pc";
        original.pairState = PairingManager.PairState.PAIRED;
        original.httpsPort = 47984;
        when(capable.getComputerDetails(xml)).thenReturn(original);
        ReconnectHostResolver.Result target = twoRoutes(capable, capable).resolve();
        assertTrue(target.supportsFastMicHandoff);
        verify(capable, never()).getServerInfo(anyBoolean());

        original.uuid = "different-pc";
        try {
            twoRoutes(capable, capable).resolve();
            fail("A capability cannot override PC identity");
        } catch (ReconnectHostResolver.TerminalException expected) {
            assertEquals(401, expected.errorCode);
        }
        original.uuid = "original-pc";
        original.pairState = PairingManager.PairState.NOT_PAIRED;
        try {
            twoRoutes(capable, capable).resolve();
            fail("A capability cannot override pairing");
        } catch (ReconnectHostResolver.TerminalException expected) {
            assertEquals(401, expected.errorCode);
        }
    }

    @Test public void capabilityParserFailsClosedForUnknownMalformedOrDuplicatedDeclarations() {
        assertTrue(ReconnectHostResolver.supportsFastMicHandoff(
                "<root><ApolloMicResumeHandoff>1</ApolloMicResumeHandoff></root>"));
        assertTrue(ReconnectHostResolver.supportsFastMicHandoff(
                "<root><ApolloMicResumeHandoff> 1 </ApolloMicResumeHandoff></root>"));
        for (String rejected : new String[]{null, "", "authenticated response", "<root/>",
                "<root><ApolloMicResumeHandoff>0</ApolloMicResumeHandoff></root>",
                "<root><ApolloMicResumeHandoff>2</ApolloMicResumeHandoff></root>",
                "<root><ApolloMicResumeHandoff>true</ApolloMicResumeHandoff></root>",
                "<root><ApolloMicResumeHandoff>1</ApolloMicResumeHandoff>",
                "<root><ApolloMicResumeHandoff>1</ApolloMicResumeHandoff><ApolloMicResumeHandoff>1</ApolloMicResumeHandoff></root>",
                "<root><nested><ApolloMicResumeHandoff>1</ApolloMicResumeHandoff></nested></root>",
                "<root xmlns:x=\"urn:x\"><x:ApolloMicResumeHandoff>1</x:ApolloMicResumeHandoff></root>",
                "<root><ApolloMicResumeHandoff><value>1</value></ApolloMicResumeHandoff></root>",
                "<!DOCTYPE root [<!ENTITY supported \"1\">]><root><ApolloMicResumeHandoff>&supported;</ApolloMicResumeHandoff></root>"}) {
            assertFalse("Unexpected capability in " + rejected,
                    ReconnectHostResolver.supportsFastMicHandoff(rejected));
        }
        assertFalse(new ReconnectHostResolver.Result(address("legacy"), 47984).supportsFastMicHandoff);
    }

    @Test public void mismatchedCertificateCandidateDoesNotRejectAnotherValidRoute() throws Exception {
        NvHTTP wrong = mock(NvHTTP.class);
        when(wrong.getServerInfoStrict()).thenThrow(new HostHttpResponseException(401, "Certificate mismatch"));
        NvHTTP valid = response("original-pc", PairingManager.PairState.PAIRED);
        ReconnectHostResolver resolver = twoRoutes(wrong, valid);
        assertEquals(address("valid"), resolver.resolve().address);
    }

    @Test public void allUnreachableIsRetryableButIdentityAndUnpairedAreTerminal() throws Exception {
        NvHTTP unreachable = mock(NvHTTP.class);
        when(unreachable.getServerInfoStrict()).thenThrow(new IOException("No route"));
        try { twoRoutes(unreachable, unreachable).resolve(); fail(); }
        catch (ReconnectHostResolver.TerminalException wrong) { fail("Network outage must remain retryable"); }
        catch (IOException expected) { }
        for (NvHTTP denied : new NvHTTP[]{response("wrong-pc", PairingManager.PairState.PAIRED),
                response("original-pc", PairingManager.PairState.NOT_PAIRED)}) {
            try { twoRoutes(denied, unreachable).resolve(); fail(); }
            catch (ReconnectHostResolver.TerminalException expected) { assertEquals(401, expected.errorCode); }
        }
    }

    @Test public void missingPinNeverOpensNetworkProbe() throws Exception {
        ReconnectHostResolver resolver = new ReconnectHostResolver(context, "original-pc", null, "client", address("fallback"), 47984);
        try { resolver.resolve(); fail(); }
        catch (ReconnectHostResolver.TerminalException expected) { assertEquals(401, expected.errorCode); }
    }

    @Test public void cancelStopsInflightRequestsAndRejectsTheirLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        NvHTTP http = response("original-pc", PairingManager.PairState.PAIRED);
        when(http.getServerInfoStrict()).thenAnswer(call -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return "authenticated response";
        });
        ReconnectHostResolver resolver = twoRoutes(http, http);
        FutureTask<Object> task = new FutureTask<>(() -> {
            try { return resolver.resolve(); } catch (InterruptedException expected) { return expected; }
        });
        Thread thread = new Thread(task);
        thread.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        resolver.cancel(); release.countDown();
        assertTrue(task.get(3, TimeUnit.SECONDS) instanceof InterruptedException);
        verify(http, atLeastOnce()).cancelPendingRequests();
    }

    private ReconnectHostResolver twoRoutes(NvHTTP first, NvHTTP second) {
        return new ReconnectHostResolver(context, "original-pc", pin, "client", address("valid"), 47984) {
            @Override protected List<ComputerDetails.AddressTuple> loadCandidates() {
                return Arrays.asList(address("first"), address("valid"));
            }
            @Override protected NvHTTP createHttp(ComputerDetails.AddressTuple address) {
                return address.address.equals("first") ? first : second;
            }
        };
    }
}
