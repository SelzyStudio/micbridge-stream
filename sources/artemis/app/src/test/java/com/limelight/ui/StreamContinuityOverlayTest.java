package com.limelight.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 35}, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StreamContinuityOverlayTest {
    private FrameLayout container;
    private SurfaceView source;
    private CopyRecorder copy;
    private StreamContinuityOverlay overlay;

    private static class CopyRecorder implements StreamContinuityOverlay.FrameCopier {
        StreamContinuityOverlay.CopyCompletion listener;
        Bitmap frame;
        int requests;
        @Override public void request(SurfaceView source, Bitmap frame,
                                      StreamContinuityOverlay.CopyCompletion listener, Handler handler) {
            requests++;
            this.frame = frame;
            this.listener = listener;
        }
    }

    @Before public void setup() {
        container = new FrameLayout(ApplicationProvider.getApplicationContext());
        source = mock(SurfaceView.class);
        SurfaceHolder holder = mock(SurfaceHolder.class);
        Surface surface = mock(Surface.class);
        when(source.getHolder()).thenReturn(holder);
        when(holder.getSurface()).thenReturn(surface);
        when(surface.isValid()).thenReturn(true);
        when(source.getWidth()).thenReturn(3840);
        when(source.getHeight()).thenReturn(2160);
        when(source.getScaleX()).thenReturn(2f);
        when(source.getScaleY()).thenReturn(2f);
        when(source.getTranslationX()).thenReturn(-100f);
        when(source.getTranslationY()).thenReturn(-200f);
        copy = new CopyRecorder();
        overlay = new StreamContinuityOverlay(container.getContext(), container, copy);
    }

    @Test public void successfulCopyRetainsOneBoundedFrameAndPanZoomGeometry() {
        AtomicInteger completion = new AtomicInteger();
        overlay.captureAndHold(source, completion::incrementAndGet);
        assertEquals(0, completion.get());
        assertTrue(overlay.isAwaitingFreshFrame());
        assertTrue((long) copy.frame.getWidth() * copy.frame.getHeight() <= StreamContinuityOverlay.MAX_PIXELS);
        copy.listener.onPixelCopyFinished(PixelCopy.SUCCESS);
        assertEquals(1, completion.get());
        assertTrue(overlay.isHoldingFrame());
        ImageView image = (ImageView) container.getChildAt(0);
        assertEquals(3840, image.getLayoutParams().width);
        assertEquals(2160, image.getLayoutParams().height);
        assertEquals(2f, image.getScaleX(), 0);
        assertEquals(-100f, image.getTranslationX(), 0);
        assertEquals(-200f, image.getTranslationY(), 0);
        assertFalse(image.isClickable());
        overlay.release();
        assertFalse(overlay.isAwaitingFreshFrame());
        assertFalse(overlay.isHoldingFrame());
        assertNull(image.getDrawable());
    }

    @Test public void timeoutUnblocksTeardownAndLateCopyCannotResurrectCover() {
        AtomicInteger completion = new AtomicInteger();
        overlay.captureAndHold(source, completion::incrementAndGet);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(1, completion.get());
        copy.listener.onPixelCopyFinished(PixelCopy.SUCCESS);
        assertFalse(overlay.isHoldingFrame());
        assertTrue(overlay.isAwaitingFreshFrame());
        assertEquals(1, completion.get());
    }

    @Test public void releaseDuringCopyCancelsCoverButStillCompletesTeardownExactlyOnce() {
        AtomicInteger completion = new AtomicInteger();
        overlay.captureAndHold(source, completion::incrementAndGet);
        overlay.release();
        copy.listener.onPixelCopyFinished(PixelCopy.SUCCESS);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(1, completion.get());
        assertFalse(overlay.isHoldingFrame());
        assertFalse(overlay.isAwaitingFreshFrame());
    }

    @Test public void failedOrMissingSourceStillGatesInputUntilFreshFrame() {
        AtomicInteger completion = new AtomicInteger();
        overlay.captureAndHold(source, completion::incrementAndGet);
        copy.listener.onPixelCopyFinished(PixelCopy.ERROR_SOURCE_NO_DATA);
        assertEquals(1, completion.get());
        assertFalse(overlay.isHoldingFrame());
        assertTrue(overlay.isAwaitingFreshFrame());
        overlay.release();
        overlay.captureAndHold(null, completion::incrementAndGet);
        assertEquals(2, completion.get());
        assertTrue(overlay.isAwaitingFreshFrame());
    }

    @Test public void heldFrameIsNotReplacedWithBlankAndPendingCopyIsShared() {
        AtomicInteger completion = new AtomicInteger();
        overlay.captureAndHold(source, completion::incrementAndGet);
        overlay.captureAndHold(source, completion::incrementAndGet);
        assertEquals(1, copy.requests);
        copy.listener.onPixelCopyFinished(PixelCopy.SUCCESS);
        assertEquals(2, completion.get());
        overlay.captureAndHold(source, completion::incrementAndGet);
        assertEquals(1, copy.requests);
        assertEquals(3, completion.get());
        assertTrue(overlay.isHoldingFrame());
    }

    @Test public void unavailableSurfaceAndCopyExceptionNeverDelayStop() {
        AtomicInteger completion = new AtomicInteger();
        when(source.getWidth()).thenReturn(0);
        overlay.captureAndHold(source, completion::incrementAndGet);
        assertEquals(1, completion.get());
        assertEquals(0, copy.requests);
        when(source.getWidth()).thenReturn(1920);
        StreamContinuityOverlay failing = new StreamContinuityOverlay(container.getContext(), container,
                (view, bitmap, listener, handler) -> { throw new IllegalArgumentException("Surface gone"); });
        failing.captureAndHold(source, completion::incrementAndGet);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(2, completion.get());
    }
}
