package com.limelight.binding.input.virtual_controller;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.profiles.ProfilesManager;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, shadows = ShadowMoonBridge.class)
public class VirtualControllerReconnectTest {
    @Before public void setUp() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        ReflectionHelpers.setStaticField(ProfilesManager.class, "instance", null);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        ProfilesManager.getInstance().load(context);
    }

    @Test public void destructionNeutralizesStateAndCancelsRetransmitAndLongClick() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout layout = new FrameLayout(activity);
        ControllerHandler handler = mock(ControllerHandler.class);
        VirtualController controller = new VirtualController(handler, layout, activity);
        DigitalButton button = new DigitalButton(controller, VirtualControllerElement.EID_A, 0, activity);
        DigitalButton.DigitalButtonListener listener = mock(DigitalButton.DigitalButtonListener.class);
        button.addDigitalButtonListener(listener);
        controller.addElement(button, 0, 0, 100, 100);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 20, 0);
        button.onElementTouchEvent(down);
        down.recycle();
        controller.getControllerInputContext().inputMap = 1;
        controller.getControllerInputContext().leftStickX = 100;
        controller.sendControllerInputContext();
        clearInvocations(handler);

        controller.destroyForReconnect();
        verify(handler).reportOscState(0, (short) 0, (short) 0, (short) 0,
                (short) 0, (byte) 0, (byte) 0);
        assertFalse(button.isPressed());
        assertNull(button.getParent());
        assertEquals(0, layout.getChildCount());
        assertEquals(0, controller.getControllerInputContext().inputMap);
        clearInvocations(handler);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        controller.sendControllerInputContext();
        controller.destroyForReconnect();
        controller.refreshLayout();
        verifyNoInteractions(handler);
        verify(listener, never()).onLongClick();
        assertEquals(0, layout.getChildCount());
    }
}
