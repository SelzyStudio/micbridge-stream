package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private final Object trackLock = new Object();
    private AudioTrack track;
    private ByteBuffer legacyWriteBuffer;
    private PcmPlaybackBuffer playbackBuffer;
    private int playbackSamplesPerMillisecond;
    private long lastPlaybackReport;
    private long nativeBacklogDroppedSamples;
    private boolean suspended;
    private boolean cleanedUp;
    private boolean audioFxRequested;
    private boolean audioFxOpen;
    private boolean writeFailureLogged;
    private long setupGeneration;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext != null ? applicationContext : context;
        this.enableAudioFx = enableAudioFx;
    }

    AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        final long generation;
        synchronized (trackLock) {
            if (cleanedUp) return -2;
            generation = ++setupGeneration;
            releaseTrackLocked();
        }
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;
        // Two 5-ms packets left almost no margin for callback/network jitter.
        // Keep a 40-ms device buffer, rounded to whole decoded Opus packets.
        int minimumBufferBytes = Math.max(bytesPerFrame * 2,
                (((sampleRate * 40 + 999) / 1000 * audioConfiguration.channelCount * 2
                        + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame);

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = minimumBufferBytes;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            minimumBufferBytes);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            AudioTrack candidate = null;
            try {
                // Creation can take time. Do not prevent suspension or cleanup while it runs.
                candidate = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                synchronized (trackLock) {
                    if (cleanedUp || generation != setupGeneration) {
                        candidate.release();
                        return -2;
                    }
                    if (!suspended) candidate.play();
                    track = candidate;
                    playbackSamplesPerMillisecond = Math.max(1,
                            sampleRate * audioConfiguration.channelCount / 1000);
                    playbackBuffer = new PcmPlaybackBuffer(playbackSamplesPerMillisecond * 60);
                    lastPlaybackReport = SystemClock.elapsedRealtime();
                    nativeBacklogDroppedSamples = 0;
                    writeFailureLogged = false;
                    updateAudioFxLocked();
                    LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                    return 0;
                }
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (candidate != null) candidate.release();
                } catch (Exception ignored) {}
            }
        }
        return -2;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        synchronized (trackLock) {
            if (suspended || cleanedUp || track == null || audioData == null || audioData.length == 0) return;
            // Keep the existing upper bound on pending native audio as well.
            if (getPendingAudioDuration() >= 40) {
                nativeBacklogDroppedSamples += audioData.length;
                if (playbackBuffer != null) playbackBuffer.clear();
                return;
            }
            try {
                int written = playbackBuffer.write(audioData, this::writePcmNonblockingLocked);
                if (written < 0 && !writeFailureLogged) {
                    writeFailureLogged = true;
                    LimeLog.warning("AudioTrack write failed: " + written);
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastPlaybackReport >= 5000) {
                    lastPlaybackReport = now;
                    LimeLog.info("Audio playback buffering: partial=" + playbackBuffer.partialWrites()
                            + " full=" + playbackBuffer.zeroWrites()
                            + " queuedMs=" + playbackBuffer.queuedSamples() / playbackSamplesPerMillisecond
                            + " droppedSamples=" + playbackBuffer.droppedSamples()
                            + " nativeBacklogDroppedSamples=" + nativeBacklogDroppedSamples);
                }
            } catch (RuntimeException e) {
                if (playbackBuffer != null) playbackBuffer.clear();
                if (!writeFailureLogged) {
                    writeFailureLogged = true;
                    LimeLog.warning("AudioTrack write unavailable: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private int writePcmNonblockingLocked(short[] data, int offset, int count) {
        if (supportsShortArrayNonblockingWrite()) {
            return track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING);
        }
        // API 21/22 provide nonblocking ByteBuffer writes; the return unit is bytes.
        int size = count * 2;
        if (legacyWriteBuffer == null || legacyWriteBuffer.capacity() < size) {
            legacyWriteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }
        legacyWriteBuffer.clear();
        legacyWriteBuffer.asShortBuffer().put(data, offset, count);
        legacyWriteBuffer.limit(size);
        int written = track.write(legacyWriteBuffer, size, AudioTrack.WRITE_NON_BLOCKING);
        return written < 0 ? written : written / 2;
    }

    int getPendingAudioDuration() {
        return MoonBridge.getPendingAudioDuration();
    }

    boolean supportsShortArrayNonblockingWrite() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /** Stores the desired state even if setup has not finished yet. */
    public void setSuspended(boolean value) {
        synchronized (trackLock) {
            if (cleanedUp || suspended == value) return;
            suspended = value;
            if (playbackBuffer != null) playbackBuffer.clear();
            writeFailureLogged = false;
            if (track != null) {
                try {
                    if (value) {
                        track.pause();
                        track.flush();
                    } else {
                        track.play();
                    }
                } catch (RuntimeException e) {
                    suspended = true;
                    LimeLog.warning("AudioTrack state change failed: " + e.getClass().getSimpleName());
                }
            }
            updateAudioFxLocked();
        }
    }

    @Override
    public void start() {
        synchronized (trackLock) {
            if (cleanedUp) return;
            audioFxRequested = true;
            updateAudioFxLocked();
        }
    }

    @Override
    public void stop() {
        synchronized (trackLock) {
            audioFxRequested = false;
            updateAudioFxLocked();
        }
    }

    @Override
    public void cleanup() {
        synchronized (trackLock) {
            if (cleanedUp) return;
            cleanedUp = true;
            setupGeneration++;
            audioFxRequested = false;
            releaseTrackLocked();
            legacyWriteBuffer = null;
        }
    }

    private void updateAudioFxLocked() {
        boolean shouldOpen = enableAudioFx && audioFxRequested && !suspended && !cleanedUp && track != null;
        if (shouldOpen == audioFxOpen) return;
        if (track != null) {
            try {
                Intent i = new Intent(shouldOpen ? AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION :
                        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
                i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
                if (shouldOpen) i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
                context.sendBroadcast(i);
            } catch (RuntimeException e) {
                LimeLog.warning("Audio effect session unavailable: " + e.getClass().getSimpleName());
            }
        }
        audioFxOpen = shouldOpen;
    }

    private void releaseTrackLocked() {
        if (playbackBuffer != null) playbackBuffer.clear();
        playbackBuffer = null;
        if (track == null) return;
        AudioTrack old = track;
        boolean requested = audioFxRequested;
        audioFxRequested = false;
        updateAudioFxLocked();
        audioFxRequested = requested;
        track = null;
        try {
            old.pause();
            old.flush();
        } catch (RuntimeException ignored) {
            // Device teardown must not prevent release or leave a stale track visible.
        } finally {
            try {
                old.release();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
