package com.limelight.binding.audio;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowMoonBridge.class)
public class AndroidAudioRendererSuspensionTest {
    private Application application;
    private AudioTrack track;

    @Before public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        track = mock(AudioTrack.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when(track.write(any(short[].class), anyInt(), anyInt(), eq(AudioTrack.WRITE_NON_BLOCKING)))
                    .thenAnswer(call -> call.getArgument(2));
        }
    }

    @Test public void suspensionBeforeSetupPreventsPlaybackAndDropsPcmUntilResume() {
        TestRenderer renderer = new TestRenderer(application, track, false);
        renderer.setSuspended(true);
        assertEquals(0, setup(renderer));
        renderer.playDecodedAudio(new short[] {1, 2});
        verify(track, never()).play();
        verify(track, never()).write(any(short[].class), anyInt(), anyInt(), anyInt());
        renderer.setSuspended(false);
        renderer.playDecodedAudio(new short[] {3, 4});
        verify(track).play();
        verify(track).write(new short[] {3, 4}, 0, 2, AudioTrack.WRITE_NON_BLOCKING);
        renderer.cleanup();
    }

    @Test public void liveSuspensionPausesFlushesAndNeverUsesVolumeOrBlockingWrites() {
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        clearInvocations(track);
        renderer.setSuspended(true);
        renderer.setSuspended(true);
        renderer.playDecodedAudio(new short[] {1, 2});
        InOrder order = inOrder(track);
        order.verify(track).pause();
        order.verify(track).flush();
        verify(track, never()).setVolume(anyFloat());
        verify(track, never()).write(any(short[].class), anyInt(), anyInt());
        verify(track, never()).write(any(short[].class), anyInt(), anyInt(), anyInt());
        renderer.setSuspended(false);
        renderer.playDecodedAudio(new short[] {5, 6});
        verify(track).play();
        verify(track).write(new short[] {5, 6}, 0, 2, AudioTrack.WRITE_NON_BLOCKING);
        renderer.cleanup();
    }

    @Test public void cleanupIsTerminalIdempotentAndSafeBeforeSetup() {
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        clearInvocations(track);
        renderer.cleanup();
        renderer.cleanup();
        renderer.setSuspended(false);
        renderer.playDecodedAudio(new short[] {1});
        renderer.start();
        assertEquals(-2, setup(renderer));
        InOrder order = inOrder(track);
        order.verify(track).pause();
        order.verify(track).flush();
        order.verify(track).release();
        verifyNoMoreInteractions(track);

        TestRenderer neverSetup = new TestRenderer(application, mock(AudioTrack.class), false);
        neverSetup.cleanup();
        neverSetup.cleanup();
        neverSetup.stop();
    }

    @Test public void cleanupDuringDelayedSetupReleasesCandidateWithoutStartingIt() throws Exception {
        CreationGate renderer = new CreationGate(application, track);
        AtomicInteger result = new AtomicInteger(123);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = setupThread(renderer, result, failure);
        try {
            assertTrue(renderer.entered.await(2, TimeUnit.SECONDS));
            renderer.cleanup();
        } finally {
            renderer.proceed.countDown();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(-2, result.get());
        verify(track).release();
        verify(track, never()).play();
    }

    @Test public void suspensionDuringDelayedSetupIsAppliedBeforeAnyPlay() throws Exception {
        CreationGate renderer = new CreationGate(application, track);
        AtomicInteger result = new AtomicInteger(123);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = setupThread(renderer, result, failure);
        try {
            assertTrue(renderer.entered.await(2, TimeUnit.SECONDS));
            renderer.setSuspended(true);
        } finally {
            renderer.proceed.countDown();
            worker.join(2000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(0, result.get());
        verify(track, never()).play();
        renderer.setSuspended(false);
        verify(track).play();
        renderer.cleanup();
    }

    @Test public void suspensionDiscardsUnwrittenRemainderInsteadOfReplayingIt() {
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenReturn(1, 2);
        renderer.playDecodedAudio(new short[] {10, 20, 30, 40});
        renderer.setSuspended(true);
        renderer.playDecodedAudio(new short[] {50, 60});
        renderer.setSuspended(false);
        renderer.playDecodedAudio(new short[] {70, 80});
        ArgumentCaptor<short[]> data = ArgumentCaptor.forClass(short[].class);
        verify(track, times(2)).write(data.capture(), eq(0), anyInt(), eq(AudioTrack.WRITE_NON_BLOCKING));
        assertArrayEquals(new short[] {10, 20, 30, 40}, data.getAllValues().get(0));
        assertArrayEquals(new short[] {70, 80}, data.getAllValues().get(1));
        renderer.cleanup();
    }

    @Test public void partialDeviceWritePreservesPcmOrderAcrossCallbacks() {
        List<Short> accepted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(track.write(any(short[].class), anyInt(), anyInt(), eq(AudioTrack.WRITE_NON_BLOCKING)))
                .thenAnswer(call -> {
                    short[] pcm = call.getArgument(0);
                    int offset = call.getArgument(1), count = call.getArgument(2);
                    int amount = calls.getAndIncrement() == 0 ? 2 : count;
                    for (int i = 0; i < amount; i++) accepted.add(pcm[offset + i]);
                    return amount;
                });
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        renderer.playDecodedAudio(new short[] {1, 2, 3, 4});
        renderer.playDecodedAudio(new short[] {5, 6, 7, 8});
        assertEquals(java.util.Arrays.asList((short)1,(short)2,(short)3,(short)4,
                (short)5,(short)6,(short)7,(short)8), accepted);
        renderer.cleanup();
    }

    @Test public void fullDeviceBufferDoesNotDiscardPacketsOrBlockTheCallback() {
        List<Short> accepted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(track.write(any(short[].class), anyInt(), anyInt(), eq(AudioTrack.WRITE_NON_BLOCKING)))
                .thenAnswer(call -> {
                    if (calls.getAndIncrement() < 2) return 0;
                    short[] pcm = call.getArgument(0);
                    int offset = call.getArgument(1), count = call.getArgument(2);
                    for (int i = 0; i < count; i++) accepted.add(pcm[offset + i]);
                    return count;
                });
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        renderer.playDecodedAudio(new short[] {1, 2});
        renderer.playDecodedAudio(new short[] {3, 4});
        renderer.playDecodedAudio(new short[] {5, 6});
        assertEquals(java.util.Arrays.asList((short)1,(short)2,(short)3,(short)4,(short)5,(short)6), accepted);
        verify(track, never()).write(any(short[].class), anyInt(), anyInt());
        renderer.cleanup();
    }

    @Test public void playbackBufferCoversFortyMillisecondsAndWholeOpusPackets() {
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        assertTrue(renderer.lastBufferSize >= 48000 * 2 * 2 * 40 / 1000);
        assertEquals(0, renderer.lastBufferSize % (240 * 2 * 2));
        renderer.cleanup();
    }

    @Test @Config(sdk = 23)
    public void legacyPartialByteWritesPreserveSamplesAcrossCallbacks() {
        List<Short> accepted = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(track.write(any(ByteBuffer.class), anyInt(), eq(AudioTrack.WRITE_NON_BLOCKING)))
                .thenAnswer(call -> {
                    ByteBuffer bytes = ((ByteBuffer) call.getArgument(0)).duplicate().order(ByteOrder.nativeOrder());
                    int wanted = call.getArgument(1);
                    int written = calls.getAndIncrement() == 0 ? 4 : wanted;
                    for (int i = 0; i < written / 2; i++) accepted.add(bytes.getShort());
                    return written;
                });
        TestRenderer renderer = new TestRenderer(application, track, false) {
            @Override boolean supportsShortArrayNonblockingWrite() { return false; }
        };
        assertEquals(0, setup(renderer));
        renderer.playDecodedAudio(new short[] {1, 2, 3, 4});
        renderer.playDecodedAudio(new short[] {5, 6, 7, 8});
        assertEquals(java.util.Arrays.asList((short)1,(short)2,(short)3,(short)4,
                (short)5,(short)6,(short)7,(short)8), accepted);
        renderer.cleanup();
    }

    @Test @Config(sdk = 23)
    public void legacyByteBufferPathUsesNonblockingPcmWrites() {
        List<short[]> written = new ArrayList<>();
        when(track.write(any(ByteBuffer.class), anyInt(), eq(AudioTrack.WRITE_NON_BLOCKING))).thenAnswer(call -> {
            ByteBuffer bytes = ((ByteBuffer) call.getArgument(0)).duplicate().order(ByteOrder.nativeOrder());
            int size = call.getArgument(1);
            short[] pcm = new short[size / 2];
            bytes.asShortBuffer().get(pcm);
            written.add(pcm);
            return size;
        });
        // Robolectric 4.16 no longer provides API 21/22. Exercise the exact legacy
        // path on its oldest available SDK; this is not an API 21 device test.
        TestRenderer renderer = new TestRenderer(application, track, false) {
            @Override boolean supportsShortArrayNonblockingWrite() { return false; }
        };
        assertEquals(0, setup(renderer));
        renderer.playDecodedAudio(new short[] {-100, 200, 300});
        assertEquals(1, written.size());
        assertArrayEquals(new short[] {-100, 200, 300}, written.get(0));
        verify(track, never()).write(any(short[].class), anyInt(), anyInt());
        renderer.cleanup();
    }

    @Test public void effectSessionClosesDuringSuspensionAndReopensOnlyOnResume() {
        TestRenderer renderer = new TestRenderer(application, track, true);
        renderer.start(); // A delayed native setup is allowed.
        renderer.setSuspended(true);
        assertEquals(0, setup(renderer));
        assertEquals(0, effectBroadcasts().size());
        renderer.setSuspended(false);
        renderer.setSuspended(true);
        renderer.setSuspended(false);
        renderer.cleanup();
        assertEquals(java.util.Arrays.asList(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION,
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION), effectBroadcasts());
    }

    @Test public void nativeQueueBoundAndFailedResumeNeverFeedPcm() {
        TestRenderer renderer = new TestRenderer(application, track, false);
        assertEquals(0, setup(renderer));
        renderer.pendingDuration = 40;
        renderer.playDecodedAudio(new short[] {1, 2});
        renderer.pendingDuration = 0;
        renderer.setSuspended(true);
        doThrow(new IllegalStateException("device unavailable")).when(track).play();
        renderer.setSuspended(false);
        renderer.playDecodedAudio(new short[] {3, 4});
        verify(track, never()).write(any(short[].class), anyInt(), anyInt(), anyInt());
        renderer.cleanup();
    }

    private static int setup(AndroidAudioRenderer renderer) {
        return renderer.setup(new MoonBridge.AudioConfiguration(2, 3), 48000, 240);
    }

    private List<String> effectBroadcasts() {
        return Shadows.shadowOf(application).getBroadcastIntents().stream().map(Intent::getAction)
                .filter(action -> AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action) ||
                        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action))
                .collect(Collectors.toList());
    }

    private static Thread setupThread(AndroidAudioRenderer renderer, AtomicInteger result,
                                      AtomicReference<Throwable> failure) {
        Thread worker = new Thread(() -> {
            try { result.set(setup(renderer)); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        return worker;
    }

    private static class TestRenderer extends AndroidAudioRenderer {
        final AudioTrack candidate;
        int pendingDuration;
        int lastBufferSize;
        TestRenderer(Context context, AudioTrack candidate, boolean effects) {
            super(context, effects);
            this.candidate = candidate;
        }
        @Override AudioTrack createAudioTrack(int channels, int sampleRate, int buffer, boolean lowLatency) {
            lastBufferSize = buffer;
            return candidate;
        }
        @Override int getPendingAudioDuration() { return pendingDuration; }
    }

    private static class CreationGate extends TestRenderer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        CreationGate(Context context, AudioTrack candidate) { super(context, candidate, false); }
        @Override AudioTrack createAudioTrack(int channels, int sampleRate, int buffer, boolean lowLatency) {
            entered.countDown();
            try {
                if (!proceed.await(2, TimeUnit.SECONDS)) throw new AssertionError("setup gate timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return candidate;
        }
    }
}
