package com.limelight.nvstream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.shadows.ShadowMoonBridge;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowMoonBridge.class)
public class NvConnectionReconnectTest {
    private NvHTTP http;
    private NvConnection connection;
    private ComputerDetails details;

    @Before public void setUp() throws Exception {
        ShadowMoonBridge.reset();
        http = mock(NvHTTP.class);
        when(http.getServerInfoStrict()).thenReturn("info");
        when(http.getServerVersion("info")).thenReturn("7.1.0.0");
        details = new ComputerDetails(); details.uuid = "ORIGINAL-PC";
        when(http.getComputerDetails("info")).thenReturn(details);
        when(http.getPairState("info")).thenReturn(PairingManager.PairState.PAIRED);
        when(http.getCurrentGame("info")).thenReturn(42);
        when(http.getCurrentGameUUID("info")).thenReturn("DESKTOP-UUID");
        when(http.launchApp(any(), eq("resume"), any(), anyInt(), anyBoolean())).thenReturn(true);
        Context appContext = ApplicationProvider.getApplicationContext();
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setApp(new NvApp("Desktop", "desktop-uuid", 42, false))
                .setResolution(1920, 1080)
                .setAudioConfiguration(new MoonBridge.AudioConfiguration(2, 3))
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_REMOTE)
                .build();
        connection = new NvConnection(appContext, new ComputerDetails.AddressTuple("host", 47989),
                47984, "client", config, null, null) {
            @Override protected NvHTTP createStartHttp() { return http; }
        };
        ConnectionContext context = ReflectionHelpers.getField(connection, "context");
        context.connListener = mock(NvConnectionListener.class);
        connection.setResumeOnly("original-pc");
    }

    @Test public void authenticatedSamePcAndAppUsesOnlyResume() throws Exception {
        assertTrue(connection.startApp());
        verify(http).getServerInfoStrict();
        verify(http, never()).getServerInfo(anyBoolean());
        verify(http).launchApp(any(), eq("resume"), eq("desktop-uuid"), eq(42), anyBoolean());
        verify(http, never()).quitApp();
        verify(http, never()).launchApp(any(), eq("launch"), any(), anyInt(), anyBoolean());
    }

    @Test public void sameNumericIdCannotReplaceDifferentAppUuid() throws Exception {
        when(http.getCurrentGameUUID("info")).thenReturn("another-app");
        assertRejected(409);
    }

    @Test public void legacyHostWithoutAppUuidMayMatchSameId() throws Exception {
        when(http.getCurrentGameUUID("info")).thenReturn(null);
        assertTrue(connection.startApp());
    }

    @Test public void missingSessionCannotLaunchDesktopAgain() throws Exception {
        when(http.getCurrentGame("info")).thenReturn(0);
        when(http.getCurrentGameUUID("info")).thenReturn(null);
        assertRejected(410);
    }

    @Test public void wrongComputerOrRevokedPairingCannotResume() throws Exception {
        details.uuid = "other-pc";
        assertRejected(401);
        details.uuid = "original-pc";
        when(http.getPairState("info")).thenReturn(PairingManager.PairState.NOT_PAIRED);
        assertRejected(401);
    }

    @Test public void stoppedConnectionDoesNotEvenProbeHttp() throws Exception {
        connection.stop();
        try { connection.startApp(); fail(); } catch (InterruptedIOException expected) { }
        verify(http, never()).getServerInfoStrict();
    }

    @Test public void stopCancelsInFlightProbeAndPreventsLateResume() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(http.getServerInfoStrict()).thenAnswer(call -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return "info";
        });
        FutureTask<Object> start = new FutureTask<>(() -> {
            try { return connection.startApp(); } catch (InterruptedIOException expected) { return expected; }
        });
        new Thread(start).start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        connection.stop();
        verify(http).cancelPendingRequests();
        release.countDown();
        assertTrue(start.get(3, TimeUnit.SECONDS) instanceof InterruptedIOException);
        verify(http, never()).launchApp(any(), anyString(), any(), anyInt(), anyBoolean());
    }

    @Test public void stopAfterResumeResponseCannotAdvanceToNativeStart() throws Exception {
        when(http.launchApp(any(), eq("resume"), any(), anyInt(), anyBoolean())).thenAnswer(call -> {
            connection.stop(); return true;
        });
        try { connection.startApp(); fail(); } catch (InterruptedIOException expected) { }
        assertEquals(0, ShadowMoonBridge.stopConnectionCount);
    }

    private void assertRejected(int errorCode) throws Exception {
        try { connection.startApp(); fail(); }
        catch (HostHttpResponseException expected) { assertEquals(errorCode, expected.getErrorCode()); }
        verify(http, never()).quitApp();
        verify(http, never()).launchApp(any(), anyString(), any(), anyInt(), anyBoolean());
    }
}
