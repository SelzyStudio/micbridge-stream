package com.limelight.binding.input.touch;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.os.Looper;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, application = Application.class, shadows = ShadowMoonBridge.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TrackpadContextMotionTest {
    @Test
    public void legacyConstructorsRetainObservedSixtyFourToOneHundredTwentyEightGain() {
        Trace twoArguments = new Trace();
        TrackpadContext original = new TrackpadContext(twoArguments.connection, 0);
        start(original, 1);
        original.touchMoveEvent(64, 0, 10);
        assertMoves(twoArguments, 128, 0);

        Trace fiveArguments = new Trace();
        TrackpadContext configured = new TrackpadContext(fiveArguments.connection, 0, false, 100, 100);
        start(configured, 1);
        configured.touchMoveEvent(64, 0, 10);
        assertMoves(fiveArguments, 128, 0);

        Trace explicitLegacy = new Trace();
        TrackpadContext sixArguments = new TrackpadContext(explicitLegacy.connection, 0, false, 100, 100, false);
        start(sixArguments, 1);
        sixArguments.touchMoveEvent(64, 0, 10);
        assertMoves(explicitLegacy, 128, 0);
    }

    @Test
    public void linearPointerSendsExactlyOneForOneAndNeverAbsolutePosition() {
        Trace trace = new Trace();
        TrackpadContext context = linear(trace, false, 100, 100);
        start(context, 1);
        context.touchMoveEvent(64, 0, 10);
        assertMoves(trace, 64, 0);
        verify(trace.connection, never()).sendMousePosition(anyShort(), anyShort(), anyShort(), anyShort());
    }

    @Test
    public void linearTotalDoesNotDependOnMoveEventBatchSize() {
        Trace whole = new Trace();
        TrackpadContext wholeContext = linear(whole, false, 100, 100);
        start(wholeContext, 1);
        wholeContext.touchMoveEvent(64, 0, 64);

        Trace split = new Trace();
        TrackpadContext splitContext = linear(split, false, 100, 100);
        start(splitContext, 1);
        for (int step = 1; step <= 64; step++) {
            splitContext.touchMoveEvent(step, 0, step);
        }
        assertEquals(64, whole.totalX());
        assertEquals(whole.totalX(), split.totalX());
        assertEquals(whole.totalY(), split.totalY());
        assertEquals(64, split.moves.size());
    }

    @Test
    public void halfSensitivityPreservesFractionalPixelsAndNegativeReturnMotion() {
        Trace trace = new Trace();
        TrackpadContext context = linear(trace, false, 50, 50);
        start(context, 1);
        context.touchMoveEvent(1, 0, 1);
        assertTrue(trace.moves.isEmpty());
        context.touchMoveEvent(2, 0, 2);
        assertMoves(trace, 1, 0);
        for (int x = 3; x <= 64; x++) {
            context.touchMoveEvent(x, 0, x);
        }
        assertEquals(32, trace.totalX());
        for (int x = 63; x >= 0; x--) {
            context.touchMoveEvent(x, 0, 128 - x);
        }
        assertEquals(0, trace.totalX());
        assertEquals(0, trace.totalY());
        assertEquals(64, trace.moves.size());
    }

    @Test
    public void swappedAxesApplyTheConfiguredSensitivityOfEachOutputAxis() {
        Trace trace = new Trace();
        TrackpadContext context = linear(trace, true, 50, 200);
        start(context, 1);
        context.touchMoveEvent(64, 32, 10);
        assertMoves(trace, 16, 128);
    }

    @Test
    public void fastLinearPointerLiftHasNoFlickWhileLegacyStillFlicks() {
        Trace linear = new Trace();
        TrackpadContext linearContext = linear(linear, false, 100, 100);
        start(linearContext, 1);
        linearContext.touchMoveEvent(64, 0, 1);
        linearContext.touchUpEvent(64, 0, 2);
        advance(300);
        assertMoves(linear, 64, 0);
        assertEquals(1, linear.moves.size());

        Trace legacy = new Trace();
        TrackpadContext legacyContext = new TrackpadContext(legacy.connection, 0);
        start(legacyContext, 1);
        legacyContext.touchMoveEvent(64, 0, 1);
        legacyContext.touchUpEvent(64, 0, 2);
        advance(30);
        assertTrue(legacy.totalX() > 128);
        assertTrue(legacy.moves.size() > 1);
        legacyContext.cancelTouch();
    }

    @Test
    public void fastLinearDragReleasesButtonOnLiftWithoutPointerMomentum() {
        for (int fingers : new int[] {1, 2}) {
            Trace trace = new Trace();
            TrackpadContext context = new TrackpadContext(trace.connection, fingers - 1,
                    false, 100, 100, true);
            start(context, fingers);
            context.touchUpEvent(0, 0, 20); // First tap holds its button pending double-tap.
            byte button = fingers == 1 ? MouseButtonPacket.BUTTON_LEFT : MouseButtonPacket.BUTTON_RIGHT;
            assertEquals(Arrays.asList(button), trace.buttonDown);
            context.touchDownEvent(0, 0, 40, true);
            context.touchMoveEvent(64, 0, 50); // Turns the pending double tap into a drag.
            context.touchUpEvent(64, 0, 51);
            assertEquals(Arrays.asList(button), trace.buttonUp);
            assertMoves(trace, 64, 0);
            advance(300);
            assertEquals(1, trace.moves.size());
            assertEquals(Arrays.asList(button), trace.buttonUp);
        }
    }

    @Test
    public void twoFingerScrollKeepsFirstMoveGainAndMomentumOfLegacyConstructor() {
        Trace legacy = scrollTrace(false, false, 100, 100);
        Trace linear = scrollTrace(true, false, 100, 100);
        assertEquals(Short.valueOf((short) 384), linear.verticalScroll.get(0));
        assertTrue(linear.verticalScroll.size() > 1);
        assertEquals(legacy.verticalScroll, linear.verticalScroll);
        assertEquals(legacy.horizontalScroll, linear.horizontalScroll);
        assertTrue(linear.moves.isEmpty());

        Trace swappedLegacy = scrollTrace(false, true, 50, 200);
        Trace swappedLinear = scrollTrace(true, true, 50, 200);
        assertEquals(swappedLegacy.verticalScroll, swappedLinear.verticalScroll);
        assertEquals(swappedLegacy.horizontalScroll, swappedLinear.horizontalScroll);
        assertTrue(swappedLinear.moves.isEmpty());
    }

    @Test
    public void scrollToPointerTransitionDoesNotAccumulateACatchUpJump() {
        Trace trace = new Trace();
        TrackpadContext context = linear(trace, false, 100, 100);
        start(context, 2);
        context.setPointerCount(1);
        context.touchMoveEvent(64, 0, 10);
        assertTrue(trace.moves.isEmpty());
        advance(201);
        context.touchMoveEvent(65, 0, 211);
        assertMoves(trace, 1, 0);
    }

    private static Trace scrollTrace(boolean linear, boolean swap, int sensitivityX, int sensitivityY) {
        Trace trace = new Trace();
        TrackpadContext context = new TrackpadContext(trace.connection, 1, swap, sensitivityX, sensitivityY, linear);
        start(context, 2);
        context.touchMoveEvent(0, 64, 10);
        context.touchUpEvent(0, 64, 11);
        advance(40);
        context.cancelTouch();
        return trace;
    }

    private static TrackpadContext linear(Trace trace, boolean swap, int sensitivityX, int sensitivityY) {
        return new TrackpadContext(trace.connection, 0, swap, sensitivityX, sensitivityY, true);
    }

    private static void start(TrackpadContext context, int pointerCount) {
        context.setPointerCount(pointerCount);
        assertTrue(context.touchDownEvent(0, 0, 0, true));
    }

    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }

    private static void assertMoves(Trace trace, int x, int y) {
        assertEquals(x, trace.totalX());
        assertEquals(y, trace.totalY());
    }

    private static final class Trace {
        final NvConnection connection = mock(NvConnection.class);
        final List<short[]> moves = new ArrayList<>();
        final List<Short> verticalScroll = new ArrayList<>();
        final List<Short> horizontalScroll = new ArrayList<>();
        final List<Byte> buttonDown = new ArrayList<>();
        final List<Byte> buttonUp = new ArrayList<>();

        Trace() {
            doAnswer(call -> {
                moves.add(new short[] {call.getArgument(0), call.getArgument(1)});
                return null;
            }).when(connection).sendMouseMove(anyShort(), anyShort());
            doAnswer(call -> { verticalScroll.add(call.getArgument(0)); return null; })
                    .when(connection).sendMouseHighResScroll(anyShort());
            doAnswer(call -> { horizontalScroll.add(call.getArgument(0)); return null; })
                    .when(connection).sendMouseHighResHScroll(anyShort());
            doAnswer(call -> { buttonDown.add(call.getArgument(0)); return null; })
                    .when(connection).sendMouseButtonDown(anyByte());
            doAnswer(call -> { buttonUp.add(call.getArgument(0)); return null; })
                    .when(connection).sendMouseButtonUp(anyByte());
        }

        int totalX() { return moves.stream().mapToInt(move -> move[0]).sum(); }
        int totalY() { return moves.stream().mapToInt(move -> move[1]).sum(); }
    }
}
