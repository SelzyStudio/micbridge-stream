package com.limelight.binding.audio;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28, 35}, shadows = ShadowMoonBridge.class)
public class MicrophoneApiCompatibilityTest {
    private Application application;
    private Context context;
    private AudioManager audioManager;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO);
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
    public void realRecorderBuilderUsesCompatiblePcmConfiguration() {
        // Exercise the actual API-dependent production builder, with Android
        // hardware provided by Robolectric rather than an overridden builder.
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context);
        AudioRecord record = manager.buildAudioRecord(MediaRecorder.AudioSource.MIC, 7680);
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, record.getState());
            assertEquals(48000, record.getSampleRate());
            assertEquals(1, record.getChannelCount());
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, record.getAudioFormat());
        }
        finally {
            record.release();
        }
    }

    @Test
    public void unavailableSourcesFallBackWithoutCallingNewerApiOnMarshmallow() {
        List<Integer> attempts = new ArrayList<>();
        AudioRecord working = mock(AudioRecord.class);
        when(working.getState()).thenReturn(AudioRecord.STATE_INITIALIZED);
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bytes) {
                attempts.add(source);
                if (source != MediaRecorder.AudioSource.MIC) {
                    throw new UnsupportedOperationException("device-specific source unavailable");
                }
                return working;
            }
        };

        assertNotNull(ReflectionHelpers.callInstanceMethod(manager, "createCaptureConfig",
                ReflectionHelpers.ClassParameter.from(int.class, 0)));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            assertEquals(Arrays.asList(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC), attempts);
            verify(audioManager, never()).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
        }
        else {
            assertEquals(Arrays.asList(MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC), attempts);
        }
    }

    @Test
    public void deniedPermissionStopsBeforeOpeningAnyRecorder() {
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO);
        MicrophoneCaptureManager manager = new MicrophoneCaptureManager(context) {
            @Override AudioRecord buildAudioRecord(int source, int bytes) {
                fail("A denied microphone permission must not open AudioRecord");
                return null;
            }
        };
        assertFalse(manager.startPreview(0, null));
    }
}
