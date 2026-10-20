package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.StreamContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, shadows = ShadowMoonBridge.class)
public class GameFiveFingerGestureTest {
    public static class TestGame extends Game {
        int keyboardCount, fullKeyboardCount, menuCount;
        @Override public void toggleKeyboard() { keyboardCount++; }
        @Override public void toggleFullKeyboard() { fullKeyboardCount++; }
        @Override public void showGameMenu(GameInputDevice device) { menuCount++; }
    }

    private TestGame game;
    private PreferenceConfiguration config;
    private NvConnection connection;
    private StreamContainer stream;
    private TouchContext[] contexts;
    private long time;
    private long downTime;

    @Before public void setUp() {
        game = Robolectric.buildActivity(TestGame.class).get();
        config = new PreferenceConfiguration();
        config.enableMultiTouchScreen = true;
        config.enableBackMenu = true;
        config.trackpadSensitivityX = config.trackpadSensitivityY = 100;
        ReflectionHelpers.setField(game, "prefConfig", config);
        connection = mock(NvConnection.class);
        game.conn = connection;
        stream = new StreamContainer(game, null);
        stream.layout(0, 0, 1280, 720);
        stream.setOnTouchListener(game);
        ReflectionHelpers.setField(game, "streamContainer", stream);
        contexts = ReflectionHelpers.getField(game, "touchContextMap");
        contexts[0] = mock(TouchContext.class);
        contexts[1] = mock(TouchContext.class);
        ReflectionHelpers.setStaticField(ProfilesManager.class, "instance", null);
        preferences().edit().clear().commit();
        time = 1000;
    }

    @Test public void fiveFingerChordCancelsHostAndConsumesEntireTailBeforeSwitching() {
        TouchContext first = contexts[0];
        TouchContext second = contexts[1];
        config.enableTouchSensitivity = true;
        config.touchSensitivityGlobal = true;
        config.touchSensitivityX = config.touchSensitivityY = 150;
        press(4);
        event(MotionEvent.ACTION_MOVE, 4, 0);
        java.util.Map<String, SensitivityBean> sensitivity = ReflectionHelpers.getField(game, "sensitivityMap");
        assertFalse(sensitivity.isEmpty());
        clearInvocations(connection, first, second);
        event(MotionEvent.ACTION_POINTER_DOWN, 5, 4);
        assertTrue(config.enableMultiTouchScreen);
        assertTrue(sensitivity.isEmpty());
        verify(connection).sendTouchEvent(eq(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL), eq(0),
                eq(0f), eq(0f), eq(0f), eq(0f), eq(0f), eq(MoonBridge.LI_ROT_UNKNOWN));
        verify(first).cancelTouch();
        verify(second).cancelTouch();
        verify(first).setPointerCount(0);
        release(5);
        assertFalse(config.enableMultiTouchScreen);
        assertEquals(2, mode());
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains(
                game.getString(R.string.mouse_mode_track_pad_natural)));
        assertEquals(0, game.keyboardCount + game.fullKeyboardCount + game.menuCount);
        verifyNoMoreInteractions(connection);
        verify(first, never()).touchUpEvent(anyInt(), anyInt(), anyLong());
        verify(second, never()).touchUpEvent(anyInt(), anyInt(), anyLong());
    }

    @Test public void returnsToEachPreviouslySelectedMouseVariant() {
        for (int mouseMode : new int[] {1, 2, 3, 5}) {
            applyMode(mouseMode);
            tap(5);
            assertEquals(0, mode());
            assertTrue(config.enableMultiTouchScreen);
            tap(5);
            assertEquals(mouseMode, mode());
            assertFalse(config.enableMultiTouchScreen);
            assertEquals(mouseMode == 2 || mouseMode == 3, config.touchscreenTrackpad);
        }
    }

    @Test public void cancelledFiveFingerChordDoesNotSwitchAndNextTapStillWorks() {
        press(5);
        event(MotionEvent.ACTION_CANCEL, 5, 0);
        assertEquals(0, mode());
        assertEquals(0, game.keyboardCount + game.fullKeyboardCount + game.menuCount);
        tap(5);
        assertEquals(2, mode());
    }

    @Test public void quickMenuOptionWaitsForAllFingersAndDoesNotChangeMode() {
        config.fiveFingerTapAction = PreferenceConfiguration.FIVE_FINGER_ACTION_MENU;
        press(5);
        event(MotionEvent.ACTION_POINTER_UP, 5, 4);
        assertEquals(0, game.menuCount);
        release(4);
        assertEquals(1, game.menuCount);
        assertEquals(0, mode());
        assertEquals(0, game.keyboardCount + game.fullKeyboardCount);
    }

    @Test public void offOptionForwardsOrdinaryMultiTouch() {
        config.fiveFingerTapAction = PreferenceConfiguration.FIVE_FINGER_ACTION_OFF;
        tap(5);
        assertEquals(0, mode());
        verify(connection, times(10)).sendTouchEvent(anyByte(), anyInt(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyShort());
        verify(connection, never()).sendTouchEvent(eq(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL),
                anyInt(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyShort());
    }

    @Test public void threeAndFourFingerKeyboardGesturesArePreserved() {
        config.enableMultiTouchGestures = true;
        tap(3);
        assertEquals(1, game.keyboardCount);
        assertEquals(0, game.fullKeyboardCount);
        tap(4);
        assertEquals(1, game.keyboardCount);
        assertEquals(1, game.fullKeyboardCount);
        assertEquals(0, mode());
    }

    @Test public void externalDisplayAndInputOnlyRestrictionsArePreserved() {
        applyMode(2);
        ReflectionHelpers.setField(game, "onExternelDisplay", true);
        tap(5);
        assertEquals(2, mode());
        ReflectionHelpers.setField(game, "onExternelDisplay", false);
        game.allowChangeMouseMode = false;
        tap(5);
        assertEquals(2, mode());
        assertEquals(game.getString(R.string.five_finger_mode_unavailable),
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void rememberingModeIsOptInAndKeepsTheSelectedMouseVariant() {
        preferences().edit().putString("mouse_mode_list", "0").commit();
        tap(5);
        assertEquals("0", preferences().getString("mouse_mode_list", null));
        config.rememberMouseMode = true;
        applyMode(3);
        tap(5);
        assertEquals("0", preferences().getString("mouse_mode_list", null));
        assertEquals(3, preferences().getInt("five_finger_last_mouse_mode", -1));
        tap(5);
        assertEquals("3", preferences().getString("mouse_mode_list", null));
    }

    @Test public void disabledTouchModeStaysDisabled() {
        applyMode(4);
        clearInvocations(connection);
        tap(5);
        assertEquals(4, mode());
        verifyNoInteractions(connection);
    }

    @Test public void mixedStylusTailIsConsumedBeforePenAndMouseRouting() {
        press(5);
        clearInvocations(connection);
        eventWithIds(MotionEvent.ACTION_POINTER_DOWN, 5, new int[] {7, 8, 9, 10, 11, 12}, 12);
        for (int first = 7; first <= 11; first++) {
            int[] ids = new int[13 - first];
            for (int i = 0; i < ids.length; i++) ids[i] = first + i;
            eventWithIds(MotionEvent.ACTION_POINTER_UP, 0, ids, 12);
        }
        eventWithIds(MotionEvent.ACTION_UP, 0, new int[] {12}, 12);
        assertEquals(0, mode());
        assertEquals(0, game.keyboardCount + game.fullKeyboardCount + game.menuCount);
        verifyNoInteractions(connection);
        tap(5);
        assertEquals(2, mode());
    }

    @Test public void rememberedMouseVariantSurvivesStartingInTouchMode() {
        preferences().edit().putString("mouse_mode_list", "0")
                .putInt("five_finger_last_mouse_mode", 3).commit();
        ReflectionHelpers.callInstanceMethod(game, "initMouseMode");
        assertEquals(0, mode());
        tap(5);
        assertEquals(3, mode());
    }

    @Test public void defaultMouseModeMovesCursorRelativelyWithoutJumpingToFingerPosition() {
        tap(5);
        assertEquals(2, mode());
        clearInvocations(connection);
        downTime = time + 10;
        event(MotionEvent.ACTION_DOWN, 1, 0);
        verifyNoInteractions(connection);
        eventWithIds(MotionEvent.ACTION_MOVE, 0, new int[] {7}, -1, 64, 0);
        verify(connection).sendMouseMove((short) 64, (short) 0);
        verify(connection, never()).sendMousePosition(anyShort(), anyShort(), anyShort(), anyShort());
        event(MotionEvent.ACTION_CANCEL, 1, 0);
    }

    @Test public void screenTrackpadUsesTheConfiguredSensitivity() {
        config.trackpadSensitivityX = 50;
        config.trackpadSensitivityY = 75;
        tap(5);
        clearInvocations(connection);
        downTime = time + 10;
        event(MotionEvent.ACTION_DOWN, 1, 0);
        eventWithIds(MotionEvent.ACTION_MOVE, 0, new int[] {7}, -1, 64, 40);
        verify(connection).sendMouseMove((short) 32, (short) 30);
        verify(connection, never()).sendMousePosition(anyShort(), anyShort(), anyShort(), anyShort());
        event(MotionEvent.ACTION_CANCEL, 1, 0);
    }

    @Test public void freshInstallDefaultsSendHalfSpeedAfterFiveFingerSwitch() {
        config = PreferenceConfiguration.readPreferences(game, preferences());
        assertEquals(50, config.trackpadSensitivityX);
        assertEquals(50, config.trackpadSensitivityY);
        ReflectionHelpers.setField(game, "prefConfig", config);
        tap(5);
        assertEquals(2, mode());
        clearInvocations(connection);
        downTime = time + 10;
        event(MotionEvent.ACTION_DOWN, 1, 0);
        eventWithIds(MotionEvent.ACTION_MOVE, 0, new int[] {7}, -1, 64, 40);
        verify(connection).sendMouseMove((short) 32, (short) 20);
        verify(connection, never()).sendMousePosition(anyShort(), anyShort(), anyShort(), anyShort());
        event(MotionEvent.ACTION_CANCEL, 1, 0);
    }

    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(game);
    }
    private int mode() { return ReflectionHelpers.getField(game, "currentMouseMode"); }
    private void applyMode(int mode) {
        ReflectionHelpers.callInstanceMethod(game, "applyMouseMode",
                ReflectionHelpers.ClassParameter.from(int.class, mode));
    }
    private void tap(int count) { press(count); release(count); }
    private void press(int count) {
        downTime = time + 10;
        event(MotionEvent.ACTION_DOWN, 1, 0);
        for (int n = 2; n <= count; n++) event(MotionEvent.ACTION_POINTER_DOWN, n, n - 1);
    }
    private void release(int count) {
        for (int n = count; n > 1; n--) event(MotionEvent.ACTION_POINTER_UP, n, n - 1);
        event(MotionEvent.ACTION_UP, 1, 0);
    }
    private void event(int action, int count, int index) {
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) ids[i] = i + 7;
        eventWithIds(action, index, ids, -1);
    }
    private void eventWithIds(int action, int index, int[] ids, int stylusId) {
        eventWithIds(action, index, ids, stylusId, 0, 0);
    }
    private void eventWithIds(int action, int index, int[] ids, int stylusId, float dx, float dy) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = ids[i];
            props[i].toolType = ids[i] == stylusId ? MotionEvent.TOOL_TYPE_STYLUS : MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 120 + (ids[i] - 7) * 50 + dx;
            coords[i].y = 200 + dy;
            coords[i].pressure = 1;
        }
        MotionEvent event = MotionEvent.obtain(downTime, time += 10,
                action | index << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                ids.length, props, coords, 0, 0, 1f, 1f, 1, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            boolean consumed = game.handleMotionEvent(stream, event);
            // Existing mouse contexts handle two contacts; the third/fourth
            // POINTER_DOWN may pass through before a five-finger chord is claimed.
            if (action != MotionEvent.ACTION_POINTER_DOWN || ids.length <= 2 || ids.length >= 5) {
                assertTrue(consumed);
            }
        }
        finally { event.recycle(); }
    }
}
