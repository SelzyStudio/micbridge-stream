package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;

import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.audio.MicrophoneCaptureManager;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.ResumableVideoDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.reconnect.ReconnectHostResolver;
import com.limelight.reconnect.StreamReconnectController;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.StreamContinuityOverlay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real Game transitions with native sockets/codecs replaced at their existing factory boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, shadows = ShadowMoonBridge.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GameContinuityLifecycleTest {
    public static class TestGame extends Game {
        int resolveCount, recreateCount, finishCount, rebuildCount;
        long resolutionGeneration;
        NvConnection nextConnection;
        MediaCodecDecoderRenderer nextDecoder;
        StreamConfiguration nextConfiguration;
        @Override public void finish() { finishCount++; }
        @Override public boolean isFinishing() { return false; }
        @Override public void recreate() { recreateCount++; }
        @Override public void setMetaKeyCaptureState(boolean enabled) { }
        @Override protected void resolveReconnectTarget(long generation) {
            resolveCount++;
            resolutionGeneration = generation;
        }
        @Override protected NvConnection createReconnectConnection(ReconnectHostResolver.Result target,
                                                                   StreamConfiguration config) {
            nextConfiguration = config;
            return nextConnection;
        }
        @Override protected MediaCodecDecoderRenderer createReconnectDecoder() { return nextDecoder; }
        @Override protected void rebuildReconnectBindings() { rebuildCount++; }
    }

    private TestGame game;
    private NvConnection original, replacement;
    private ResumableVideoDecoderRenderer oldVideo;
    private AndroidAudioRenderer oldAudio;
    private MicrophoneCaptureManager microphone;
    private ControllerHandler input;
    private InputCaptureProvider capture;
    private StreamContainer container;
    private StreamContinuityOverlay continuity;
    private StreamReconnectController reconnect;
    private ExecutorService worker;

    @Before public void setUp() {
        game = Robolectric.buildActivity(TestGame.class).get();
        try {
            java.lang.invoke.MethodHandles.privateLookupIn(Game.class, java.lang.invoke.MethodHandles.lookup())
                    .findSpecial(androidx.appcompat.app.AppCompatActivity.class, "onCreate",
                            java.lang.invoke.MethodType.methodType(void.class, android.os.Bundle.class), Game.class)
                    .bindTo(game).invokeWithArguments((Object) null);
        } catch (Throwable failure) { throw new AssertionError(failure); }
        original = mock(NvConnection.class);
        replacement = mock(NvConnection.class);
        oldVideo = mock(ResumableVideoDecoderRenderer.class);
        oldAudio = mock(AndroidAudioRenderer.class);
        microphone = mock(MicrophoneCaptureManager.class);
        input = mock(ControllerHandler.class);
        capture = mock(InputCaptureProvider.class);
        container = mock(StreamContainer.class);
        continuity = mock(StreamContinuityOverlay.class);
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        when(container.getSurface()).thenReturn(surface);
        when(container.getSurfaceView()).thenReturn(mock(SurfaceView.class));
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(container).runAfterNextPresentedFrame(any());
        doAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; })
                .when(continuity).captureAndHold(any(), any());
        game.conn = original;
        game.connected = true;
        game.nextConnection = replacement;
        game.nextDecoder = mock(MediaCodecDecoderRenderer.class);
        game.setIntent(new Intent().putExtra(Game.EXTRA_PC_UUID, "original-paired-pc"));
        PreferenceConfiguration preferences = new PreferenceConfiguration();
        preferences.enableMicrophone = false;
        preferences.enableCommitText = true;
        preferences.fps = 60;
        ReflectionHelpers.setField(game, "prefConfig", preferences);
        ReflectionHelpers.setField(game, "sessionConfiguration", new StreamConfiguration.Builder().build());
        ReflectionHelpers.setField(game, "streamRefreshRate", 60f);
        ReflectionHelpers.setField(game, "controllerHandler", input);
        ReflectionHelpers.setField(game, "inputCaptureProvider", capture);
        ReflectionHelpers.setField(game, "sessionVideoRenderer", oldVideo);
        ReflectionHelpers.setField(game, "sessionAudioRenderer", oldAudio);
        ReflectionHelpers.setField(game, "decoderRenderer", mock(MediaCodecDecoderRenderer.class));
        ReflectionHelpers.setField(game, "microphoneCaptureManager", microphone);
        ReflectionHelpers.setField(game, "streamContainer", container);
        ReflectionHelpers.setField(game, "continuityOverlay", continuity);
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(game, "tombstonePrefs", game.getSharedPreferences("continuity", Context.MODE_PRIVATE));
        ReflectionHelpers.setField(game, "activityStarted", true);
        ReflectionHelpers.setField(game, "activityResumed", true);
        ReflectionHelpers.setField(game, "autoReconnectArmed", true);
        ReflectionHelpers.setField(game, "transportStarted", true);
        ReflectionHelpers.callInstanceMethod(game, "initializeReconnectController");
        reconnect = ReflectionHelpers.getField(game, "reconnectController");
        worker = ReflectionHelpers.getField(game, "microphoneExecutor");
    }

    @After public void tearDown() throws Exception {
        reconnect.close();
        ((Handler) ReflectionHelpers.getField(game, "timerHandler")).removeCallbacksAndMessages(null);
        ((Handler) ReflectionHelpers.getField(game, "commitTextHandler")).removeCallbacksAndMessages(null);
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        Game.instance = null;
    }

    @Test public void captureCompletesBeforeVideoIsDisposedAndActivityRemainsAlive() throws Exception {
        AtomicReference<Runnable> captureDone = new AtomicReference<>();
        doAnswer(call -> { captureDone.set(call.getArgument(1)); return null; })
                .when(continuity).captureAndHold(any(), any());
        game.connectionTerminated(-1);
        idle();
        assertTrue(reconnect.isRecovering());
        assertFalse(game.connected);
        verify(oldAudio).setSuspended(true);
        verify(microphone).setCaptureAllowed(false);
        verify(oldVideo, never()).setForeground(false);
        verify(original, never()).stopForReconnect();
        captureDone.get().run();
        drain();
        verify(oldVideo).setForeground(false);
        verify(original).stopForReconnect();
        verify(original, never()).stop();
        assertEquals(0, game.finishCount);
        assertEquals(0, game.recreateCount);
    }

    @Test public void resolvedTargetReplacesOnlyTransportAndRetainsActivitySurfaceAndServiceToken() throws Exception {
        ReflectionHelpers.setField(game, "sessionServiceToken", "same-session-token");
        NvConnectionListener callback = resolveAndInstall();
        assertNotNull(callback);
        assertSame(replacement, game.conn);
        assertSame(container, ReflectionHelpers.getField(game, "streamContainer"));
        assertSame(continuity, ReflectionHelpers.getField(game, "continuityOverlay"));
        assertEquals("same-session-token", ReflectionHelpers.getField(game, "sessionServiceToken"));
        assertEquals(1, game.rebuildCount);
        assertEquals(0, game.recreateCount);
        assertEquals(0, game.finishCount);
        assertTrue(reconnect.isRecovering());
        assertFalse(game.connected);
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "awaitingFreshFrame"));
        verify(continuity, never()).release();
        assertEquals(StreamConfiguration.STREAM_CFG_AUTO, game.nextConfiguration.getRemote());
        assertEquals(60, game.nextConfiguration.getRefreshRate());
    }

    @Test public void handshakeSuccessKeepsFrameAndMediaGatedUntilPresentedFrame() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        clearInvocations(input, capture, microphone, continuity);
        callback.connectionStarted();
        idle();
        assertTrue(reconnect.isRecovering());
        assertFalse(game.connected);
        verify(continuity, never()).release();
        verify(input, never()).setSuspended(false);
        verify(capture, never()).enableCapture();
        verify(microphone, never()).setCaptureAllowed(true);
        presentCurrentFrame();
        assertTrue(game.connected);
        assertFalse(reconnect.isRecovering());
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "awaitingFreshFrame"));
        verify(continuity).release();
        verify(input).setSuspended(false);
    }

    @Test public void replacementDecoderFailureKeepsOrdinaryStopInsteadOfAssumingItsRouteWasLost() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        assertFalse(callback.stageFailed("Video decoder initialization", 0, -5));
        idle();
        drain();
        verify(replacement).stop();
        verify(replacement, never()).stopForReconnect();
        assertFalse(game.connected);
        assertTrue(reconnect.isRecovering());
        verify(continuity, never()).release();
    }

    @Test public void replacementTransportPortFailureUsesFastStopWithoutUnfreezingOrRebuildingTheActivity() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        assertFalse(callback.stageFailed("RTSP handshake", MoonBridge.ML_PORT_FLAG_TCP_47984, -1));
        idle();
        drain();
        verify(replacement).stopForReconnect();
        verify(replacement, never()).stop();
        assertFalse(game.connected);
        assertTrue(reconnect.isRecovering());
        assertEquals(0, game.recreateCount);
        verify(continuity, never()).release();
    }

    @Test public void presentationBeforeHandshakeIsRememberedButCannotUnfreezeAlone() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        presentCurrentFrame();
        assertFalse(game.connected);
        assertTrue(reconnect.isRecovering());
        verify(continuity, never()).release();
        callback.connectionStarted();
        idle();
        assertTrue(game.connected);
        assertFalse(reconnect.isRecovering());
        verify(continuity).release();
    }

    @Test public void staleQueuedCallbacksCannotStartOrTerminateTheReplacementTransport() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        callback.connectionStarted();
        callback.connectionTerminated(-1);
        idle();
        drain();
        long retiredGeneration = ReflectionHelpers.getField(game, "connectionGeneration");
        clearInvocations(input, capture, microphone, continuity);
        callback.connectionStarted();
        callback.rumble((short) 0, (short) 1, (short) 1);
        callback.connectionTerminated(-1);
        idle();
        assertEquals(retiredGeneration, (long) ReflectionHelpers.getField(game, "connectionGeneration"));
        assertFalse(game.connected);
        assertTrue(reconnect.isRecovering());
        verify(continuity, never()).release();
        verify(input, never()).setSuspended(false);
        advance(2000);
        assertEquals(2, game.resolveCount);
    }

    @Test public void frozenInputIsConsumedWithoutSendingOrBufferingItForRecovery() throws Exception {
        game.connectionTerminated(-1);
        idle();
        drain();
        clearInvocations(original, input);
        assertTrue(game.handleKeyDown(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)));
        assertTrue(game.handleKeyUp(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)));
        assertFalse(game.handleKeyUp(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
        assertTrue(game.handleCommitText("must-not-be-queued"));
        assertTrue(game.handleDeleteSurroundingText(4, 0));
        game.sendKeys(new short[]{0x41});
        MotionEvent touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 30, 40, 0);
        try { assertTrue(game.handleMotionEvent(container, touch)); }
        finally { touch.recycle(); }
        advance(200);
        Queue<?> pendingText = ReflectionHelpers.getField(game, "commitTextQueue");
        assertTrue(pendingText.isEmpty());
        verifyNoInteractions(original, input);
    }

    @Test public void hiddenReplacementCannotReleaseCoverAndRetriesOnlyWhenVisible() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        game.onStop();
        callback.connectionStarted();
        idle();
        presentCurrentFrame();
        assertFalse(game.connected);
        verify(continuity, never()).release();
        advance(20000);
        assertEquals(1, game.resolveCount);
        game.onStart();
        presentCurrentFrame();
        assertTrue(game.connected);
        verify(continuity).release();
    }

    @Test public void explicitStopInvalidatesAlreadyQueuedPresentationAndHandshake() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        callback.connectionStarted();
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        clearInvocations(continuity, input, capture);
        idle();
        presentCurrentFrame();
        assertFalse(game.connected);
        assertFalse(reconnect.isRecovering());
        verifyNoInteractions(continuity, input, capture);
    }

    @Test public void missingPresentedFrameSettlesTheAttemptAndRetriesInsteadOfRemainingStuck() throws Exception {
        NvConnectionListener callback = resolveAndInstall();
        callback.connectionStarted();
        idle();
        advance(15000);
        drain();
        assertFalse(game.connected);
        verify(replacement).stopForReconnect();
        advance(2000);
        assertEquals(2, game.resolveCount);
        assertTrue(reconnect.isRecovering());
        verify(continuity, never()).release();
    }

    @Test public void backgroundCaptureRejectsOldPresentationCallbackAfterVisibilityGenerationChanges() {
        // Install a real wrapper only to capture Game's generation-bound callback registration.
        ResumableVideoDecoderRenderer wrapper = mock(ResumableVideoDecoderRenderer.class);
        ReflectionHelpers.setField(game, "sessionVideoRenderer", wrapper);
        ReflectionHelpers.callInstanceMethod(game, "bindPresentationCallback",
                ReflectionHelpers.ClassParameter.from(long.class, 0L));
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(wrapper).setOnFirstFramePresented(callbacks.capture());
        Runnable oldPresentation = callbacks.getValue();
        game.onPause();
        oldPresentation.run();
        idle();
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "awaitingFreshFrame"));
        verify(continuity, never()).release();
        verify(container).cancelPendingFramePresentation();
    }

    private NvConnectionListener resolveAndInstall() throws Exception {
        game.connectionTerminated(-1);
        idle();
        drain();
        advance(1000);
        ReflectionHelpers.callInstanceMethod(game, "completeReconnectResolution",
                ReflectionHelpers.ClassParameter.from(long.class, game.resolutionGeneration),
                ReflectionHelpers.ClassParameter.from(ReconnectHostResolver.Result.class,
                        new ReconnectHostResolver.Result(new ComputerDetails.AddressTuple("192.0.2.1", 47989), 47984)));
        ArgumentCaptor<NvConnectionListener> callback = ArgumentCaptor.forClass(NvConnectionListener.class);
        verify(replacement).start(any(), any(), callback.capture());
        return callback.getValue();
    }

    private void presentCurrentFrame() {
        long generation = ReflectionHelpers.getField(game, "connectionGeneration");
        ReflectionHelpers.setField(game, "freshFramePresented", true);
        ReflectionHelpers.callInstanceMethod(game, "completePresentedConnection",
                ReflectionHelpers.ClassParameter.from(long.class, generation));
        idle();
    }

    private void drain() throws Exception { worker.submit(() -> { }).get(3, TimeUnit.SECONDS); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void advance(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
}
