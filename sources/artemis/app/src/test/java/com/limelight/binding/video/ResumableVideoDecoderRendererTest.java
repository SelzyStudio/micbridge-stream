package com.limelight.binding.video;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.view.Surface;

import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, manifest = Config.NONE, application = Application.class)
public class ResumableVideoDecoderRendererTest {
    private final List<FakeDecoder> decoders = new ArrayList<>();
    private ResumableVideoDecoderRenderer renderer;
    private Surface surface;
    private int nextSetupResult;
    private boolean nextSetupThrows;

    @Before
    public void setUp() {
        surface = validSurface();
        renderer = new ResumableVideoDecoderRenderer(77, target -> {
            FakeDecoder decoder = new FakeDecoder();
            decoder.surface = target;
            decoder.setupResult = nextSetupResult;
            decoder.setupThrows = nextSetupThrows;
            decoders.add(decoder);
            return decoder;
        });
    }

    @Test
    public void setupAndStartWaitForValidSurfaceWithoutRequestingIdr() {
        assertEquals(0, renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60));
        renderer.start();
        assertTrue(renderer.isSuspended());
        assertTrue(decoders.isEmpty());
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertTrue(renderer.setRenderTarget(surface));
        assertEquals(Arrays.asList("setup", "start"), current().calls);
    }

    @Test
    public void initialDecoderGetsNegotiatedFormatAndBoundSurface() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        assertEquals(77, renderer.getCapabilities());
        assertSame(surface, current().surface);
        assertEquals(MoonBridge.VIDEO_FORMAT_H264, current().format);
        assertEquals(1920, current().width);
        assertEquals(1080, current().height);
        assertEquals(60, current().redrawRate);
        assertFalse(renderer.isSuspended());
    }

    @Test
    public void initialHandshakeWhileHiddenSucceedsAndDefersCodecUntilVisible() {
        renderer.setForeground(false);
        renderer.setRenderTarget(surface);
        assertEquals(0, renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60));
        renderer.start();
        assertTrue(decoders.isEmpty());
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        assertTrue(renderer.setForeground(true));
        assertEquals(Arrays.asList("setup", "start"), current().calls);
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
    }

    @Test
    public void hiddenVideoDisposesCodecAndDropsAllFramesWithoutIdrRequests() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder previous = current();
        assertFalse(renderer.setForeground(false));
        assertTrue(renderer.isSuspended());
        assertEquals(Arrays.asList("setup", "start", "stop", "cleanup"), previous.calls);
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertFalse(renderer.setForeground(false));
        assertEquals(0, previous.submissions);
        assertEquals(1, decoders.size());
    }

    @Test
    public void foregroundUsesNewCodecAndRestoresOriginalNegotiation() {
        ready(MoonBridge.VIDEO_FORMAT_H265_MAIN10);
        FakeDecoder previous = current();
        renderer.setForeground(false);
        assertTrue(renderer.setForeground(true));
        assertNotSame(previous, current());
        assertEquals(2, decoders.size());
        assertEquals(MoonBridge.VIDEO_FORMAT_H265_MAIN10, current().format);
        assertEquals(Arrays.asList("setup", "start"), current().calls);
        assertFalse(renderer.setForeground(true));
    }

    @Test
    public void surfaceRecreationWhileHiddenWaitsUntilForeground() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        renderer.setForeground(false);
        assertFalse(renderer.setRenderTarget(null));
        Surface replacement = validSurface();
        assertFalse(renderer.setRenderTarget(replacement));
        assertEquals(1, decoders.size());
        assertTrue(renderer.setForeground(true));
        assertSame(replacement, current().surface);
    }

    @Test
    public void foregroundWithoutSurfaceWaitsForSurfaceCallback() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        renderer.setForeground(false);
        renderer.setRenderTarget(null);
        assertFalse(renderer.setForeground(true));
        assertTrue(renderer.isSuspended());
        assertTrue(renderer.setRenderTarget(validSurface()));
        assertEquals(2, decoders.size());
    }

    @Test
    public void sameSurfaceCallbackDoesNotRestartDecoder() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        assertFalse(renderer.setRenderTarget(surface));
        assertEquals(1, decoders.size());
        assertEquals(Arrays.asList("setup", "start"), current().calls);
    }

    @Test
    public void invalidSurfaceNeverCreatesCodec() {
        renderer.setRenderTarget(mock(Surface.class));
        renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60);
        renderer.start();
        assertTrue(renderer.isSuspended());
        assertTrue(decoders.isEmpty());
    }

    @Test
    public void sameSurfaceThatBecameInvalidDisposesItsCodec() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        when(surface.isValid()).thenReturn(false);
        assertFalse(renderer.setRenderTarget(surface));
        assertTrue(renderer.isSuspended());
        assertEquals(Arrays.asList("setup", "start", "stop", "cleanup"), current().calls);
    }

    @Test
    public void replacingLiveSurfaceDisposesOldCodecBeforeCreatingNewOne() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder previous = current();
        assertTrue(renderer.setRenderTarget(validSurface()));
        assertEquals(Arrays.asList("setup", "start", "stop", "cleanup"), previous.calls);
        assertEquals(Arrays.asList("setup", "start"), current().calls);
    }

    @Test
    public void h264RequiresBeginningOfIdrRatherThanTailOfPartialIdr() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(0, current().submissions);
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertEquals(4, current().submissions);
    }

    @Test
    public void hevcRequiresVpsAtBeginningOfFreshIdr() {
        ready(MoonBridge.VIDEO_FORMAT_H265);
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_VPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(2, current().submissions);
    }

    @Test
    public void av1ResumesFromCompleteIdrPicture() {
        ready(MoonBridge.VIDEO_FORMAT_AV1_MAIN8);
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(1, current().submissions);
    }

    @Test
    public void decoderErrorRequiresAnotherCompleteIdr() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        current().submissionResult = MoonBridge.DR_NEED_IDR;
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        current().submissionResult = MoonBridge.DR_OK;
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR));
        assertEquals(1, current().submissions);
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
    }

    @Test
    public void resumeDoesNotUseReferenceFramesFromPreviousDecoder() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR);
        renderer.setForeground(false);
        renderer.setForeground(true);
        assertEquals(MoonBridge.DR_NEED_IDR, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
        assertEquals(0, current().submissions);
    }

    @Test
    public void hdrMetadataIsCopiedAndRestoredAfterCodecRecreation() {
        byte[] metadata = {1, 2, 3};
        renderer.setHdrMode(true, metadata);
        metadata[0] = 99;
        ready(MoonBridge.VIDEO_FORMAT_H265_MAIN10);
        assertTrue(current().hdrEnabled);
        assertArrayEquals(new byte[] {1, 2, 3}, current().hdrMetadata);
        current().hdrMetadata[1] = 99;
        renderer.setForeground(false);
        renderer.setForeground(true);
        assertArrayEquals(new byte[] {1, 2, 3}, current().hdrMetadata);
    }

    @Test
    public void hdrChangesWhileHiddenReachOnlyNewDecoder() {
        ready(MoonBridge.VIDEO_FORMAT_H265_MAIN10);
        FakeDecoder previous = current();
        renderer.setForeground(false);
        renderer.setHdrMode(false, null);
        assertEquals(0, previous.hdrCalls);
        renderer.setForeground(true);
        assertEquals(1, current().hdrCalls);
        assertFalse(current().hdrEnabled);
        assertNull(current().hdrMetadata);
    }

    @Test
    public void nativeStopAndCleanupAreTerminalAndIdempotent() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder previous = current();
        renderer.stop();
        renderer.cleanup();
        renderer.stop();
        renderer.cleanup();
        renderer.setForeground(false);
        renderer.setForeground(true);
        renderer.setRenderTarget(validSurface());
        assertEquals(Arrays.asList("setup", "start", "stop", "cleanup"), previous.calls);
        assertEquals(1, decoders.size());
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
    }

    @Test
    public void cleanupBeforeNativeStartDoesNotStopUnstartedRendererThread() {
        renderer.setRenderTarget(surface);
        renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60);
        renderer.cleanup();
        assertEquals(Arrays.asList("setup", "cleanup"), current().calls);
        renderer.start();
        assertEquals(Arrays.asList("setup", "cleanup"), current().calls);
    }

    @Test
    public void nativeSetupFailureDisposesFailedDecoderExactlyOnce() {
        renderer.setRenderTarget(surface);
        nextSetupResult = -5;
        assertEquals(-5, renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60));
        assertTrue(renderer.isSuspended());
        renderer.cleanup();
        assertEquals(Arrays.asList("setup", "cleanup"), current().calls);
    }

    @Test
    public void unexpectedSetupExceptionStillDisposesCandidate() {
        renderer.setRenderTarget(surface);
        nextSetupThrows = true;
        try {
            renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60);
            fail("Expected setup exception");
        } catch (IllegalArgumentException expected) {
            assertEquals("test setup failure", expected.getMessage());
        }
        assertTrue(renderer.isSuspended());
        assertEquals(Arrays.asList("setup", "cleanup"), current().calls);
    }

    @Test
    public void resumeSetupFailureReportsErrorAndKeepsCodecDisposed() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        renderer.setForeground(false);
        nextSetupResult = -5;
        try {
            renderer.setForeground(true);
            fail("Expected resume failure");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("-5"));
        }
        assertTrue(renderer.isSuspended());
        assertEquals(Arrays.asList("setup", "cleanup"), current().calls);
    }

    @Test
    public void backgroundDisposalWaitsForInFlightSubmission() throws Exception {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder previous = current();
        previous.submitEntered = new CountDownLatch(1);
        previous.allowSubmitToFinish = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread submitThread = checkedThread(error, () ->
                submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        submitThread.start();
        assertTrue(previous.submitEntered.await(5, TimeUnit.SECONDS));
        CountDownLatch backgroundRequested = new CountDownLatch(1);
        Thread backgroundThread = checkedThread(error, () -> {
            backgroundRequested.countDown();
            renderer.setForeground(false);
        });
        backgroundThread.start();
        assertTrue(backgroundRequested.await(5, TimeUnit.SECONDS));
        assertFalse(previous.calls.contains("cleanup"));
        previous.allowSubmitToFinish.countDown();
        submitThread.join(5000);
        backgroundThread.join(5000);
        assertFalse(submitThread.isAlive());
        assertFalse(backgroundThread.isAlive());
        assertNull(error.get());
        assertTrue(renderer.isSuspended());
        assertEquals(Arrays.asList("setup", "start", "submit", "stop", "cleanup"), previous.calls);
        assertEquals(MoonBridge.DR_OK, submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_PFRAME));
    }

    @Test
    public void backgroundWakesCodecRecoveryBeforeAcquiringSubmissionLock() throws Exception {
        assertShutdownWakesRecovery(() -> renderer.setForeground(false));
    }

    @Test
    public void surfaceDestructionWakesCodecRecoveryBeforeAcquiringSubmissionLock() throws Exception {
        assertShutdownWakesRecovery(() -> renderer.setRenderTarget(null));
    }

    @Test
    public void nativeStopWakesCodecRecoveryBeforeAcquiringSubmissionLock() throws Exception {
        assertShutdownWakesRecovery(renderer::stop);
    }

    @Test
    public void nativeCleanupWakesCodecRecoveryBeforeAcquiringSubmissionLock() throws Exception {
        assertShutdownWakesRecovery(renderer::cleanup);
    }

    @Test
    public void setupStartAndDecodeSubmissionDoNotReportPresentedFrame() {
        AtomicInteger callbacks = new AtomicInteger();
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        ready(MoonBridge.VIDEO_FORMAT_H264);
        submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR);
        submit(MoonBridge.BUFFER_TYPE_PICDATA, MoonBridge.FRAME_TYPE_IDR);
        assertEquals(0, callbacks.get());
        current().presentFrame();
        assertEquals(1, callbacks.get());
    }

    @Test
    public void actualPresentationFiresOnlyOncePerDelegate() {
        AtomicInteger callbacks = new AtomicInteger();
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        ready(MoonBridge.VIDEO_FORMAT_H264);
        current().presentFrame();
        current().presentFrame();
        assertEquals(1, callbacks.get());
        renderer.setForeground(false);
        renderer.setForeground(true);
        current().presentFrame();
        current().presentFrame();
        assertEquals(2, callbacks.get());
    }

    @Test
    public void presentationFromDisposedDelegateCannotUncoverReplacementSurface() {
        AtomicInteger callbacks = new AtomicInteger();
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder previous = current();
        renderer.setRenderTarget(validSurface());
        previous.presentFrame();
        assertEquals(0, callbacks.get());
        current().presentFrame();
        assertEquals(1, callbacks.get());
    }

    @Test
    public void hiddenOrStoppedDelegateCannotReportLatePresentation() {
        AtomicInteger callbacks = new AtomicInteger();
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder first = current();
        renderer.setForeground(false);
        first.presentFrame();
        renderer.setForeground(true);
        FakeDecoder second = current();
        renderer.stop();
        second.presentFrame();
        renderer.cleanup();
        second.presentFrame();
        assertEquals(0, callbacks.get());
    }

    @Test
    public void callbackInstalledAfterPresentationReceivesOneCurrentFrame() {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        current().presentFrame();
        AtomicInteger callbacks = new AtomicInteger();
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        current().presentFrame();
        assertEquals(1, callbacks.get());
    }

    @Test
    public void failedDecoderSetupCannotReportPresentation() {
        AtomicInteger callbacks = new AtomicInteger();
        renderer.setOnFirstFramePresented(callbacks::incrementAndGet);
        renderer.setRenderTarget(surface);
        nextSetupResult = -5;
        assertEquals(-5, renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 60));
        current().presentFrame();
        assertEquals(0, callbacks.get());
    }

    @Test
    public void presentationCallbackRunsOutsideSubmissionMonitor() throws Exception {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        AtomicReference<Throwable> error = new AtomicReference<>();
        renderer.setOnFirstFramePresented(() -> {
            Thread dispose = checkedThread(error, () -> renderer.setForeground(false));
            dispose.start();
            try {
                dispose.join(2000);
                assertFalse("Activity callback must not hold decoder monitor", dispose.isAlive());
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
        });
        current().presentFrame();
        assertNull(error.get());
        assertTrue(renderer.isSuspended());
    }

    private void assertShutdownWakesRecovery(Runnable shutdown) throws Exception {
        ready(MoonBridge.VIDEO_FORMAT_H264);
        FakeDecoder previous = current();
        previous.submitEntered = new CountDownLatch(1);
        previous.allowSubmitToFinish = new CountDownLatch(1);
        previous.prepareAbortsSubmission = true;
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread submit = checkedThread(error, () ->
                submit(MoonBridge.BUFFER_TYPE_SPS, MoonBridge.FRAME_TYPE_IDR));
        Thread stop = checkedThread(error, shutdown);
        submit.start();
        assertTrue(previous.submitEntered.await(5, TimeUnit.SECONDS));
        stop.start();
        submit.join(5000);
        stop.join(5000);
        assertFalse(submit.isAlive());
        assertFalse(stop.isAlive());
        assertNull(error.get());
        assertTrue(previous.prepareCalls > 0);
        assertTrue(renderer.isSuspended());
        assertEquals(Arrays.asList("setup", "start", "submit", "stop", "cleanup"), previous.calls);
    }

    private void ready(int format) {
        renderer.setRenderTarget(surface);
        assertEquals(0, renderer.setup(format, 1920, 1080, 60));
        renderer.start();
    }

    private int submit(int type, int frameType) {
        return renderer.submitDecodeUnit(new byte[] {1, 2}, 2, type, 123, frameType,
                (char) 7, 1000, 1002);
    }

    private FakeDecoder current() {
        return decoders.get(decoders.size() - 1);
    }

    private static Surface validSurface() {
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        return surface;
    }

    private static Thread checkedThread(AtomicReference<Throwable> error, Runnable task) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });
    }

    private static final class FakeDecoder extends VideoDecoderRenderer implements
            ResumableVideoDecoderRenderer.FramePresentationSource {
        final List<String> calls = new ArrayList<>();
        Surface surface;
        int format;
        int width;
        int height;
        int redrawRate;
        int setupResult;
        boolean setupThrows;
        int submissionResult = MoonBridge.DR_OK;
        int submissions;
        int hdrCalls;
        boolean hdrEnabled;
        byte[] hdrMetadata;
        CountDownLatch submitEntered;
        CountDownLatch allowSubmitToFinish;
        boolean prepareAbortsSubmission;
        int prepareCalls;
        Runnable onFirstFramePresented;

        @Override public void setOnFirstFramePresented(Runnable callback) {
            onFirstFramePresented = callback;
        }
        void presentFrame() {
            if (onFirstFramePresented != null) onFirstFramePresented.run();
        }

        @Override
        public int setup(int format, int width, int height, int redrawRate) {
            calls.add("setup");
            this.format = format;
            this.width = width;
            this.height = height;
            this.redrawRate = redrawRate;
            if (setupThrows) {
                throw new IllegalArgumentException("test setup failure");
            }
            return setupResult;
        }

        @Override public void start() { calls.add("start"); }
        @Override public void prepareForStop() {
            prepareCalls++;
            if (prepareAbortsSubmission && allowSubmitToFinish != null) {
                allowSubmitToFinish.countDown();
            }
        }
        @Override public void stop() { calls.add("stop"); }
        @Override public void cleanup() { calls.add("cleanup"); }
        @Override public int getCapabilities() { return 77; }

        @Override
        public int submitDecodeUnit(byte[] data, int length, int type, int frameNumber,
                                    int frameType, char hostLatency, long receiveTimeMs,
                                    long enqueueTimeMs) {
            calls.add("submit");
            submissions++;
            if (submitEntered != null) {
                submitEntered.countDown();
                try {
                    if (!allowSubmitToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for test submission");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return submissionResult;
        }

        @Override
        public void setHdrMode(boolean enabled, byte[] metadata) {
            hdrCalls++;
            hdrEnabled = enabled;
            hdrMetadata = metadata;
        }
    }
}
