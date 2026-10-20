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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowMoonBridge.class)
public class NvConnectionRecoveryStopTest {
    @Before public void setUp() {
        ShadowMoonBridge.reset();
        ReflectionHelpers.setStaticField(NvConnection.class, "bridgeOwner", null);
        ReflectionHelpers.setStaticField(NvConnection.class, "bridgeGeneration", 0L);
        ReflectionHelpers.setStaticField(NvConnection.class, "connectionAllowed", new Semaphore(1));
    }

    @Test public void recoveryUsesOnlyItsDedicatedFastInterruptAndStop() {
        NvConnection connection = createConnection();
        assertEquals(0, start(connection));
        connection.stopForReconnect();
        connection.stopForReconnect();
        assertEquals(1, ShadowMoonBridge.interruptConnectionForReconnectCount);
        assertEquals(1, ShadowMoonBridge.stopConnectionForReconnectCount);
        assertEquals(0, ShadowMoonBridge.interruptConnectionCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionCount);
    }

    @Test public void ordinaryDisconnectKeepsGracefulNativeStop() {
        NvConnection connection = createConnection();
        assertEquals(0, start(connection));
        connection.stop();
        assertEquals(1, ShadowMoonBridge.interruptConnectionCount);
        assertEquals(1, ShadowMoonBridge.stopConnectionCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionForReconnectCount);
        assertFalse(ShadowMoonBridge.lastOrdinaryStopUsedRecoveryClose);
    }

    @Test public void nextOwnerResetsFastCloseAndStaleOwnerCannotChangeIt() {
        NvConnection stale = createConnection(), current = createConnection();
        assertEquals(0, start(stale));
        stale.stopForReconnect();
        assertTrue(ShadowMoonBridge.recoveryCloseRequested);
        assertEquals(0, start(current));
        assertFalse(ShadowMoonBridge.recoveryCloseRequested);
        stale.stopForReconnect();
        assertEquals(1, ShadowMoonBridge.interruptConnectionForReconnectCount);
        assertFalse(ShadowMoonBridge.recoveryCloseRequested);
        current.stop();
        assertEquals(1, ShadowMoonBridge.stopConnectionCount);
        assertFalse(ShadowMoonBridge.lastOrdinaryStopUsedRecoveryClose);
        assertEquals(2, ShadowMoonBridge.prepareConnectionStartCount);
    }

    @Test public void stopBeforeAnyNativeOwnerDoesNotChangeGlobalCloseMode() {
        NvConnection cancelled = createConnection();
        cancelled.stopForReconnect();
        assertEquals(-1, start(cancelled));
        assertEquals(0, ShadowMoonBridge.prepareConnectionStartCount);
        assertEquals(0, ShadowMoonBridge.interruptConnectionForReconnectCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionForReconnectCount);
    }

    @Test public void cancelBetweenPrepareAndNativeEntryUsesFastCleanup() throws Exception {
        NvConnection connection = createConnection();
        ShadowMoonBridge.setupEntered = new CountDownLatch(1);
        ShadowMoonBridge.setupRelease = new CountDownLatch(1);
        ShadowMoonBridge.recoveryInterruptEntered = new CountDownLatch(1);
        FutureTask<Integer> handshake = new FutureTask<>(() -> start(connection));
        Thread starter = new Thread(handshake, "test-prepared-start");
        starter.start();
        assertTrue(ShadowMoonBridge.setupEntered.await(2, TimeUnit.SECONDS));
        FutureTask<Void> stopping = new FutureTask<>(() -> { connection.stopForReconnect(); return null; });
        Thread stopper = new Thread(stopping, "test-early-recovery-cancel");
        stopper.start();
        assertTrue(ShadowMoonBridge.recoveryInterruptEntered.await(2, TimeUnit.SECONDS));
        assertEquals(0, ShadowMoonBridge.stopConnectionForReconnectCount);
        ShadowMoonBridge.setupRelease.countDown();
        assertEquals(Integer.valueOf(-1), handshake.get(2, TimeUnit.SECONDS));
        stopping.get(2, TimeUnit.SECONDS);
        assertEquals(1, ShadowMoonBridge.stopConnectionForReconnectCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionCount);
        assertNull(ReflectionHelpers.getStaticField(NvConnection.class, "bridgeOwner"));
    }

    @Test public void inFlightNativeStartFailureStillReceivesRecoveryInterruptOnly() throws Exception {
        NvConnection connection = createConnection();
        ShadowMoonBridge.startEntered = new CountDownLatch(1);
        ShadowMoonBridge.startRelease = new CountDownLatch(1);
        ShadowMoonBridge.recoveryInterruptEntered = new CountDownLatch(1);
        ShadowMoonBridge.startConnectionResult = -5;
        FutureTask<Integer> handshake = new FutureTask<>(() -> start(connection));
        new Thread(handshake, "test-failed-native-start").start();
        assertTrue(ShadowMoonBridge.startEntered.await(2, TimeUnit.SECONDS));
        FutureTask<Void> stopping = new FutureTask<>(() -> { connection.stopForReconnect(); return null; });
        new Thread(stopping, "test-native-recovery-stop").start();
        assertTrue(ShadowMoonBridge.recoveryInterruptEntered.await(2, TimeUnit.SECONDS));
        ShadowMoonBridge.startRelease.countDown();
        assertEquals(Integer.valueOf(-5), handshake.get(2, TimeUnit.SECONDS));
        stopping.get(2, TimeUnit.SECONDS);
        assertEquals(1, ShadowMoonBridge.interruptConnectionForReconnectCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionCount);
        assertEquals(0, ShadowMoonBridge.stopConnectionForReconnectCount);
    }

    @Test public void staleRecoveryStopDoesNotWaitForNewOwnersBlockedHandshake() throws Exception {
        NvConnection stale = createConnection(), current = createConnection();
        assertEquals(0, start(stale));
        ShadowMoonBridge.startEntered = new CountDownLatch(1);
        ShadowMoonBridge.startRelease = new CountDownLatch(1);
        FutureTask<Integer> handshake = new FutureTask<>(() -> start(current));
        new Thread(handshake, "test-new-owner-handshake").start();
        assertTrue(ShadowMoonBridge.startEntered.await(2, TimeUnit.SECONDS));
        FutureTask<Void> staleStop = new FutureTask<>(() -> { stale.stopForReconnect(); return null; });
        new Thread(staleStop, "test-stale-fast-stop").start();
        try {
            staleStop.get(300, TimeUnit.MILLISECONDS);
            assertEquals(0, ShadowMoonBridge.interruptConnectionForReconnectCount);
            assertFalse(ShadowMoonBridge.recoveryCloseRequested);
        } finally {
            ShadowMoonBridge.startRelease.countDown();
            handshake.get(2, TimeUnit.SECONDS);
        }
        current.stop();
        assertFalse(ShadowMoonBridge.lastOrdinaryStopUsedRecoveryClose);
    }

    private static NvConnection createConnection() {
        Context app = ApplicationProvider.getApplicationContext();
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setAudioConfiguration(new MoonBridge.AudioConfiguration(2, 3)).build();
        return new NvConnection(app, new ComputerDetails.AddressTuple("127.0.0.1", 47989),
                47984, "test", config, null, null);
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
}
