package com.limelight.binding.audio;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.app.Application;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.shadows.ShadowMoonBridge;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = ShadowMoonBridge.class)
public class MicrophoneCaptureManagerTest {
    private Context context;
    private AudioManager audioManager;

    @Before
    public void setUp() {
        Context application = ApplicationProvider.getApplicationContext();
        audioManager = mock(AudioManager.class);
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(new AudioDeviceInfo[0]);
        when(audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)).thenReturn("true");
        context = new ContextWrapper(application) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return AUDIO_SERVICE.equals(name) ? audioManager : super.getSystemService(name);
            }
        };
        ShadowMoonBridge.reset();
    }

    @Test
    public void unsupportedSourceBuildsFallBackToMic() {
        AudioRecord workingRecord = initializedRecord();
        List<Integer> attempts = new ArrayList<>();
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) {
                attempts.add(source);
                if (source == MediaRecorder.AudioSource.UNPROCESSED) {
                    throw new UnsupportedOperationException("device does not support raw input");
                }
                if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                    throw new IllegalArgumentException("source not available");
                }
                return workingRecord;
            }
        };

        assertNotNull(createConfig(manager));
        assertEquals(Arrays.asList(MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC), attempts);
        verify(workingRecord, never()).release();
    }

    @Test
    public void unprocessedSourceRequiresDeviceSupport() {
        when(audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)).thenReturn(null);
        List<Integer> attempts = new ArrayList<>();
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) {
                attempts.add(source);
                return initializedRecord();
            }
        };

        assertNotNull(createConfig(manager));
        assertEquals(Arrays.asList(MediaRecorder.AudioSource.VOICE_RECOGNITION), attempts);
    }

    @Test
    public void failedInitializationReleasesCandidateAndTriesNextSource() {
        AudioRecord broken = mock(AudioRecord.class);
        AudioRecord working = initializedRecord();
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) {
                return source == MediaRecorder.AudioSource.UNPROCESSED ? broken : working;
            }
        };

        assertNotNull(createConfig(manager));
        verify(broken).release();
        verify(working, never()).release();
    }

    @Test
    public void unavailableSourcesReturnFailureInsteadOfThrowing() {
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) {
                throw new IllegalStateException("recorder temporarily unavailable");
            }
        };
        assertNull(createConfig(manager));
    }

    @Test
    public void sameTypeInputsRemainIndividuallySelectable() {
        AudioDeviceInfo first = inputDevice(10, "USB microphone A");
        AudioDeviceInfo second = inputDevice(11, "USB microphone B");
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                .thenReturn(new AudioDeviceInfo[] {first, second, first});

        List<MicrophoneCaptureManager.InputDeviceEntry> entries =
                MicrophoneCaptureManager.getAvailableInputDevices(context);

        assertEquals(2, entries.size());
        assertEquals(10, entries.get(0).id);
        assertEquals(11, entries.get(1).id);
        assertNotEquals(entries.get(0).label, entries.get(1).label);
    }

    @Test
    public void stoppedWorkerRetainsItsRecorderUntilBlockedReadExits() throws Exception {
        Shadows.shadowOf((Application) ApplicationProvider.getApplicationContext())
                .grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        AudioRecord record = initializedRecord();
        when(record.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        when(record.read(any(short[].class), anyInt(), anyInt(), eq(AudioRecord.READ_BLOCKING)))
                .thenAnswer(invocation -> {
                    reading.countDown();
                    if (!finishRead.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("test failed to unblock microphone read");
                    }
                    return 960;
                });
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) { return record; }
        };

        try {
            assertTrue(manager.startPreview(0, null));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            manager.stop();
            // A device that ignores stop() can outlive the bounded join. The
            // capture worker must own the recorder until its read has returned.
            verify(record, never()).release();
        }
        finally {
            finishRead.countDown();
            manager.stop();
        }
        verify(record, timeout(5000)).release();
        verify(record, times(1)).read(any(short[].class), anyInt(), anyInt(), eq(AudioRecord.READ_BLOCKING));
    }

    @Test
    public void terminalReadFailureStopsCaptureAndNativeStreaming() {
        Shadows.shadowOf((Application) ApplicationProvider.getApplicationContext())
                .grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        ShadowMoonBridge.microphoneStreamActive = true;
        AudioRecord record = initializedRecord();
        when(record.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
        when(record.read(any(short[].class), anyInt(), anyInt(), eq(AudioRecord.READ_BLOCKING)))
                .thenAnswer(invocation -> {
                    // Keep a broken implementation's retry loop bounded so
                    // Mockito does not allocate millions of invocations.
                    Thread.sleep(10);
                    return AudioRecord.ERROR_DEAD_OBJECT;
                });
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) { return record; }
        };

        try {
            assertTrue(manager.startStreaming(0, null));
            verify(record, timeout(5000)).stop();
            verify(record, timeout(5000)).release();
            assertFalse(ShadowMoonBridge.microphoneStreamingStarted);
            verify(record, times(1)).read(any(short[].class), anyInt(), anyInt(), eq(AudioRecord.READ_BLOCKING));
        }
        finally {
            manager.stop();
        }
    }

    @Test
    public void captureGateDropsPcmFromAReadThatReturnsAfterBackgrounding() throws Exception {
        Shadows.shadowOf((Application) ApplicationProvider.getApplicationContext())
                .grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        ShadowMoonBridge.microphoneStreamActive = true;
        AudioRecord record = initializedRecord();
        when(record.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishRead = new CountDownLatch(1);
        when(record.read(any(short[].class), anyInt(), anyInt(), eq(AudioRecord.READ_BLOCKING)))
                .thenAnswer(invocation -> {
                    reading.countDown();
                    if (!finishRead.await(5, TimeUnit.SECONDS)) throw new AssertionError("read gate timed out");
                    short[] samples = invocation.getArgument(0);
                    Arrays.fill(samples, (short) 1000);
                    return Math.min(960, samples.length);
                });
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) { return record; }
        };
        try {
            assertTrue(manager.startStreaming(0, null));
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            assertTrue(ShadowMoonBridge.microphoneStreamingStarted);
            manager.setCaptureAllowed(false); // No stop()/join is needed to close the PCM gate.
            finishRead.countDown();
            verify(record, timeout(5000)).release();
            assertEquals(0, ShadowMoonBridge.lastQueuedMicrophoneSampleCount);
            verify(record, times(1)).read(any(short[].class), anyInt(), anyInt(), eq(AudioRecord.READ_BLOCKING));
        } finally {
            finishRead.countDown();
            manager.stop();
        }
    }

    @Test
    public void captureGateRejectsADelayedDeviceStartWithoutStartingTheEncoderWorker() throws Exception {
        verifyDelayedStartIsRejected(false);
    }

    @Test
    public void rejectedDelayedStartStillReleasesTheRecorderIfDeviceStopThrows() throws Exception {
        verifyDelayedStartIsRejected(true);
    }

    private void verifyDelayedStartIsRejected(boolean stopThrows) throws Exception {
        Shadows.shadowOf((Application) ApplicationProvider.getApplicationContext())
                .grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        ShadowMoonBridge.microphoneStreamActive = true;
        AudioRecord record = initializedRecord();
        when(record.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
        if (stopThrows) doThrow(new IllegalStateException("device disappeared")).when(record).stop();
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            if (!finishStart.await(5, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
            return null;
        }).when(record).startRecording();
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bufferSizeBytes) { return record; }
        };
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try { result.set(manager.startStreaming(0, null)); }
            catch (Throwable error) { failure.set(error); }
        });
        starter.start();
        try {
            assertTrue(starting.await(5, TimeUnit.SECONDS));
            manager.setCaptureAllowed(false);
        } finally {
            finishStart.countDown();
            starter.join(5000);
            manager.stop();
        }
        assertFalse(starter.isAlive());
        assertNull(failure.get());
        assertEquals(Boolean.FALSE, result.get());
        assertFalse(ShadowMoonBridge.microphoneStreamingStarted);
        assertEquals(0, ShadowMoonBridge.lastQueuedMicrophoneSampleCount);
        verify(record).stop();
        verify(record).release();
        verify(record, never()).read(any(short[].class), anyInt(), anyInt(), anyInt());
    }

    private static Object createConfig(MicrophoneCaptureManager manager) {
        return ReflectionHelpers.callInstanceMethod(manager, "createCaptureConfig",
                ReflectionHelpers.ClassParameter.from(int.class, 0));
    }

    private static AudioRecord initializedRecord() {
        AudioRecord record = mock(AudioRecord.class);
        when(record.getState()).thenReturn(AudioRecord.STATE_INITIALIZED);
        return record;
    }

    private static AudioDeviceInfo inputDevice(int id, String productName) {
        AudioDeviceInfo device = mock(AudioDeviceInfo.class);
        when(device.getId()).thenReturn(id);
        when(device.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_DEVICE);
        when(device.getProductName()).thenReturn(productName);
        return device;
    }
}
