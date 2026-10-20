package com.limelight.binding.video;

import android.view.Surface;

import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Keeps the network video renderer alive while releasing codecs that target a hidden Surface.
 *
 * <p>Unlike MediaCodec.setOutputSurface(), recreating the decoder also works on API 21 and 22.
 * Calls from the Activity and native decode thread share this monitor, so no decode submission
 * can race decoder disposal or use the old Surface after setRenderTarget(null) returns.</p>
 */
public final class ResumableVideoDecoderRenderer extends VideoDecoderRenderer {
    /** A renderer that can identify a frame presented to its output Surface. */
    public interface FramePresentationSource {
        void setOnFirstFramePresented(Runnable callback);
    }

    public interface DecoderFactory {
        /** Returns an unconfigured decoder already bound to the supplied valid Surface. */
        VideoDecoderRenderer create(Surface surface);
    }

    private final int capabilities;
    private final DecoderFactory factory;
    private volatile VideoDecoderRenderer decoder;
    private volatile Surface renderTarget;
    private boolean foreground = true;
    private boolean configured;
    private boolean started;
    private boolean stopped;
    private boolean cleanedUp;
    private boolean decoderStarted;
    private boolean awaitingIdr = true;
    private int format;
    private int width;
    private int height;
    private int redrawRate;
    private boolean hasHdrState;
    private boolean hdrEnabled;
    private byte[] hdrMetadata;
    private Runnable onFirstFramePresented;
    private long decoderGeneration;
    private boolean firstFramePresented;
    private boolean firstFrameCallbackDelivered;

    public ResumableVideoDecoderRenderer(int capabilities, DecoderFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        this.capabilities = capabilities;
        this.factory = factory;
    }

    /** Returns true when a decoder was created, allowing the caller to request an IDR now. */
    public boolean setRenderTarget(Surface surface) {
        if (renderTarget != surface || surface == null || !surface.isValid()) {
            prepareDecoderForStop();
        }
        synchronized (this) {
            if (cleanedUp) {
                return false;
            }
            if (renderTarget != surface || surface == null || !surface.isValid()) {
                releaseDecoder();
                renderTarget = surface;
            }
            return resumeDecoder();
        }
    }

    /** Releases the codec, renderer thread, and frame callbacks while video is hidden. */
    public boolean setForeground(boolean foreground) {
        if (!foreground) {
            prepareDecoderForStop();
        }
        synchronized (this) {
            this.foreground = foreground;
            if (!foreground) {
                releaseDecoder();
                return false;
            }
            return resumeDecoder();
        }
    }

    public synchronized boolean isSuspended() {
        return decoder == null;
    }

    /**
     * Called once for each newly created decoder, after its first presented frame.
     * MediaCodec dispatches this on the main looper. Submission/handshake success is not
     * a presentation signal. A callback already queued by a disposed decoder is ignored.
     */
    public void setOnFirstFramePresented(Runnable callback) {
        Runnable ready = null;
        synchronized (this) {
            onFirstFramePresented = callback;
            if (callback != null && firstFramePresented && !firstFrameCallbackDelivered &&
                    decoder != null && decoderStarted && !stopped && !cleanedUp) {
                firstFrameCallbackDelivered = true;
                ready = callback;
            }
        }
        if (ready != null) ready.run();
    }

    private void onDecoderFirstFramePresented(VideoDecoderRenderer source, long generation) {
        Runnable ready = null;
        synchronized (this) {
            if (source != decoder || generation != decoderGeneration || !decoderStarted ||
                    !foreground || stopped || cleanedUp) return;
            firstFramePresented = true;
            if (!firstFrameCallbackDelivered && onFirstFramePresented != null) {
                firstFrameCallbackDelivered = true;
                ready = onFirstFramePresented;
            }
        }
        // Never invoke Activity code while holding the submission/codec disposal monitor.
        if (ready != null) ready.run();
    }

    @Override
    public synchronized int setup(int format, int width, int height, int redrawRate) {
        if (stopped || cleanedUp || configured) {
            return -1;
        }
        this.format = format;
        this.width = width;
        this.height = height;
        this.redrawRate = redrawRate;
        configured = true;
        return createDecoderIfReady();
    }

    @Override
    public synchronized void start() {
        if (stopped || cleanedUp) {
            return;
        }
        started = true;
        if (decoder != null && !decoderStarted) {
            decoder.start();
            decoderStarted = true;
        }
    }

    @Override
    public void prepareForStop() {
        prepareDecoderForStop();
    }

    @Override
    public void stop() {
        prepareDecoderForStop();
        synchronized (this) {
            stopped = true;
            started = false;
            releaseDecoder();
        }
    }

    @Override
    public void cleanup() {
        prepareDecoderForStop();
        synchronized (this) {
            cleanedUp = true;
            stopped = true;
            started = false;
            releaseDecoder();
            renderTarget = null;
            hdrMetadata = null;
            onFirstFramePresented = null;
        }
    }

    @Override
    public int getCapabilities() {
        return capabilities;
    }

    @Override
    public synchronized void setHdrMode(boolean enabled, byte[] metadata) {
        hasHdrState = true;
        hdrEnabled = enabled;
        hdrMetadata = metadata == null ? null : metadata.clone();
        if (decoder != null) {
            decoder.setHdrMode(hdrEnabled, copyHdrMetadata());
        }
    }

    @Override
    public synchronized int submitDecodeUnit(byte[] data, int length, int type,
                                            int frameNumber, int frameType,
                                            char hostLatency, long receiveTimeMs,
                                            long enqueueTimeMs) {
        if (decoder == null || !decoderStarted || stopped || cleanedUp) {
            // Continue draining network traffic without waking MediaCodec or asking for IDRs.
            return MoonBridge.DR_OK;
        }

        if (awaitingIdr) {
            // An IDR is delivered as multiple Java calls. Resuming between its SPS/VPS and
            // picture data must not send the tail of that frame to a fresh decoder.
            if (!isIdrStart(type, frameType)) {
                return MoonBridge.DR_NEED_IDR;
            }
            awaitingIdr = false;
        }

        int result = decoder.submitDecodeUnit(data, length, type, frameNumber, frameType,
                hostLatency, receiveTimeMs, enqueueTimeMs);
        if (result == MoonBridge.DR_NEED_IDR) {
            awaitingIdr = true;
        }
        return result;
    }

    private boolean isIdrStart(int type, int frameType) {
        if (frameType != MoonBridge.FRAME_TYPE_IDR) {
            return false;
        }
        if ((format & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            return type == MoonBridge.BUFFER_TYPE_SPS;
        }
        if ((format & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            return type == MoonBridge.BUFFER_TYPE_VPS;
        }
        return type == MoonBridge.BUFFER_TYPE_PICDATA;
    }

    private boolean resumeDecoder() {
        if (decoder != null) {
            return false;
        }
        int result = createDecoderIfReady();
        if (result != 0) {
            throw new IllegalStateException("Unable to resume video decoder: " + result);
        }
        return decoder != null;
    }

    private int createDecoderIfReady() {
        if (decoder != null || !configured || !foreground || stopped || cleanedUp ||
                renderTarget == null || !renderTarget.isValid()) {
            return 0;
        }
        VideoDecoderRenderer candidate = factory.create(renderTarget);
        if (candidate == null) {
            throw new IllegalStateException("Decoder factory returned null");
        }
        final long candidateGeneration = ++decoderGeneration;
        if (candidate instanceof FramePresentationSource) {
            ((FramePresentationSource) candidate).setOnFirstFramePresented(
                    () -> onDecoderFirstFramePresented(candidate, candidateGeneration));
        }
        int result;
        try {
            result = candidate.setup(format, width, height, redrawRate);
        } catch (RuntimeException | Error failure) {
            try {
                candidate.cleanup();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (result != 0) {
            candidate.cleanup();
            return result;
        }
        decoder = candidate;
        firstFramePresented = false;
        firstFrameCallbackDelivered = false;
        awaitingIdr = true;
        if (hasHdrState) {
            decoder.setHdrMode(hdrEnabled, copyHdrMetadata());
        }
        if (started) {
            decoder.start();
            decoderStarted = true;
        }
        return 0;
    }

    private byte[] copyHdrMetadata() {
        return hdrMetadata == null ? null : hdrMetadata.clone();
    }

    private void prepareDecoderForStop() {
        // Codec recovery can be waiting for a Choreographer callback inside submitDecodeUnit().
        // Wake that wait before acquiring this monitor, especially when display VSYNC stopped.
        VideoDecoderRenderer current = decoder;
        if (current != null) {
            current.prepareForStop();
        }
    }

    private void releaseDecoder() {
        if (decoder == null) {
            return;
        }
        VideoDecoderRenderer previous = decoder;
        boolean wasStarted = decoderStarted;
        decoder = null;
        decoderGeneration++;
        firstFramePresented = false;
        firstFrameCallbackDelivered = false;
        decoderStarted = false;
        awaitingIdr = true;
        try {
            if (wasStarted) {
                previous.stop();
            }
        } finally {
            previous.cleanup();
        }
    }
}
