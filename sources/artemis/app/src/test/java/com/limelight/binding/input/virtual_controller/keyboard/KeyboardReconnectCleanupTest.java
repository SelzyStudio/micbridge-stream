package com.limelight.binding.input.virtual_controller.keyboard;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.Game;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.BitSet;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, shadows = ShadowMoonBridge.class)
public class KeyboardReconnectCleanupTest {
    @Before public void setUp() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        ReflectionHelpers.setStaticField(ProfilesManager.class, "instance", null);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        ProfilesManager.getInstance().load(context);
    }

    @After public void tearDown() { Game.instance = null; }

    @Test public void oldKeyboardCannotDeliverDelayedOrNewInputToReplacementGame() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout layout = new FrameLayout(activity);
        KeyBoardController controller = new KeyBoardController(mock(NvConnection.class), layout, activity);
        KeyBoardDigitalButton button = new KeyBoardDigitalButton(controller, "test", 0, activity);
        KeyBoardDigitalButton.DigitalButtonListener listener = mock(KeyBoardDigitalButton.DigitalButtonListener.class);
        button.addDigitalButtonListener(listener);
        controller.addElement(button, 0, 0, 100, 100);
        touch(button, MotionEvent.ACTION_DOWN);
        controller.destroyForReconnect();

        Game replacement = mock(Game.class);
        replacement.connected = true;
        Game.instance = replacement;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        controller.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
        controller.sendMouseMove(10, 20);
        controller.refreshLayout();
        controller.destroyForReconnect();
        verifyNoInteractions(replacement);
        verify(listener, never()).onLongClick();
        assertFalse(button.isPressed());
        assertEquals(0, layout.getChildCount());
        assertNull(button.getParent());
    }

    @Test public void stickyMacroReleasesOldConnectionBeforeDetaching() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout layout = new FrameLayout(activity);
        NvConnection oldConnection = mock(NvConnection.class);
        KeyBoardController controller = new KeyBoardController(oldConnection, layout, activity);
        short key = (short) 0x8041;
        KeyBoardDigitalButton macro = KeyBoardControllerConfigurationLoader.createCustomButton(
                "custom_test", new short[]{key}, 0, "A", -1, true, controller, oldConnection, activity);
        controller.addElement(macro, 0, 0, 100, 100);
        touch(macro, MotionEvent.ACTION_DOWN);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertTrue(macro.isSticky());
        clearInvocations(oldConnection);

        controller.destroyForReconnect();
        verify(oldConnection).sendKeyboardInput(key, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        clearInvocations(oldConnection);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verifyNoInteractions(oldConnection);
        assertFalse(macro.isSticky());
    }

    @Test public void retainedLayoutClearsHeldModifiersAndPendingLongClick() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout layout = new FrameLayout(activity);
        activity.setContentView(layout);
        PreferenceConfiguration preferences = PreferenceConfiguration.readPreferences(activity);
        preferences.stickyModifierKey = true;
        KeyBoardLayoutController controller = new KeyBoardLayoutController(layout, activity, preferences);
        controller.refreshLayout();
        Game game = mock(Game.class);
        game.connected = true;
        Game.instance = game;
        LinearLayout keyboard = ReflectionHelpers.getField(controller, "keyboardView");
        View shift = findKey(keyboard, KeyEvent.KEYCODE_SHIFT_LEFT);
        touch(shift, MotionEvent.ACTION_DOWN);
        controller.releaseSessionInput();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));

        assertFalse(controller.isModifierKeyPressed(KeyEvent.KEYCODE_SHIFT_LEFT));
        BitSet pressed = ReflectionHelpers.getField(controller, "pressedKeyStates");
        assertTrue(pressed.isEmpty());
        PopupWindow popup = ReflectionHelpers.getField(controller, "keyPopup");
        assertFalse(popup.isShowing());
        assertSame(layout, keyboard.getParent());
        verify(game).onKey(eq(null), eq(KeyEvent.KEYCODE_SHIFT_LEFT),
                argThat(event -> event.getAction() == KeyEvent.ACTION_UP));
    }

    @Test public void differentLayoutsOwnTheirLongClickCallbacks() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        PreferenceConfiguration preferences = PreferenceConfiguration.readPreferences(activity);
        preferences.stickyModifierKey = true;
        KeyBoardLayoutController first = new KeyBoardLayoutController(new FrameLayout(activity), activity, preferences);
        KeyBoardLayoutController second = new KeyBoardLayoutController(new FrameLayout(activity), activity, preferences);
        LinearLayout keyboard = ReflectionHelpers.getField(first, "keyboardView");
        touch(findKey(keyboard, KeyEvent.KEYCODE_SHIFT_LEFT), MotionEvent.ACTION_DOWN);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertTrue(first.isModifierKeyPressed(KeyEvent.KEYCODE_SHIFT_LEFT));
        assertFalse(second.isModifierKeyPressed(KeyEvent.KEYCODE_SHIFT_LEFT));
        first.releaseSessionInput();
        second.releaseSessionInput();
    }

    private static View findKey(LinearLayout keyboard, int keyCode) {
        for (int row = 0; row < keyboard.getChildCount(); row++) {
            LinearLayout keys = (LinearLayout) keyboard.getChildAt(row);
            for (int column = 0; column < keys.getChildCount(); column++) {
                View key = keys.getChildAt(column);
                if (String.valueOf(keyCode).equals(key.getTag())) return key;
            }
        }
        throw new AssertionError("Missing key " + keyCode);
    }

    private static void touch(View view, int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 20, 20, 0);
        if (view instanceof keyBoardVirtualControllerElement) {
            ((keyBoardVirtualControllerElement) view).onElementTouchEvent(event);
        } else {
            view.dispatchTouchEvent(event);
        }
        event.recycle();
    }
}
