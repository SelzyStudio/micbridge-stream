package com.limelight.shadows;

import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = com.limelight.nvstream.jni.MoonBridge.class, isInAndroidSdk = false)
public class ShadowMoonBridge {
    public static boolean lastStartConnectionEnableMicrophone;
    public static final java.util.List<String> keyboardRequests = new java.util.ArrayList<>();
    public static int backgroundResult;
    public static final java.util.List<Boolean> backgroundRequests = new java.util.ArrayList<>();
    public static int stopConnectionCount;
    public static int interruptConnectionCount;
    public static int prepareConnectionStartCount;
    public static int stopConnectionForReconnectCount;
    public static int interruptConnectionForReconnectCount;
    public static boolean recoveryCloseRequested;
    public static boolean lastOrdinaryStopUsedRecoveryClose;
    public static java.util.concurrent.CountDownLatch setupEntered;
    public static java.util.concurrent.CountDownLatch setupRelease;
    public static java.util.concurrent.CountDownLatch recoveryInterruptEntered;
    public static int startConnectionResult;
    public static java.util.concurrent.CountDownLatch startEntered;
    public static java.util.concurrent.CountDownLatch startRelease;
    public static boolean microphoneStreamActive;
    public static boolean microphoneEncryptionEnabled;
    public static int setupMicrophoneEncoderResult;
    public static int lastQueuedMicrophoneSampleCount;
    public static boolean microphoneStreamingStarted;

    // Static initializer override to prevent System.loadLibrary
    @Implementation
    protected static void __staticInitializer__() {
        // no-op
    }

    // Provide minimal nested AudioConfiguration
    public static class AudioConfiguration {
        public final int channelCount;
        public final int channelMask;
        public AudioConfiguration(int c, int m) {
            this.channelCount = c; this.channelMask = m;
        }
        public int toInt() { return 0; }
    }

    // Define constants minimally needed by code under test
    public static final AudioConfiguration AUDIO_CONFIGURATION_STEREO = new AudioConfiguration(2, 0x3);
    public static final AudioConfiguration AUDIO_CONFIGURATION_51_SURROUND = new AudioConfiguration(6, 0x3F);
    public static final AudioConfiguration AUDIO_CONFIGURATION_71_SURROUND = new AudioConfiguration(8, 0x63F);

    public static final int DR_OK = 0;

    public static int CAPABILITY_SLICES_PER_FRAME(byte s) { return 0; }

    public static int getPendingAudioDuration() { return 0; }

    // stubbed methods used by code but not relevant to unit tests
    public static void reset() {
        lastStartConnectionEnableMicrophone = false;
        microphoneStreamActive = false;
        microphoneEncryptionEnabled = false;
        setupMicrophoneEncoderResult = 0;
        lastQueuedMicrophoneSampleCount = 0;
        microphoneStreamingStarted = false;
        backgroundResult = 0;
        backgroundRequests.clear();
        keyboardRequests.clear();
        stopConnectionCount = 0;
        interruptConnectionCount = 0;
        prepareConnectionStartCount = stopConnectionForReconnectCount = interruptConnectionForReconnectCount = 0;
        recoveryCloseRequested = lastOrdinaryStopUsedRecoveryClose = false;
        setupEntered = setupRelease = recoveryInterruptEntered = null;
        startConnectionResult = 0;
        startEntered = startRelease = null;
    }

    @Implementation
    protected static void init() {
    }

    @Implementation
    protected static void setupBridge(VideoDecoderRenderer videoRenderer,
                                      AudioRenderer audioRenderer,
                                      NvConnectionListener connectionListener) {
        if (setupEntered != null) {
            setupEntered.countDown();
            try {
                if (!setupRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("Test bridge setup was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    public static void cleanupBridge() {}

    @Implementation
    protected static int startConnection(String address, String appVersion, String gfeVersion,
                                         String rtspSessionUrl, int serverCodecModeSupport,
                                         int width, int height, int fps,
                                         int bitrate, int packetSize, int streamingRemotely,
                                         int audioConfiguration, int supportedVideoFormats,
                                         int clientRefreshRateX100,
                                         byte[] riAesKey, byte[] riAesIv,
                                         int videoCapabilities,
                                         int colorSpace, int colorRange,
                                         boolean enableMicrophone) {
        lastStartConnectionEnableMicrophone = enableMicrophone;
        if (startEntered != null) {
            startEntered.countDown();
            try {
                if (!startRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("Test handshake was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        return startConnectionResult;
    }

    @Implementation
    protected static void stopConnection() {
        stopConnectionCount++;
        lastOrdinaryStopUsedRecoveryClose = recoveryCloseRequested;
    }

    @Implementation protected static void prepareConnectionStart() {
        prepareConnectionStartCount++;
        recoveryCloseRequested = false;
    }

    @Implementation protected static void stopConnectionForReconnect() {
        stopConnectionForReconnectCount++;
        recoveryCloseRequested = true;
    }

    @Implementation protected static void interruptConnectionForReconnect() {
        interruptConnectionForReconnectCount++;
        recoveryCloseRequested = true;
        if (recoveryInterruptEntered != null) recoveryInterruptEntered.countDown();
    }

    @Implementation
    protected static void interruptConnection() {
        interruptConnectionCount++;
    }

    @Implementation
    protected static void sendKeyboardInput(short keyMap, byte direction, byte modifier, byte flags) {
        if (!Thread.holdsLock(com.limelight.nvstream.jni.MoonBridge.class)) {
            throw new AssertionError("Keyboard bridge call escaped the native lifetime lock");
        }
        keyboardRequests.add(keyMap + ":" + direction + ":" + modifier + ":" + flags);
    }

    @Implementation
    protected static int setStreamBackground(boolean background) {
        if (!Thread.holdsLock(com.limelight.nvstream.jni.MoonBridge.class)) {
            throw new AssertionError("Background bridge call escaped the native lifetime lock");
        }
        backgroundRequests.add(background);
        return backgroundResult;
    }

    @Implementation
    protected static boolean isMicrophoneStreamActive() {
        return microphoneStreamActive;
    }

    @Implementation
    protected static boolean isMicrophoneEncryptionEnabled() {
        return microphoneEncryptionEnabled;
    }

    @Implementation
    protected static int setupMicrophoneEncoder(int sampleRate, int channelCount, int bitrate) {
        return setupMicrophoneEncoderResult;
    }

    @Implementation
    protected static void startMicrophoneStreaming() {
        microphoneStreamingStarted = true;
    }

    @Implementation
    protected static void stopMicrophoneStreaming() {
        microphoneStreamingStarted = false;
    }

    @Implementation
    protected static void cleanupMicrophoneEncoder() {
    }

    @Implementation
    protected static int queueMicrophonePcm(short[] pcmData, int sampleCount) {
        lastQueuedMicrophoneSampleCount = sampleCount;
        return sampleCount;
    }
}
