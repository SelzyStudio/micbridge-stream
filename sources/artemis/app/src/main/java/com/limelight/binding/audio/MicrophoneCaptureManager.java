package com.limelight.binding.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MicrophoneCaptureManager {
    public interface LevelListener {
        void onLevelUpdate(double level, boolean signalDetected, String status);
    }

    public static final class InputDeviceEntry {
        public final int id;
        public final String label;

        public InputDeviceEntry(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 1;
    private static final int FRAME_SIZE = 960;
    private static final int DEFAULT_BITRATE = 24000;
    private static final int LEVEL_UPDATE_INTERVAL_MS = 50;
    private static final int SIGNAL_PEAK_THRESHOLD = 250;
    private static final double SIGNAL_RMS_THRESHOLD = 90.0;
    private static final double PREVIEW_RMS_FLOOR = 40.0;
    private static final double PREVIEW_RMS_CEILING = 4000.0;
    private static final double PREVIEW_PEAK_CEILING = 12000.0;
    private static final double PREVIEW_DECAY_FACTOR = 0.84;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object captureStateLock = new Object();

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running;
    private volatile long captureGeneration;
    private boolean captureAllowed = true;
    private boolean streamingToHost;
    private LevelListener levelListener;

    public MicrophoneCaptureManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean hasRecordAudioPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
    }

    public static List<InputDeviceEntry> getAvailableInputDevices(Context context) {
        Map<Integer, InputDeviceEntry> uniqueEntries = new LinkedHashMap<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new ArrayList<>();
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return new ArrayList<>();
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            String label = describeDevice(deviceInfo);
            uniqueEntries.put(deviceInfo.getId(), new InputDeviceEntry(deviceInfo.getId(), label));
        }

        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        for (InputDeviceEntry entry : uniqueEntries.values()) {
            Integer count = labelCounts.get(entry.label);
            labelCounts.put(entry.label, count == null ? 1 : count + 1);
        }
        List<InputDeviceEntry> entries = new ArrayList<>();
        for (InputDeviceEntry entry : uniqueEntries.values()) {
            entries.add(new InputDeviceEntry(entry.id, labelCounts.get(entry.label) > 1 ?
                    entry.label + " (#" + entry.id + ")" : entry.label));
        }
        return entries;
    }

    public boolean startPreview(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, false);
    }

    public boolean startStreaming(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, true);
    }

    /** Immediately prevents a delayed read/start from sending PCM; teardown may run off the UI thread. */
    public void setCaptureAllowed(boolean allowed) {
        synchronized (captureStateLock) {
            captureAllowed = allowed;
            if (!allowed) {
                running = false;
                captureGeneration++;
            }
        }
    }

    public synchronized void stop() {
        AudioRecord recordToRelease;
        Thread threadToJoin;
        boolean wasStreaming;

        synchronized (captureStateLock) {
            running = false;
            captureGeneration++;
        }
        recordToRelease = audioRecord;
        threadToJoin = captureThread;
        wasStreaming = streamingToHost;

        if (recordToRelease != null) {
            try {
                recordToRelease.stop();
            }
            catch (IllegalStateException ignored) {
            }
        }

        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(2000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        captureThread = null;
        audioRecord = null;
        // A blocked device read can outlive the bounded join. The worker owns
        // its recorder and releases it only after read() has returned.
        if (recordToRelease != null && threadToJoin == null) {
            recordToRelease.release();
        }

        if (wasStreaming) {
            MoonBridge.stopMicrophoneStreaming();
            MoonBridge.cleanupMicrophoneEncoder();
        }
        streamingToHost = false;

        dispatchStatus(string(R.string.microphone_preview_inactive), 0.0, false);
    }

    private synchronized boolean startCapture(int preferredDeviceId, LevelListener listener, boolean streamToHost) {
        CaptureConfig config;
        AudioRecord newRecord;
        final int bufferSamples;

        stop();
        levelListener = listener;

        synchronized (captureStateLock) {
            if (!captureAllowed) return false;
        }

        if (!hasRecordAudioPermission(context)) {
            dispatchStatus(string(R.string.microphone_preview_permission_required), 0.0, false);
            return false;
        }

        if (streamToHost && !MoonBridge.isMicrophoneStreamActive()) {
            dispatchStatus(string(R.string.microphone_host_not_negotiated), 0.0, false);
            return false;
        }

        try {
            config = createCaptureConfig(preferredDeviceId);
        }
        catch (SecurityException e) {
            dispatchStatus(string(R.string.microphone_preview_permission_required), 0.0, false);
            return false;
        }
        if (config == null) {
            dispatchStatus(string(R.string.microphone_preview_open_failed), 0.0, false);
            return false;
        }

        newRecord = config.record;
        bufferSamples = config.bufferSamples;

        if (streamToHost && MoonBridge.setupMicrophoneEncoder(SAMPLE_RATE, CHANNEL_COUNT, DEFAULT_BITRATE) != 0) {
            newRecord.release();
            dispatchStatus(string(R.string.microphone_encoder_setup_failed), 0.0, false);
            return false;
        }

        try {
            newRecord.startRecording();
        }
        catch (IllegalStateException | SecurityException e) {
            if (streamToHost) {
                MoonBridge.cleanupMicrophoneEncoder();
            }
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        if (newRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            if (streamToHost) {
                MoonBridge.cleanupMicrophoneEncoder();
            }
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        LimeLog.info(String.format((Locale) null,
                "Microphone capture active using source %s, device %s, buffer %d samples",
                config.sourceName,
                config.deviceLabel,
                bufferSamples));

        final long generation;
        final boolean mayStart;
        synchronized (captureStateLock) {
            mayStart = captureAllowed;
            generation = captureGeneration;
            if (mayStart) {
                audioRecord = newRecord;
                streamingToHost = streamToHost;
                running = true;
                if (streamToHost) MoonBridge.startMicrophoneStreaming();
            }
        }
        if (!mayStart) {
            // A delayed device start may finish after the screen was hidden. Device
            // teardown can block or throw; it must not hold the UI's capture gate.
            try {
                newRecord.stop();
            } catch (IllegalStateException | SecurityException ignored) {
            } finally {
                try {
                    newRecord.release();
                } finally {
                    if (streamToHost) MoonBridge.cleanupMicrophoneEncoder();
                }
            }
            return false;
        }

        captureThread = new Thread(() -> {
            try {
                runCaptureLoop(newRecord, bufferSamples, streamToHost, generation, config.statusMessage);
            }
            finally {
                newRecord.release();
            }
        }, streamToHost ? "MicStreamCapture" : "MicPreviewCapture");
        captureThread.start();
        dispatchStatus(config.statusMessage, 0.0, false);
        return true;
    }

    private void runCaptureLoop(AudioRecord record, int bufferSamples, boolean streamToHost,
                                long generation, String status) {
        short[] readBuffer = new short[bufferSamples];
        int pendingPeak = 0;
        double pendingRms = 0.0;
        long lastUpdateTime = SystemClock.elapsedRealtime();
        double currentLevel = 0.0;

        while (running && generation == captureGeneration) {
            int samplesRead;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                samplesRead = record.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_BLOCKING);
            }
            else {
                samplesRead = record.read(readBuffer, 0, readBuffer.length);
            }

            if (samplesRead < 0) {
                failCaptureRead(generation, samplesRead);
                break;
            }
            if (samplesRead == 0) {
                continue;
            }

            SignalStats signalStats = calculateSignalStats(readBuffer, samplesRead);
            if (signalStats.peak > pendingPeak) {
                pendingPeak = signalStats.peak;
            }
            if (signalStats.rms > pendingRms) {
                pendingRms = signalStats.rms;
            }

            synchronized (captureStateLock) {
                // stop() invalidates the generation before native encoder
                // teardown. A delayed read must never queue into a new stream.
                if (!captureAllowed || !running || generation != captureGeneration) {
                    break;
                }
                if (streamToHost) {
                    int queued = MoonBridge.queueMicrophonePcm(readBuffer, samplesRead);
                    if (queued < 0) {
                        LimeLog.warning("Failed to queue microphone PCM data for native encoding");
                    }
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdateTime >= LEVEL_UPDATE_INTERVAL_MS) {
                    double instantaneousLevel = calculatePreviewLevel(pendingPeak, pendingRms);
                    double nextLevel = Math.max(instantaneousLevel, currentLevel * PREVIEW_DECAY_FACTOR);
                    boolean nextSignalDetected = pendingPeak >= SIGNAL_PEAK_THRESHOLD ||
                            pendingRms >= SIGNAL_RMS_THRESHOLD;
                    pendingPeak = 0;
                    pendingRms = 0.0;
                    lastUpdateTime = now;
                    currentLevel = nextLevel;
                    dispatchStatus(status, nextLevel, nextSignalDetected);
                }
            }
        }
    }

    private synchronized void failCaptureRead(long generation, int error) {
        if (!running || generation != captureGeneration) {
            return;
        }
        LimeLog.warning("Microphone capture read failed with AudioRecord error " + error);
        // A permanent AudioRecord error requires recreation, not a busy retry
        // on the same recorder. stop() also tears down the native encoder.
        stop();
        dispatchStatus(string(R.string.microphone_capture_read_failed), 0.0, false);
    }

    private CaptureConfig createCaptureConfig(int preferredDeviceId) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean supportsUnprocessed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && audioManager != null &&
                "true".equals(audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] preferredSources = new int[] {
                supportsUnprocessed ? MediaRecorder.AudioSource.UNPROCESSED : -1,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        int minBufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSizeBytes <= 0) {
            minBufferSizeBytes = FRAME_SIZE * 4 * 2;
        }
        int bufferSizeBytes = Math.max(minBufferSizeBytes, FRAME_SIZE * 4 * 2);
        int bufferSamples = Math.max(FRAME_SIZE, bufferSizeBytes / 2);
        boolean missingSelectedDevice = false;
        AudioDeviceInfo preferredDevice = null;
        String preferredDeviceLabel = string(R.string.microphone_device_default);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDeviceId != 0) {
            preferredDevice = findInputDevice(preferredDeviceId);
            if (preferredDevice != null) {
                preferredDeviceLabel = describeDevice(preferredDevice);
            }
            else {
                missingSelectedDevice = true;
            }
        }

        for (int source : preferredSources) {
            AudioRecord candidate = null;

            if (source < 0) {
                continue;
            }

            try {
                candidate = buildAudioRecord(source, bufferSizeBytes);
                if (candidate == null) {
                    continue;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDevice != null) {
                    boolean preferredApplied = candidate.setPreferredDevice(preferredDevice);
                    if (!preferredApplied) {
                        LimeLog.info("Preferred microphone device selection was rejected by AudioRecord");
                    }
                }

                if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                    continue;
                }

                CaptureConfig config = new CaptureConfig();
                config.record = candidate;
                config.bufferSamples = bufferSamples;
                config.sourceName = audioSourceToString(source);
                config.deviceLabel = preferredDevice != null ? preferredDeviceLabel : string(R.string.microphone_device_default);
                config.statusMessage = missingSelectedDevice ?
                        string(R.string.microphone_preview_selected_missing) :
                        (preferredDevice != null ?
                                string(R.string.microphone_preview_selected_active) :
                                string(R.string.microphone_preview_default_active));
                candidate = null; // Ownership transfers to the capture worker.
                return config;
            }
            catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                LimeLog.warning("Microphone source " + audioSourceToString(source) +
                        " unavailable: " + e.getClass().getSimpleName());
            }
            finally {
                if (candidate != null) {
                    candidate.release();
                }
            }
        }

        return null;
    }

    AudioRecord buildAudioRecord(int audioSource, int bufferSizeBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .build();
        }

        return new AudioRecord(audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes);
    }

    private AudioDeviceInfo findInputDevice(int deviceId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (deviceInfo.getId() == deviceId) {
                return deviceInfo;
            }
        }

        return null;
    }

    private void dispatchStatus(String status, double level, boolean detected) {
        final long generation = captureGeneration;
        final LevelListener listener = levelListener;
        mainHandler.post(() -> {
            if (listener != null && listener == levelListener && generation == captureGeneration) {
                listener.onLevelUpdate(level, detected, status);
            }
        });
    }

    private String string(int resId) {
        return context.getString(resId);
    }

    private static SignalStats calculateSignalStats(short[] samples, int sampleCount) {
        int peak = 0;
        double sumSquares = 0.0;

        for (int i = 0; i < sampleCount; i++) {
            int sample = Math.abs(samples[i]);
            if (sample > peak) {
                peak = sample;
            }
            sumSquares += (double) sample * sample;
        }

        SignalStats signalStats = new SignalStats();
        signalStats.peak = peak;
        signalStats.rms = sampleCount > 0 ? Math.sqrt(sumSquares / sampleCount) : 0.0;
        return signalStats;
    }

    private static double calculatePreviewLevel(int peak, double rms) {
        double rmsLevel = (Math.log10(Math.max(rms, PREVIEW_RMS_FLOOR)) -
                Math.log10(PREVIEW_RMS_FLOOR)) /
                (Math.log10(PREVIEW_RMS_CEILING) - Math.log10(PREVIEW_RMS_FLOOR));
        double peakLevel = Math.min(1.0, peak / PREVIEW_PEAK_CEILING);
        return Math.max(0.0, Math.min(1.0, Math.max(rmsLevel, peakLevel)));
    }

    private static String audioSourceToString(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                audioSource == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED";
        }
        return "MIC";
    }

    private static String describeDevice(AudioDeviceInfo deviceInfo) {
        switch (deviceInfo.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio input";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset microphone";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB microphone";
            default:
                CharSequence productName = deviceInfo.getProductName();
                if (productName != null && productName.length() > 0) {
                    return productName.toString();
                }
                return "Input device " + deviceInfo.getId();
        }
    }

    private static final class SignalStats {
        int peak;
        double rms;
    }

    private static final class CaptureConfig {
        AudioRecord record;
        int bufferSamples;
        String sourceName;
        String deviceLabel;
        String statusMessage;
    }
}
