package com.limelight.binding.input;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.limelight.binding.input.driver.AbstractController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.GameGestures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, shadows = ShadowMoonBridge.class)
public class ControllerHandlerSuspensionTest {
    private Activity activity;
    private NvConnection connection;
    private ControllerHandler handler;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(NoShieldServiceActivity.class).setup().get();
        connection = mock(NvConnection.class);
        PreferenceConfiguration preferences = new PreferenceConfiguration();
        preferences.deadzonePercentage = 10;
        preferences.multiController = true;
        preferences.analogStickForScrolling = PreferenceConfiguration.AnalogStickForScrolling.RIGHT;
        handler = new ControllerHandler(activity, connection, mock(GameGestures.class), preferences);
    }

    @After
    public void tearDown() {
        handler.destroy();
        activity.finish();
    }

    @Test
    public void oscSuspensionSendsNeutralAndDiscardsHiddenState() {
        handler.reportOscState(ControllerPacket.A_FLAG, (short) 12000, (short) -8000,
                (short) 7000, (short) -3000, (byte) 40, (byte) 50);
        clearInvocations(connection);
        handler.setSuspended(true);
        verify(connection).sendControllerInput((short) 0, (short) 0, 0,
                (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        clearInvocations(connection);
        handler.reportOscState(ControllerPacket.B_FLAG, (short) 32000, (short) 32000,
                (short) 0, (short) 0, (byte) 100, (byte) 100);
        assertEquals(0, defaultContext().inputMap);
        assertEquals(0, defaultContext().leftStickX);
        verifyNoInteractions(connection);
        handler.setSuspended(false);
        verifyNoInteractions(connection);
        handler.reportOscState(ControllerPacket.X_FLAG, (short) 0, (short) 0,
                (short) 0, (short) 0, (byte) 0, (byte) 0);
        verify(connection).sendControllerInput((short) 0, (short) 0, ControllerPacket.X_FLAG,
                (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
    }

    @Test
    public void suspendResumeWithoutControllerInputDoesNotCreateController() {
        handler.setSuspended(true);
        handler.setSuspended(false);
        verifyNoInteractions(connection);
    }

    @Test
    public void usbPollingAndMotionStaySilentUntilForeground() {
        AbstractController usbDevice = addUsbDevice(7);
        handler.reportControllerState(7, ControllerPacket.A_FLAG, 0.5f, 0, 0, 0, 0, 0);
        handler.setSuspended(true);
        clearInvocations(connection);
        handler.reportControllerState(7, ControllerPacket.B_FLAG, 1, 1, 1, 1, 1, 1);
        handler.reportControllerMotion(7, MoonBridge.LI_MOTION_TYPE_GYRO, 1, 2, 3);
        verifyNoInteractions(connection);
        handler.setSuspended(false);
        handler.reportControllerState(7, ControllerPacket.X_FLAG, 0, 0, 0, 0, 0, 0);
        verify(connection).sendControllerInput((short) 0, (short) 1, ControllerPacket.X_FLAG,
                (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        handler.reportControllerMotion(7, MoonBridge.LI_MOTION_TYPE_GYRO, 4, 5, 6);
        verify(connection).sendControllerMotionEvent((byte) 0, MoonBridge.LI_MOTION_TYPE_GYRO, 4, 5, 6);
        verify(usbDevice).rumble((short) 0, (short) 0);
    }

    @Test
    public void hardwareButtonsAndAxesCannotChangeStateWhileSuspended() {
        handler.handleButtonDown(button(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A));
        handler.setSuspended(true);
        clearInvocations(connection);
        handler.handleButtonDown(button(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B));
        handler.handleButtonUp(button(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A));
        MotionEvent motion = MotionEvent.obtain(100, 150, MotionEvent.ACTION_MOVE, 400, 300, 0);
        try {
            motion.setSource(InputDevice.SOURCE_JOYSTICK);
            assertTrue(handler.handleMotionEvent(motion));
            assertTrue(handler.tryHandleTouchpadEvent(motion));
        } finally {
            motion.recycle();
        }
        assertEquals(0, defaultContext().inputMap);
        verifyNoInteractions(connection);
        handler.setSuspended(false);
        handler.handleButtonDown(button(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(ControllerPacket.X_FLAG, defaultContext().inputMap);
    }

    @Test
    public void suspensionReleasesEmulatedMouseButtonsAndPreservesModeChoice() {
        ControllerHandler.InputDeviceContext context = defaultContext();
        context.mouseEmulationActive = true;
        handler.reportOscState(ControllerPacket.A_FLAG | ControllerPacket.B_FLAG,
                (short) 12000, (short) 0, (short) 0, (short) 0, (byte) 0, (byte) 0);
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
        handler.setSuspended(true);
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
        clearInvocations(connection);
        context.mouseEmulationRunnable.run();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        verifyNoInteractions(connection);
        assertTrue(context.mouseEmulationActive);
        assertEquals(0, context.mouseEmulationLastInputMap);
        handler.setSuspended(false);
        handler.reportOscState(0, (short) 20000, (short) 0,
                (short) 0, (short) 0, (byte) 0, (byte) 0);
        context.mouseEmulationRunnable.run();
        verify(connection, atLeastOnce()).sendMouseMove(anyShort(), anyShort());
    }

    @Test
    public void controllerTouchContactsAndRumbleAreReleasedAtSuspension() {
        AbstractController usbDevice = addUsbDevice(7);
        handler.reportControllerState(7, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0);
        handler.handleRumble((short) 0, (short) 40, (short) 50);
        handler.handleRumbleTriggers((short) 0, (short) 60, (short) 70);
        handler.setSuspended(true);
        verify(connection).sendControllerTouchEvent((byte) 0,
                MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0, 0, 0, 0);
        verify(usbDevice).rumble((short) 0, (short) 0);
        verify(usbDevice).rumbleTriggers((short) 0, (short) 0);
        clearInvocations(usbDevice);
        handler.handleRumble((short) 0, (short) 400, (short) 500);
        handler.handleRumbleTriggers((short) 0, (short) 600, (short) 700);
        verifyNoInteractions(usbDevice);
        handler.setSuspended(false);
        handler.handleRumble((short) 0, (short) 80, (short) 90);
        verify(usbDevice).rumble((short) 80, (short) 90);
    }

    @Test
    public void hardwareSensorCallbackCannotSendWhileSuspended() {
        SensorEventListener listener = ReflectionHelpers.callInstanceMethod(handler, "createSensorListener",
                ReflectionHelpers.ClassParameter.from(short.class, (short) 0),
                ReflectionHelpers.ClassParameter.from(byte.class, MoonBridge.LI_MOTION_TYPE_GYRO),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        listener.onSensorChanged(sensorEvent(1, 2, 3));
        verify(connection).sendControllerMotionEvent(eq((byte) 0), eq(MoonBridge.LI_MOTION_TYPE_GYRO),
                anyFloat(), anyFloat(), anyFloat());
        handler.setSuspended(true);
        clearInvocations(connection);
        listener.onSensorChanged(sensorEvent(4, 5, 6));
        verifyNoInteractions(connection);
        handler.setSuspended(false);
        listener.onSensorChanged(sensorEvent(7, 8, 9));
        verify(connection).sendControllerMotionEvent(eq((byte) 0), eq(MoonBridge.LI_MOTION_TYPE_GYRO),
                anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    public void sensorRequestsWhileHiddenAreRememberedWithoutRegisteringSensors() {
        ControllerHandler.InputDeviceContext context = handler.new InputDeviceContext();
        context.controllerNumber = 1;
        context.assignedControllerNumber = true;
        context.sensorManager = mock(SensorManager.class);
        Sensor accel = mock(Sensor.class);
        when(context.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel);
        inputContexts().put(5, context);
        handler.handleSetMotionEventState((short) 1, MoonBridge.LI_MOTION_TYPE_ACCEL, (short) 60);
        verify(context.sensorManager).registerListener(any(), eq(accel), eq(1000000 / 60));
        handler.setSuspended(true);
        verify(context.sensorManager).unregisterListener(any(SensorEventListener.class));
        clearInvocations(context.sensorManager, connection);
        handler.handleSetMotionEventState((short) 1, MoonBridge.LI_MOTION_TYPE_ACCEL, (short) 120);
        assertEquals(120, context.accelReportRateHz);
        verifyNoInteractions(context.sensorManager);
        handler.setSuspended(false);
        Handler background = ReflectionHelpers.getField(handler, "backgroundThreadHandler");
        Shadows.shadowOf(background.getLooper()).idleFor(Duration.ofSeconds(1));
        verify(context.sensorManager).registerListener(any(), eq(accel), eq(1000000 / 120));
    }

    @Test
    public void repeatedSuspensionDoesNotResendNeutralOrKeepHeldComboState() {
        ControllerHandler.InputDeviceContext context = defaultContext();
        handler.reportOscState(ControllerPacket.PLAY_FLAG, (short) 0, (short) 0,
                (short) 0, (short) 0, (byte) 0, (byte) 0);
        context.backMenuPending = true;
        context.pendingExit = true;
        context.emulatingButtonFlags = 3;
        handler.setSuspended(true);
        assertFalse(context.backMenuPending);
        assertFalse(context.pendingExit);
        assertEquals(0, context.emulatingButtonFlags);
        clearInvocations(connection);
        handler.setSuspended(true);
        verifyNoInteractions(connection);
    }

    @Test
    public void permanentStopCannotBeResumed() {
        handler.reportOscState(ControllerPacket.A_FLAG, (short) 0, (short) 0,
                (short) 0, (short) 0, (byte) 0, (byte) 0);
        handler.stop();
        clearInvocations(connection);
        handler.setSuspended(false);
        handler.reportOscState(ControllerPacket.B_FLAG, (short) 0, (short) 0,
                (short) 0, (short) 0, (byte) 0, (byte) 0);
        handler.reportControllerMotion(7, MoonBridge.LI_MOTION_TYPE_GYRO, 1, 2, 3);
        verifyNoInteractions(connection);
    }

    @Test
    public void suspensionWaitsForInFlightUsbPacketThenNeutralizesIt() throws Exception {
        addUsbDevice(7);
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch finishSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            sendEntered.countDown();
            assertTrue(finishSend.await(5, TimeUnit.SECONDS));
            return null;
        }).when(connection).sendControllerInput(anyShort(), anyShort(), anyInt(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread input = checkedThread(error, () ->
                handler.reportControllerState(7, ControllerPacket.A_FLAG, 0, 0, 0, 0, 0, 0));
        Thread suspend = checkedThread(error, () -> handler.setSuspended(true));
        input.start();
        assertTrue(sendEntered.await(5, TimeUnit.SECONDS));
        suspend.start();
        finishSend.countDown();
        input.join(5000);
        suspend.join(5000);
        assertFalse(input.isAlive());
        assertFalse(suspend.isAlive());
        assertNull(error.get());
        ArgumentCaptor<Integer> buttons = ArgumentCaptor.forClass(Integer.class);
        verify(connection, times(2)).sendControllerInput(anyShort(), anyShort(), buttons.capture(),
                anyByte(), anyByte(), anyShort(), anyShort(), anyShort(), anyShort());
        assertEquals(Arrays.asList(ControllerPacket.A_FLAG, 0), buttons.getAllValues());
        clearInvocations(connection);
        handler.reportControllerState(7, ControllerPacket.B_FLAG, 1, 1, 1, 1, 1, 1);
        verifyNoInteractions(connection);
    }

    private AbstractController addUsbDevice(int id) {
        AbstractController device = mock(AbstractController.class);
        when(device.getControllerId()).thenReturn(id);
        handler.deviceAdded(device);
        return device;
    }

    private ControllerHandler.InputDeviceContext defaultContext() {
        return ReflectionHelpers.getField(handler, "defaultContext");
    }

    private SparseArray<ControllerHandler.InputDeviceContext> inputContexts() {
        return ReflectionHelpers.getField(handler, "inputDeviceContexts");
    }

    private static KeyEvent button(int action, int keyCode) {
        return new KeyEvent(100, 150, action, keyCode, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD);
    }

    private static SensorEvent sensorEvent(float x, float y, float z) {
        SensorEvent event = ReflectionHelpers.callConstructor(SensorEvent.class,
                ReflectionHelpers.ClassParameter.from(int.class, 3));
        event.values[0] = x;
        event.values[1] = y;
        event.values[2] = z;
        return event;
    }

    private static Thread checkedThread(AtomicReference<Throwable> error, Runnable task) {
        return new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });
    }

    /** Ordinary phones do not expose NVIDIA's proprietary controller service. */
    public static class NoShieldServiceActivity extends Activity {
        @Override
        public boolean bindService(Intent service, ServiceConnection connection, int flags) {
            // Robolectric otherwise reports a successful binding with a null Binder; draining
            // the main looper would then crash SceManager's unrelated service callback.
            return false;
        }
    }
}
