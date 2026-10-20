package com.limelight.binding.video;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FirstFramePresentationTest {
    private MediaCodecDecoderRenderer.FirstFramePresentation presentation;
    private AtomicInteger callbacks;

    @Before public void setUp() {
        presentation = new MediaCodecDecoderRenderer.FirstFramePresentation(new Handler(Looper.getMainLooper()));
        callbacks = new AtomicInteger();
        presentation.setCallback(callbacks::incrementAndGet);
    }

    @Test public void sourceConfigurationDoesNotPretendAFrameWasPresented() {
        presentation.bindSource(new Object());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
    }

    @Test public void firstActualPresentationIsDispatchedOnceOnMainLooper() {
        Object codec = new Object();
        long generation = presentation.bindSource(codec);
        presentation.onPresented(codec, generation);
        presentation.onPresented(codec, generation);
        assertEquals(0, callbacks.get());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, callbacks.get());
        presentation.onPresented(codec, generation);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, callbacks.get());
    }

    @Test public void queuedOldCodecCallbackCannotUncoverNewCodec() {
        Object previous = new Object();
        long oldGeneration = presentation.bindSource(previous);
        presentation.onPresented(previous, oldGeneration);
        Object replacement = new Object();
        long nextGeneration = presentation.bindSource(replacement);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
        presentation.onPresented(previous, oldGeneration);
        presentation.onPresented(replacement, nextGeneration);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, callbacks.get());
    }

    @Test public void reuseOfSameCodecObjectStillRequiresCurrentConfigurationGeneration() {
        Object codec = new Object();
        long oldGeneration = presentation.bindSource(codec);
        long newGeneration = presentation.bindSource(codec);
        presentation.onPresented(codec, oldGeneration);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
        presentation.onPresented(codec, newGeneration);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, callbacks.get());
    }

    @Test public void stopInvalidatesAlreadyQueuedPresentation() {
        Object codec = new Object();
        long generation = presentation.bindSource(codec);
        presentation.onPresented(codec, generation);
        presentation.clearSource();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
    }

    @Test public void replacingCallbackCannotRunItsQueuedPredecessor() {
        Object codec = new Object();
        long generation = presentation.bindSource(codec);
        presentation.onPresented(codec, generation);
        AtomicInteger replacement = new AtomicInteger();
        presentation.setCallback(replacement::incrementAndGet);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
        assertEquals(1, replacement.get());
    }

    @Test public void listenerInstalledAfterActualFrameStillReceivesPresentation() {
        presentation.setCallback(null);
        Object codec = new Object();
        long generation = presentation.bindSource(codec);
        presentation.onPresented(codec, generation);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
        presentation.setCallback(callbacks::incrementAndGet);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, callbacks.get());
    }

    @Test public void legacySuccessfulReleaseIsBestEffortAndDelayedRatherThanImmediate() {
        Object codec = new Object();
        long generation = presentation.bindSource(codec);
        presentation.onLegacySurfaceReleased(codec, generation);
        presentation.onLegacySurfaceReleased(codec, generation);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(33));
        assertEquals(0, callbacks.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals(1, callbacks.get());
    }

    @Test public void stoppedLegacyReleaseCannotUncoverLaterSurface() {
        Object codec = new Object();
        long generation = presentation.bindSource(codec);
        presentation.onLegacySurfaceReleased(codec, generation);
        presentation.clearSource();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40));
        assertEquals(0, callbacks.get());
    }
}
