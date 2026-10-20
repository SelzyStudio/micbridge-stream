package com.limelight.nvstream;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.shadows.ShadowMoonBridge;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowMoonBridge.class)
public class NvConnectionBackgroundTest {
    @Before public void setUp() {
        ShadowMoonBridge.reset();
        ReflectionHelpers.setStaticField(NvConnection.class, "bridgeOwner", null);
        ReflectionHelpers.setStaticField(NvConnection.class, "bridgeGeneration", 0L);
        ReflectionHelpers.setStaticField(NvConnection.class, "connectionAllowed", new Semaphore(1));
    }

    @Test public void requestBeforeStartIsAppliedToItsOwnSuccessfulConnection() {
        NvConnection connection = createConnection();
        assertEquals(0, connection.setStreamBackground(true));
        assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
        assertEquals(0, start(connection));
        assertEquals(Arrays.asList(true), ShadowMoonBridge.backgroundRequests);
        assertEquals(0, connection.setStreamBackground(false));
        assertEquals(Arrays.asList(true, false), ShadowMoonBridge.backgroundRequests);
    }

    @Test public void returnBeforeHandshakeDiscardsStaleBackgroundRequest() {
        NvConnection connection = createConnection();
        connection.setStreamBackground(true);
        connection.setStreamBackground(false);
        assertEquals(0, start(connection));
        assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
    }

    @Test public void staleConnectionCannotPauseOrInterruptANewerOwner() {
        NvConnection stale = createConnection();
        NvConnection current = createConnection();
        assertEquals(0, start(stale));
        assertEquals(0, start(current));
        assertEquals(-1, stale.setStreamBackground(true));
        stale.stop();
        assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
        assertEquals(0, ShadowMoonBridge.interruptConnectionCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionCount);
        assertEquals(0, current.setStreamBackground(true));
        assertEquals(Arrays.asList(true), ShadowMoonBridge.backgroundRequests);
    }

    @Test public void stopIsIdempotentAndNeverSendsAfterNativeCleanup() {
        NvConnection connection = createConnection();
        assertEquals(0, start(connection));
        connection.stop();
        connection.stop();
        assertEquals(-1, connection.setStreamBackground(true));
        assertEquals(1, ShadowMoonBridge.interruptConnectionCount);
        assertEquals(1, ShadowMoonBridge.stopConnectionCount);
        assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
        Semaphore permits = ReflectionHelpers.getStaticField(NvConnection.class, "connectionAllowed");
        // This fixture never acquired a start permit, so stop cannot invent one.
        assertEquals(1, permits.availablePermits());
    }

    @Test public void unsupportedHostReturnsUnsupportedWithoutExecutingServerCommands() {
        NvConnection connection = createConnection();
        assertEquals(0, start(connection));
        ShadowMoonBridge.backgroundResult = -2;
        assertEquals(-2, connection.setStreamBackground(true));
        assertEquals(Arrays.asList(true), ShadowMoonBridge.backgroundRequests);
    }

    @Test public void failedOrCancelledStartNeverClaimsANativeBackgroundSender() {
        NvConnection failed = createConnection();
        ShadowMoonBridge.startConnectionResult = -5;
        assertEquals(-5, start(failed));
        assertEquals(-1, failed.setStreamBackground(true));
        assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
        NvConnection cancelled = createConnection();
        cancelled.stop();
        ShadowMoonBridge.startConnectionResult = 0;
        assertEquals(-1, start(cancelled));
        assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
    }

    @Test public void backgroundRequestReturnsDuringBlockedNativeHandshakeAndIsNotLost() throws Exception {
        NvConnection connection = createConnection();
        ShadowMoonBridge.startEntered = new java.util.concurrent.CountDownLatch(1);
        ShadowMoonBridge.startRelease = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<Integer> handshake = new java.util.concurrent.FutureTask<>(() -> start(connection));
        Thread starter = new Thread(handshake, "test-native-handshake");
        starter.start();
        assertTrue(ShadowMoonBridge.startEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.FutureTask<Integer> uiRequest = new java.util.concurrent.FutureTask<>(
                () -> {
                    connection.releasePressedKeys();
                    return connection.setStreamBackground(true);
                });
        Thread requester = new Thread(uiRequest, "test-ui-background-request");
        requester.start();
        try {
            // The native handshake still owns MoonBridge.class. The UI request
            // must queue and return rather than acquiring that lifetime lock.
            assertEquals(Integer.valueOf(0), uiRequest.get(500, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertTrue(ShadowMoonBridge.backgroundRequests.isEmpty());
        } finally {
            ShadowMoonBridge.startRelease.countDown();
            starter.join(2000);
            requester.join(2000);
        }
        assertEquals(Integer.valueOf(0), handshake.get());
        assertEquals(Arrays.asList(true), ShadowMoonBridge.backgroundRequests);
    }

    @Test public void pressedKeyReleasePreservesFlagsAndCoalescesRepeatedDown() {
        NvConnection connection = createConnection();
        assertEquals(0, start(connection));
        connection.sendKeyboardInput((short) 65, (byte) 3, (byte) 1, (byte) 2);
        connection.sendKeyboardInput((short) 65, (byte) 3, (byte) 1, (byte) 2);
        connection.releasePressedKeys();
        connection.releasePressedKeys();
        assertEquals(Arrays.asList("65:3:1:2", "65:3:1:2", "65:4:0:2"),
                ShadowMoonBridge.keyboardRequests);
    }

    @Test public void ordinaryAndLateKeyUpsRemainSafeAfterPressedKeyRelease() {
        NvConnection connection = createConnection();
        assertEquals(0, start(connection));
        connection.sendKeyboardInput((short) 65, (byte) 3, (byte) 0, (byte) 0);
        connection.sendKeyboardInput((short) 65, (byte) 4, (byte) 0, (byte) 0);
        connection.releasePressedKeys();
        assertEquals(2, ShadowMoonBridge.keyboardRequests.size());
        connection.sendKeyboardInput((short) 66, (byte) 3, (byte) 0, (byte) 1);
        connection.releasePressedKeys();
        connection.sendKeyboardInput((short) 66, (byte) 4, (byte) 0, (byte) 1);
        connection.releasePressedKeys();
        assertEquals(Arrays.asList("65:3:0:0", "65:4:0:0", "66:3:0:1", "66:4:0:1", "66:4:0:1"),
                ShadowMoonBridge.keyboardRequests);
    }

    @Test public void staleKeyReleaseCannotReleaseNewConnectionsKeys() {
        NvConnection stale = createConnection();
        NvConnection current = createConnection();
        assertEquals(0, start(stale));
        stale.sendKeyboardInput((short) 65, (byte) 3, (byte) 0, (byte) 0);
        assertEquals(0, start(current));
        current.sendKeyboardInput((short) 66, (byte) 3, (byte) 0, (byte) 0);
        stale.releasePressedKeys();
        stale.sendKeyboardInput((short) 65, (byte) 4, (byte) 0, (byte) 0);
        current.releasePressedKeys();
        assertEquals(Arrays.asList("65:3:0:0", "66:3:0:0", "66:4:0:0"),
                ShadowMoonBridge.keyboardRequests);
    }

    private static int start(NvConnection connection) {
        ConnectionContext context = ReflectionHelpers.getField(connection, "context");
        context.serverAppVersion = "7.1.431.0";
        context.serverGfeVersion = "3.0.0.0";
        context.rtspSessionUrl = "rtsp://127.0.0.1";
        context.negotiatedWidth = 1280;
        context.negotiatedHeight = 720;
        context.negotiatedPacketSize = 1024;
        return NvConnection.startBridgeConnection(connection, context, new byte[16], null, null, null);
    }

    private static NvConnection createConnection() {
        Context context = ApplicationProvider.getApplicationContext();
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setAudioConfiguration(new MoonBridge.AudioConfiguration(2, 3)).build();
        return new NvConnection(context, new ComputerDetails.AddressTuple("127.0.0.1", 47984),
                47989, "test", config, null, null);
    }
}
