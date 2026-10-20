package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.SeekBar;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.R;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class})
public class PreferenceConfigurationTest {
    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        context.getSharedPreferences(PreferenceManager.KEY_HAS_SET_DEFAULT_VALUES, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void freshInstallUsesHalfTrackpadSensitivityBeforeOpeningSettings() {
        assertFalse(prefs.contains("seekbar_trackpad_sensitivity_x"));
        assertFalse(prefs.contains("seekbar_trackpad_sensitivity_y"));

        assertTrackpadSensitivity(50, 50);
    }

    @Test
    public void realPreferenceInitializationAndSlidersUseHalfTrackpadSensitivity() {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, false);

        assertTrackpadSensitivity(50, 50);
        assertTrackpadSliderValues(50, 50);
    }

    @Test
    public void storedTrackpadSensitivitySurvivesDefaultInitialization() {
        prefs.edit().putInt("seekbar_trackpad_sensitivity_x", 75)
                .putInt("seekbar_trackpad_sensitivity_y", 125).commit();
        assertTrackpadSensitivity(75, 125);

        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);

        assertEquals(75, prefs.getInt("seekbar_trackpad_sensitivity_x", -1));
        assertEquals(125, prefs.getInt("seekbar_trackpad_sensitivity_y", -1));
        assertTrackpadSensitivity(75, 125);
    }

    private void assertTrackpadSensitivity(int x, int y) {
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);
        assertEquals(x, config.trackpadSensitivityX);
        assertEquals(y, config.trackpadSensitivityY);
    }

    private void assertTrackpadSliderValues(int x, int y) {
        Context themedContext = new ContextThemeWrapper(context, R.style.SettingsTheme);
        PreferenceManager manager = new PreferenceManager(themedContext);
        PreferenceScreen screen = manager.inflateFromResource(themedContext, R.xml.preferences, null);
        assertTrackpadSliderValue(screen, "seekbar_trackpad_sensitivity_x", x);
        assertTrackpadSliderValue(screen, "seekbar_trackpad_sensitivity_y", y);
    }

    private void assertTrackpadSliderValue(PreferenceScreen screen, String key, int expected) {
        SeekBarPreference preference = screen.findPreference(key);
        assertNotNull(preference);
        // Build the actual slider: readPreferences' fallback alone would not
        // detect an XML default that disagrees with the fresh-install default.
        preference.getDialog();
        SeekBar slider = ReflectionHelpers.getField(preference, "seekBar");
        int minimum = ReflectionHelpers.getField(preference, "minValue");
        assertEquals(expected, slider.getProgress() + minimum);
    }

    @Test
    public void fiveFingerTapDefaultsToModeToggleAndAcceptsOtherActions() {
        assertEquals(PreferenceConfiguration.FIVE_FINGER_ACTION_TOGGLE,
                PreferenceConfiguration.readPreferences(context, prefs).fiveFingerTapAction);
        for (String action : new String[] {"quick_menu", "off", "toggle_mode"}) {
            prefs.edit().putString("five_finger_tap_action", action).commit();
            assertEquals(action, PreferenceConfiguration.readPreferences(context, prefs).fiveFingerTapAction);
        }
        prefs.edit().putString("five_finger_tap_action", "invalid").commit();
        assertEquals(PreferenceConfiguration.FIVE_FINGER_ACTION_TOGGLE,
                PreferenceConfiguration.readPreferences(context, prefs).fiveFingerTapAction);
    }

    @Test
    public void readPreferencesLoadsMicrophoneSettings() {
        prefs.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_MICROPHONE_PREF_STRING, true)
                .putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "7")
                .commit();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);

        assertTrue(config.enableMicrophone);
        assertEquals(7, config.microphoneDeviceId);
    }

    @Test
    public void readPreferencesResetsInvalidMicrophoneDeviceSelection() {
        prefs.edit()
                .putString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, "invalid")
                .commit();

        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context, prefs);

        assertEquals(0, config.microphoneDeviceId);
        assertEquals("0", prefs.getString(PreferenceConfiguration.MICROPHONE_DEVICE_PREF_STRING, null));
    }
}
