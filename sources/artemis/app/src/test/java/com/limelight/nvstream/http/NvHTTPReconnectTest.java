package com.limelight.nvstream.http;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.limelight.shadows.ShadowMoonBridge;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.io.InterruptedIOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.X509TrustManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowMoonBridge.class)
public class NvHTTPReconnectTest {
    private final X509Certificate pin = mock(X509Certificate.class);

    @Test public void strictUnauthorizedResponseNeverFallsBackToHttp() throws Exception {
        List<String> requests = new ArrayList<>();
        NvHTTP http = client(requests, 401, "denied");
        try { http.getServerInfoStrict(); fail(); }
        catch (HostHttpResponseException expected) { assertEquals(401, expected.getErrorCode()); }
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("https://"));
    }

    @Test public void strictPinRejectsDifferentCertificateEvenWithDefaultCaTrust() throws Exception {
        NvHTTP http = client(new ArrayList<>(), 401, "denied");
        try { http.getServerInfoStrict(); } catch (HostHttpResponseException expected) { }
        X509TrustManager trust = ReflectionHelpers.getField(http, "trustManager");
        trust.checkServerTrusted(new X509Certificate[]{pin}, "RSA");
        try {
            trust.checkServerTrusted(new X509Certificate[]{mock(X509Certificate.class)}, "RSA");
            fail("A different CA-trusted certificate must never replace the original pin");
        } catch (CertificateException expected) { }
    }

    @Test public void strictModeRequiresPinAndCancellationRejectsLaterRequests() throws Exception {
        NvHTTP unpinned = new NvHTTP(new ComputerDetails.AddressTuple("host", 47989), 47984,
                "client", null, mock(LimelightCryptoProvider.class));
        try { unpinned.getServerInfoStrict(); fail(); }
        catch (HostHttpResponseException expected) { assertEquals(401, expected.getErrorCode()); }
        List<String> requests = new ArrayList<>();
        NvHTTP http = client(requests, 200, "unused");
        http.cancelPendingRequests();
        try { http.getServerInfoStrict(); fail(); } catch (InterruptedIOException expected) { }
        assertTrue(requests.isEmpty());
    }

    @Test public void strictRequestDisablesRedirectsAndHasBoundedCallTimeout() throws Exception {
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("host", 47989), 47984,
                "client", pin, mock(LimelightCryptoProvider.class));
        final int[] timeout = new int[1];
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            timeout[0] = chain.call().timeout().timeoutNanos() > 0 ? 1 : 0;
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Moved").header("Location", "http://other-host/serverinfo")
                    .body(ResponseBody.create("", MediaType.get("text/plain"))).build();
        }).build();
        ReflectionHelpers.setField(http, "httpClientShortConnectTimeout", client);
        try { http.getServerInfoStrict(); fail(); }
        catch (HostHttpResponseException expected) { assertEquals(302, expected.getErrorCode()); }
        assertEquals(1, timeout[0]);
    }

    private NvHTTP client(List<String> requests, int status, String body) throws Exception {
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("host", 47989), 47984,
                "client", pin, mock(LimelightCryptoProvider.class));
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request().url().toString());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Test response")
                    .body(ResponseBody.create(body, MediaType.get("text/xml"))).build();
        }).build();
        ReflectionHelpers.setField(http, "httpClientShortConnectTimeout", client);
        return http;
    }
}
