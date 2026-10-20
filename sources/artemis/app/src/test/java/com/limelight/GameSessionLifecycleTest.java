package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, shadows = ShadowMoonBridge.class)
public class GameSessionLifecycleTest {
    public static class TestGame extends Game {
        int finishCount;
        boolean finishing;
        @Override public void finish() { finishCount++; }
        @Override public boolean isFinishing() { return finishing; }
    }

    private TestGame game;
    private NvConnection connection;
    private com.limelight.binding.video.ResumableVideoDecoderRenderer renderer;
    private com.limelight.binding.audio.AndroidAudioRenderer audio;

    @Before public void setUp() {
        game = Robolectric.buildActivity(TestGame.class).get();
        try {
            java.lang.invoke.MethodHandles.privateLookupIn(Game.class, java.lang.invoke.MethodHandles.lookup())
                    .findSpecial(androidx.appcompat.app.AppCompatActivity.class, "onCreate",
                            java.lang.invoke.MethodType.methodType(void.class, android.os.Bundle.class), Game.class)
                    .bindTo(game).invokeWithArguments((Object) null);
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
        connection = mock(NvConnection.class);
        game.conn = connection;
        game.connected = true;
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "decoderRenderer", mock(MediaCodecDecoderRenderer.class));
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        ReflectionHelpers.setField(game, "inputCaptureProvider", mock(InputCaptureProvider.class));
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(game, "tombstonePrefs", game.getSharedPreferences("lifecycle", Context.MODE_PRIVATE));
        renderer = mock(com.limelight.binding.video.ResumableVideoDecoderRenderer.class);
        audio = mock(com.limelight.binding.audio.AndroidAudioRenderer.class);
        ReflectionHelpers.setField(game, "sessionVideoRenderer", renderer);
        ReflectionHelpers.setField(game, "sessionAudioRenderer", audio);
    }

    @After public void tearDown() {
        java.util.concurrent.ExecutorService executor = ReflectionHelpers.getField(game, "microphoneExecutor");
        executor.shutdownNow();
        Game.instance = null;
    }

    @Test public void hiddenActivityKeepsSameConnectionAndDoesNotFinish() {
        game.onStop();
        assertTrue("Going home must retain the connected session", game.connected);
        assertEquals("Going home must retain the stream Activity", 0, game.finishCount);
        verify(connection, never()).stop();
        verify(connection).setStreamBackground(true);
        verify(renderer).setForeground(false);
        verify(audio).setSuspended(true);
    }

    @Test public void returnRestoresMediaWithoutLaunchingOrStoppingTheConnection() {
        game.onStop();
        clearInvocations(connection, renderer, audio);
        game.onStart();
        assertTrue(game.connected);
        assertEquals(0, game.finishCount);
        verify(renderer).setForeground(true);
        verify(audio).setSuspended(false);
        verify(connection).setStreamBackground(false);
        verify(connection, never()).stop();
        verify(connection, never()).start(any(), any(), any());
    }

    @Test public void surfaceLossPausesVideoWithoutEndingTheSession() {
        ReflectionHelpers.setField(game, "activityStarted", true);
        ReflectionHelpers.setField(game, "surfaceCreated", true);
        ReflectionHelpers.setField(game, "attemptedConnection", true);
        when(renderer.isSuspended()).thenReturn(true);
        game.surfaceDestroyed(null);
        assertTrue(game.connected);
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "surfaceCreated"));
        verify(renderer).setRenderTarget(null);
        verify(connection).setStreamBackground(true);
        verify(connection, never()).stop();
    }

    @Test public void pausingImmediatelyGatesMicrophoneAndReleasesInput() {
        com.limelight.binding.audio.MicrophoneCaptureManager mic =
                mock(com.limelight.binding.audio.MicrophoneCaptureManager.class);
        ReflectionHelpers.setField(game, "microphoneCaptureManager", mic);
        ReflectionHelpers.setField(game, "activityResumed", true);
        game.onPause();
        verify(mic).setCaptureAllowed(false);
        verify(mic, timeout(1000)).stop();
        assertFalse(ReflectionHelpers.<Boolean>getField(game, "activityResumed"));
        verify((ControllerHandler) ReflectionHelpers.getField(game, "controllerHandler")).setSuspended(true);
        verify((InputCaptureProvider) ReflectionHelpers.getField(game, "inputCaptureProvider")).disableCapture();
        assertTrue(game.connected);
    }

    @Test public void lateConnectionCompletionCannotReviveClosedSession() {
        game.connected = false;
        ReflectionHelpers.setField(game, "sessionClosing", true);
        game.connectionStarted();
        assertFalse(game.connected);
        verifyNoInteractions(connection, renderer, audio);
    }

    @Test public void appReturnIntentExistsOnlyWhileItsOriginalSessionIsAlive() {
        Game.instance = game;
        ReflectionHelpers.setField(game, "sessionServiceToken", "same-session");
        assertEquals("same-session", Game.getActiveSessionIntent().getStringExtra(
                StreamSessionService.EXTRA_SESSION_TOKEN));
        ReflectionHelpers.setField(game, "sessionClosing", true);
        assertNull(Game.getActiveSessionIntent());
    }

    @Test public void explicitExitStopsConnectionAndNeverBecomesABackgroundSession() {
        game.finishing = true;
        game.onStop();
        assertFalse(game.connected);
        assertTrue(ReflectionHelpers.<Boolean>getField(game, "sessionClosing"));
        verify(connection, timeout(1000)).stop();
        verify(connection, never()).setStreamBackground(true);
    }

    @Test public void restoredActivityAfterProcessDeathReturnsToComputerList() {
        TestGame restored = Robolectric.buildActivity(TestGame.class)
                .create(new android.os.Bundle()).get();
        assertEquals(1, restored.finishCount);
        assertNull(restored.conn);
        assertEquals(PcView.class.getName(), org.robolectric.Shadows.shadowOf(restored)
                .getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void staleNotificationCannotCreateAReplacementConnection() {
        android.content.Intent notification = new android.content.Intent(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(), TestGame.class)
                .setAction(StreamSessionService.ACTION_RETURN_TO_SESSION);
        TestGame restored = Robolectric.buildActivity(TestGame.class, notification).create().get();
        assertEquals(1, restored.finishCount);
        assertNull(restored.conn);
        assertEquals(PcView.class.getName(), org.robolectric.Shadows.shadowOf(restored)
                .getNextStartedActivity().getComponent().getClassName());
    }
}
