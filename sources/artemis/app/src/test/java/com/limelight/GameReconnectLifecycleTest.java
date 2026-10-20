package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.audio.MicrophoneCaptureManager;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.ResumableVideoDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.reconnect.MicReconnectHandoffGate;
import com.limelight.reconnect.StreamReconnectController;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises Game's real recovery entry points; only host resolution is replaced with a recorder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, shadows = ShadowMoonBridge.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GameReconnectLifecycleTest {
    public static class TestGame extends Game {
        int finishCount;
        int resolveCount;
        long resolveGeneration;
        @Override public void finish() { finishCount++; }
        @Override public boolean isFinishing() { return false; }
        @Override public void setMetaKeyCaptureState(boolean enabled) { }
        @Override protected void resolveReconnectTarget(long generation) {
            resolveCount++;
            resolveGeneration = generation;
        }
    }

    private TestGame game;
    private NvConnection connection;
    private ControllerHandler input;
    private InputCaptureProvider capture;
    private ResumableVideoDecoderRenderer video;
    private AndroidAudioRenderer audio;
    private MicrophoneCaptureManager microphone;
    private StreamReconnectController reconnect;
    private ExecutorService executor;

    @Before public void setUp() {
        game = Robolectric.buildActivity(TestGame.class).get();
        // Initialize AppCompat window state without Game.onCreate() opening a network session.
        try {
            java.lang.invoke.MethodHandles.privateLookupIn(Game.class, java.lang.invoke.MethodHandles.lookup())
                    .findSpecial(androidx.appcompat.app.AppCompatActivity.class, "onCreate",
                            java.lang.invoke.MethodType.methodType(void.class, android.os.Bundle.class), Game.class)
                    .bindTo(game).invokeWithArguments((Object) null);
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        connection = mock(NvConnection.class);
        input = mock(ControllerHandler.class);
        capture = mock(InputCaptureProvider.class);
        video = mock(ResumableVideoDecoderRenderer.class);
        audio = mock(AndroidAudioRenderer.class);
        microphone = mock(MicrophoneCaptureManager.class);
        game.conn = connection;
        game.connected = true;
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "decoderRenderer", mock(MediaCodecDecoderRenderer.class));
        ReflectionHelpers.setField(game, "controllerHandler", input);
        ReflectionHelpers.setField(game, "inputCaptureProvider", capture);
        ReflectionHelpers.setField(game, "sessionVideoRenderer", video);
        ReflectionHelpers.setField(game, "sessionAudioRenderer", audio);
        ReflectionHelpers.setField(game, "microphoneCaptureManager", microphone);
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(game, "tombstonePrefs",
                game.getSharedPreferences("reconnect-lifecycle", Context.MODE_PRIVATE));
        ReflectionHelpers.setField(game, "activityStarted", true);
        ReflectionHelpers.setField(game, "activityResumed", true);
        ReflectionHelpers.setField(game, "autoReconnectArmed", true);
        ReflectionHelpers.callInstanceMethod(game, "initializeReconnectController");
        reconnect = ReflectionHelpers.getField(game, "reconnectController");
        executor = ReflectionHelpers.getField(game, "microphoneExecutor");
    }

    @After public void tearDown() throws Exception {
        reconnect.close();
        ((Handler) ReflectionHelpers.getField(game, "timerHandler")).removeCallbacksAndMessages(null);
        executor.shutdownNow();
        assertTrue("stream worker did not shut down", executor.awaitTermination(3, TimeUnit.SECONDS));
        Game.instance = null;
    }

    @Test public void networkTerminationReleasesInputAndSuspendsAllMediaBeforeRetry() throws Exception {
        game.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game.connectionTerminated(-1);
        idleMain();

        assertTrue(reconnect.isRecovering());
        assertFalse(game.connected);
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "connecting"));
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "sessionClosing"));
        verify(capture).disableCapture();
        verify(input).setSuspended(true);
        verify(connection).releasePressedKeys();
        verify(connection).sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL,
                0, 0, 0, 0, 0, 0, MoonBridge.LI_ROT_UNKNOWN);
        for (byte button = MouseButtonPacket.BUTTON_LEFT; button <= MouseButtonPacket.BUTTON_X2; button++) {
            verify(connection).sendMouseButtonUp(button);
        }
        verify(microphone).setCaptureAllowed(false);
        verify(video).setForeground(false);
        verify(audio).setSuspended(true);
        assertEquals(0, game.getWindow().getAttributes().flags &
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drainWorker();
        verify(microphone).stop();
        verify(connection).stopForReconnect();
        verify(connection, never()).stop();
        assertEquals(0, game.resolveCount);
        assertEquals(0, game.finishCount);
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "displayedFailureDialog"));
        advance(1000);
        assertEquals(1, game.resolveCount);
        assertTrue(reconnect.isCurrentAttempt(game.resolveGeneration));
        verify(connection, never()).start(any(), any(), any());
    }

    @Test public void confirmedDefaultNetworkChangeStartsResolutionImmediatelyAndUsesFastStop() throws Exception {
        ReflectionHelpers.callInstanceMethod(game, "requestAutomaticReconnect",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        idleMain();
        drainWorker();
        assertEquals("no artificial first retry delay for a confirmed route change", 1, game.resolveCount);
        verify(connection).stopForReconnect();
        verify(connection, never()).stop();
        assertTrue(reconnect.isCurrentAttempt(game.resolveGeneration));
        assertFalse(game.connected);
    }

    @Test public void knownRouteLossWhileOfflineWaitsForNetworkWithoutTheHalfSecondResumeDelay() throws Exception {
        reconnect.setNetworkAvailable(false);
        ReflectionHelpers.callInstanceMethod(game, "requestAutomaticReconnect",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        idleMain();
        drainWorker();
        assertEquals(0, game.resolveCount);
        reconnect.setNetworkAvailable(true);
        idleMain();
        assertEquals(1, game.resolveCount);
        verify(connection).stopForReconnect();
        verify(connection, never()).stop();
    }

    @Test public void explicitStopAfterFastRecoveryStillUsesTheOrdinaryStopApi() throws Exception {
        ReflectionHelpers.callInstanceMethod(game, "requestAutomaticReconnect",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        idleMain();
        drainWorker();
        verify(connection).stopForReconnect();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        drainWorker();
        verify(connection).stop();
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "sessionClosing"));
        assertFalse(reconnect.isRecovering());
    }

    @Test public void queuedRouteChangeExpeditesTheSameRetiredTransportWithoutStoppingItTwice() throws Exception {
        game.connectionTerminated(-1);
        ReflectionHelpers.callInstanceMethod(game, "requestAutomaticReconnect",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        idleMain();
        drainWorker();
        assertEquals(1, game.resolveCount);
        verify(connection, times(1)).stopForReconnect();
        verify(connection, never()).stop();
        assertTrue(reconnect.isCurrentAttempt(game.resolveGeneration));
    }

    @Test public void defaultNetworkCallbackAfterNativeRecoveryExpeditesItsStillPendingFirstAttempt() throws Exception {
        ReflectionHelpers.callInstanceMethod(game, "registerReconnectNetworkMonitor");
        try {
            Network previous = mock(Network.class);
            Network replacement = mock(Network.class);
            ReflectionHelpers.setField(game, "reconnectDefaultNetwork", previous);
            reconnect.setNetworkAvailable(true);
            game.connectionTerminated(-1);
            idleMain();
            drainWorker();
            assertTrue(reconnect.isRecovering());
            assertFalse(game.connected);
            assertEquals(0, reconnect.getAttemptCount());
            assertEquals(0, game.resolveCount);

            ConnectivityManager.NetworkCallback callback = ReflectionHelpers.getField(game, "reconnectNetworkCallback");
            callback.onAvailable(replacement);
            idleMain();

            assertEquals("a real default route change must remove the remaining initial timer", 1, game.resolveCount);
            assertTrue(reconnect.isCurrentAttempt(game.resolveGeneration));
            verify(connection, times(1)).stopForReconnect();
            verify(connection, never()).stop();
        } finally {
            ReflectionHelpers.callInstanceMethod(game, "unregisterReconnectNetworkMonitor");
        }
    }

    @Test public void nativeStopRunsOffMainThreadAndDoesNotBlockRecoveryUi() throws Exception {
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        AtomicReference<Thread> stopThread = new AtomicReference<>();
        doAnswer(invocation -> {
            stopThread.set(Thread.currentThread());
            stopEntered.countDown();
            assertTrue("test did not release native stop", releaseStop.await(3, TimeUnit.SECONDS));
            return null;
        }).when(connection).stopForReconnect();
        try {
            game.connectionTerminated(-1);
            idleMain();
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            assertNotSame(Looper.getMainLooper().getThread(), stopThread.get());
            assertTrue(reconnect.isRecovering());
            verify(microphone).setCaptureAllowed(false);
            verify(video).setForeground(false);
        } finally {
            releaseStop.countDown();
            drainWorker();
        }
    }

    @Test public void microphoneHandoffDeadlineStartsOnlyAfterNativeTransportStopCompletes() throws Exception {
        MicReconnectHandoffGate gate = mock(MicReconnectHandoffGate.class);
        ReflectionHelpers.setField(game, "microphoneHandoffGate", gate);
        ReflectionHelpers.<PreferenceConfiguration>getField(game, "prefConfig").enableMicrophone = true;
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        doAnswer(invocation -> {
            stopEntered.countDown();
            assertTrue("test did not release native stop", releaseStop.await(3, TimeUnit.SECONDS));
            return null;
        }).when(connection).stopForReconnect();
        try {
            game.connectionTerminated(-1);
            idleMain();
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            verifyNoInteractions(gate);
            assertTrue(reconnect.isRecovering());
        } finally {
            releaseStop.countDown();
            drainWorker();
        }
        InOrder ordered = inOrder(connection, gate);
        ordered.verify(connection).stopForReconnect();
        ordered.verify(gate).transportStopped(true);
        verifyNoMoreInteractions(gate);
    }

    @Test public void microphoneDisabledTransportStopDoesNotRequestAMicrophoneLeaseDelay() throws Exception {
        MicReconnectHandoffGate gate = mock(MicReconnectHandoffGate.class);
        ReflectionHelpers.setField(game, "microphoneHandoffGate", gate);
        ReflectionHelpers.<PreferenceConfiguration>getField(game, "prefConfig").enableMicrophone = false;

        game.connectionTerminated(-1);
        idleMain();
        drainWorker();

        InOrder ordered = inOrder(connection, gate);
        ordered.verify(connection).stopForReconnect();
        ordered.verify(gate).transportStopped(false);
        verifyNoMoreInteractions(gate);
        assertTrue(reconnect.isRecovering());
    }

    @Test public void duplicateNativeFailuresCoalesceIntoOneStopAndOneRecoveryAttempt() throws Exception {
        game.connectionTerminated(-1);
        game.connectionTerminated(MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC);
        assertFalse(game.stageFailed("RTSP", 1, -1));
        idleMain();
        drainWorker();
        verify(connection, times(1)).stopForReconnect();
        verify(microphone, times(1)).setCaptureAllowed(false);
        game.connectionTerminated(-1);
        assertFalse(game.stageFailed("RTSP", 1, -1));
        advance(1000);
        assertEquals(1, game.resolveCount);
        advance(60000);
        assertEquals("an unresolved attempt must not overlap another", 1, game.resolveCount);
        verify(connection, times(1)).stopForReconnect();
    }

    @Test public void lateHandshakeSuccessCannotReviveTheFailedTransportOrCancelRecovery() throws Exception {
        game.connectionTerminated(-1);
        idleMain();
        drainWorker();
        assertTrue(reconnect.isRecovering());
        assertFalse(game.connected);
        clearInvocations(connection, video, audio, microphone, input, capture);

        game.connectionStarted();
        idleMain();

        assertFalse(game.connected);
        assertTrue(reconnect.isRecovering());
        verifyNoInteractions(connection, video, audio, microphone, input, capture);
        advance(1000);
        assertEquals(1, game.resolveCount);
        assertTrue(reconnect.isCurrentAttempt(game.resolveGeneration));
    }

    @Test public void alreadyQueuedHandshakeSuccessRechecksRecoveryOnTheUiThread() throws Exception {
        // Both callbacks enter before the paused UI thread starts recovery. The queued
        // success must check again after the termination callback has suspended the stream.
        game.connectionTerminated(-1);
        Thread lateHandshake = new Thread(game::connectionStarted, "late-handshake-test");
        lateHandshake.start();
        lateHandshake.join(2000);
        assertFalse("late callback thread did not finish", lateHandshake.isAlive());
        idleMain();
        drainWorker();

        assertFalse(game.connected);
        assertTrue(reconnect.isRecovering());
        verify(video, never()).setForeground(true);
        verify(audio, never()).setSuspended(false);
        advance(1000);
        assertEquals(1, game.resolveCount);
        assertTrue(reconnect.isCurrentAttempt(game.resolveGeneration));
    }

    @Test public void backgroundLossWaitsUntilTheAppReturns() throws Exception {
        game.onStop();
        game.connectionTerminated(-1);
        idleMain();
        drainWorker();
        assertTrue(reconnect.isRecovering());
        advance(60000);
        assertEquals(0, game.resolveCount);
        game.onStart();
        advance(499);
        assertEquals(0, game.resolveCount);
        advance(1);
        assertEquals(1, game.resolveCount);
        assertEquals(0, game.finishCount);
        // onStart must not unpause a failed transport while resolution is pending.
        verify(video, never()).setForeground(true);
        verify(audio, never()).setSuspended(false);
    }

    @Test public void offlineLossWaitsForNetworkAndForegroundTogether() throws Exception {
        reconnect.setNetworkAvailable(false);
        game.connectionTerminated(-1);
        idleMain();
        drainWorker();
        advance(60000);
        assertEquals(0, game.resolveCount);
        game.onStop();
        reconnect.setNetworkAvailable(true);
        advance(60000);
        assertEquals(0, game.resolveCount);
        game.onStart();
        advance(500);
        assertEquals(1, game.resolveCount);
    }

    @Test public void failedAttemptDefersAgainWhenAppIsCovered() throws Exception {
        game.connectionTerminated(-1);
        idleMain();
        drainWorker();
        advance(1000);
        long oldGeneration = game.resolveGeneration;
        game.onStop();
        reconnect.attemptFailed(oldGeneration);
        advance(60000);
        assertEquals(1, game.resolveCount);
        game.onStart();
        advance(500);
        assertEquals(2, game.resolveCount);
        assertTrue(game.resolveGeneration > oldGeneration);
    }

    @Test public void explicitDisconnectCancelsPendingRecoveryAndLateNativeCallbacks() throws Exception {
        game.connectionTerminated(-1);
        idleMain();
        drainWorker();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        drainWorker();
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "sessionClosing"));
        assertFalse(reconnect.isRecovering());
        clearInvocations(connection, video, audio, microphone);

        game.connectionTerminated(-1);
        assertFalse(game.stageFailed("RTSP", 1, -1));
        game.connectionStarted();
        game.onStart();
        advance(60000);
        assertEquals(0, game.resolveCount);
        assertFalse(game.connected);
        verifyNoInteractions(connection, video, audio, microphone);
    }

    @Test public void closingInFlightResolutionRejectsItsLateFailureAndFutureNetworkEvents() throws Exception {
        game.connectionTerminated(-1);
        idleMain();
        drainWorker();
        advance(1000);
        long generation = game.resolveGeneration;
        assertTrue(reconnect.isCurrentAttempt(generation));
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        drainWorker();
        reconnect.attemptFailed(generation);
        reconnect.setNetworkAvailable(false);
        reconnect.setNetworkAvailable(true);
        game.onStop();
        game.onStart();
        advance(60000);
        assertFalse(reconnect.isCurrentAttempt(generation));
        assertEquals(1, game.resolveCount);
        assertFalse(game.connected);
    }

    @Test public void resumedHandshakePortFailureUsesAutomaticRecoveryInsteadOfPortDiagnostics() throws Exception {
        // The shadow has no implementation for connectivity diagnostics: reaching the old
        // branch would invoke native code and fail this test before recovery can be scheduled.
        game.connected = false;
        ReflectionHelpers.setField(game, "connecting", true);
        ReflectionHelpers.setField(game, "resumeOnlyReconnect", true);
        assertFalse(game.stageFailed("RTSP handshake", 1, -1));
        idleMain();
        drainWorker();
        assertTrue(reconnect.isRecovering());
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "connecting"));
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "sessionClosing"));
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "displayedFailureDialog"));
        advance(1000);
        assertEquals(1, game.resolveCount);
        assertEquals(0, game.finishCount);
    }

    private void drainWorker() throws Exception {
        executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void advance(long milliseconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }
}
