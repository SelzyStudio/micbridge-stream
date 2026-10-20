package com.limelight.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.Game;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, manifest = Config.NONE, application = Application.class)
public class StreamContainerLifecycleTest {
    private StreamContainer container;
    private Game game;
    private SurfaceHolder holder;
    private Surface surface;

    @Before
    public void setUp() {
        game = mock(Game.class);
        holder = mock(SurfaceHolder.class);
        surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        when(holder.getSurface()).thenReturn(surface);
        container = new StreamContainer(ApplicationProvider.getApplicationContext(), null);
        PreferenceConfiguration preferences = new PreferenceConfiguration();
        preferences.renderMode = 0;
        container.init(game, preferences);
        // Robolectric versions differ in whether a newly constructed SurfaceView is ready.
        container.surfaceDestroyed(container.getSurfaceView().getHolder());
        clearInvocations(game);
    }

    @Test
    public void surfaceRecreationNotifiesAgainAndDoesNotReuseDestroyedSurface() {
        AtomicInteger ready = new AtomicInteger();
        container.setOnSurfaceAvailable(ready::incrementAndGet);
        container.surfaceCreated(holder);
        container.surfaceChanged(holder, 0, 1920, 1080);
        assertSame(surface, container.getSurface());
        assertEquals(1, ready.get());
        container.surfaceDestroyed(holder);
        assertNull(container.getSurface());
        container.setOnSurfaceAvailable(ready::incrementAndGet);
        assertEquals(1, ready.get());
        Surface replacement = mock(Surface.class);
        when(holder.getSurface()).thenReturn(replacement);
        container.surfaceCreated(holder);
        container.surfaceChanged(holder, 0, 1920, 1080);
        assertSame(replacement, container.getSurface());
        assertEquals(2, ready.get());
    }

    @Test
    public void gameStopsDecoderBeforeContainerDropsCurrentSurface() {
        container.surfaceCreated(holder);
        container.surfaceChanged(holder, 0, 1920, 1080);
        doAnswer(invocation -> {
            assertSame(surface, container.getSurface());
            return null;
        }).when(game).surfaceDestroyed(holder);
        container.surfaceDestroyed(holder);
        verify(game).surfaceDestroyed(holder);
        assertNull(container.getSurface());
    }

    @Test
    public void continuityWaitsForFinalGlDrawOnlyForStereoOutput() {
        AtomicInteger presented = new AtomicInteger();
        container.runAfterNextPresentedFrame(presented::incrementAndGet);
        assertEquals(1, presented.get());
        FramePresentation stereo = mock(FramePresentation.class);
        ReflectionHelpers.setField(container, "framePresentation", stereo);
        Runnable callback = presented::incrementAndGet;
        container.runAfterNextPresentedFrame(callback);
        verify(stereo).runAfterNextVideoFrameDraw(callback);
        assertEquals(1, presented.get());
        container.cancelPendingFramePresentation();
        verify(stereo).cancelPendingFramePresentation();
        container.onDestroy();
        clearInvocations(stereo);
        container.runAfterNextPresentedFrame(callback);
        verifyNoInteractions(stereo);
    }

    @Test
    public void activityDestructionClearsCallbacksAndIgnoresLateSurfaceEvents() {
        AtomicInteger ready = new AtomicInteger();
        container.setOnSurfaceAvailable(ready::incrementAndGet);
        container.surfaceCreated(holder);
        container.surfaceChanged(holder, 0, 1920, 1080);
        container.onDestroy();
        assertNull(container.getSurface());
        clearInvocations(game);
        container.surfaceCreated(holder);
        container.surfaceChanged(holder, 0, 1920, 1080);
        container.surfaceDestroyed(holder);
        container.setOnSurfaceAvailable(ready::incrementAndGet);
        container.onStereo3DSurfaceReady(surface);
        container.onDestroy();
        assertEquals(1, ready.get());
        assertNull(container.getSurface());
        verifyNoInteractions(game);
    }
}
