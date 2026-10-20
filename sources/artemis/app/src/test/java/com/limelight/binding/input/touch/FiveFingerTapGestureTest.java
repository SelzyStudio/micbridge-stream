package com.limelight.binding.input.touch;

import static com.limelight.binding.input.touch.FiveFingerTapGesture.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.view.InputDevice;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, application = Application.class)
public class FiveFingerTapGestureTest {
    private static final int[] IDS = {7, 24, 3, 11, 30};

    @Test
    public void claimsOnFifthDownAndCompletesOnlyOnFinalUpOnce() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        assertEquals(CONSUMED | COMPLETED, finish(gesture, 80));
        assertEquals(NONE, send(gesture, 81, MotionEvent.ACTION_UP, 0, new int[] {30}, 0, 0));
    }

    @Test
    public void fourFingersNeverConsumeOrComplete() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        assertEquals(NONE, send(gesture, 0, MotionEvent.ACTION_DOWN, 0, new int[] {7}, 0, 0));
        for (int count = 2; count <= 4; count++) {
            assertEquals(NONE, send(gesture, count * 10, MotionEvent.ACTION_POINTER_DOWN, count - 1,
                    Arrays.copyOf(IDS, count), 0, 0));
        }
        for (int count = 4; count > 1; count--) {
            assertEquals(NONE, send(gesture, 90 - count, MotionEvent.ACTION_POINTER_UP, 0,
                    Arrays.copyOfRange(IDS, 4 - count, 4), 0, 0));
        }
        assertEquals(NONE, send(gesture, 100, MotionEvent.ACTION_UP, 0, new int[] {11}, 0, 0));
    }

    @Test
    public void exactAssemblyAndTapDeadlinesAreInclusive() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 300);
        assertEquals(CONSUMED | COMPLETED, finish(gesture, 500));
    }

    @Test
    public void lateFifthFingerClaimsButNeverCompletes() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 301);
        assertEquals(CONSUMED, finish(gesture, 400));
    }

    @Test
    public void longTapConsumesTailWithoutCompleting() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        assertEquals(CONSUMED, finish(gesture, 501));
    }

    @Test
    public void movementBeforeFifthInvalidatesWithoutPrematureConsumption() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        send(gesture, 0, MotionEvent.ACTION_DOWN, 0, new int[] {7}, 0, 0);
        assertEquals(NONE, send(gesture, 1, MotionEvent.ACTION_MOVE, 0, new int[] {7}, 11, 0));
        for (int count = 2; count < 5; count++) {
            assertEquals(NONE, send(gesture, count * 10, MotionEvent.ACTION_POINTER_DOWN, count - 1,
                    Arrays.copyOf(IDS, count), 0, 0));
        }
        assertEquals(CONSUMED | CLAIM_JUST_STARTED,
                send(gesture, 50, MotionEvent.ACTION_POINTER_DOWN, 4, IDS, 0, 0));
        assertEquals(CONSUMED, finish(gesture, 80));
    }

    @Test
    public void movementUsesRadialSlopAndExactBoundaryPasses() {
        FiveFingerTapGesture within = new FiveFingerTapGesture(10);
        claim(within, 40);
        assertEquals(CONSUMED, send(within, 41, MotionEvent.ACTION_MOVE, 0, IDS, 6, 8));
        assertEquals(CONSUMED | COMPLETED, finish(within, 80));

        FiveFingerTapGesture outside = new FiveFingerTapGesture(10);
        claim(outside, 40);
        assertEquals(CONSUMED, send(outside, 41, MotionEvent.ACTION_MOVE, 0, IDS, 9, 9));
        assertEquals(CONSUMED, finish(outside, 80));
    }

    @Test
    public void historicalExcursionInvalidatesEvenWhenCurrentPositionReturns() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        MotionEvent move = event(41, MotionEvent.ACTION_MOVE, 0, IDS, 20, 0, -1);
        try {
            move.addBatch(42, coordinates(IDS, 0, 0), 0);
            assertTrue(move.getHistorySize() > 0);
            assertEquals(CONSUMED, gesture.onTouchEvent(move));
        }
        finally {
            move.recycle();
        }
        assertEquals(CONSUMED, finish(gesture, 80));
    }

    @Test
    public void sixthContactInvalidatesButEveryReleaseRemainsConsumed() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        int[] six = {7, 24, 3, 11, 30, 1};
        assertEquals(CONSUMED, send(gesture, 41, MotionEvent.ACTION_POINTER_DOWN, 5, six, 0, 0));
        assertEquals(CONSUMED, send(gesture, 42, MotionEvent.ACTION_POINTER_UP, 5, six, 0, 0));
        assertEquals(CONSUMED, finish(gesture, 80));
    }

    @Test
    public void pointerIndicesCanReorderWhileIdsAndReleaseOwnershipStayStable() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        int[] reordered = {30, 3, 7, 11, 24};
        assertEquals(CONSUMED, send(gesture, 41, MotionEvent.ACTION_MOVE, 0, reordered, 0, 0));
        assertEquals(CONSUMED, send(gesture, 42, MotionEvent.ACTION_POINTER_UP, 2, reordered, 0, 0));
        assertEquals(CONSUMED, send(gesture, 43, MotionEvent.ACTION_POINTER_UP, 3,
                new int[] {30, 3, 11, 24}, 0, 0));
        assertEquals(CONSUMED, send(gesture, 44, MotionEvent.ACTION_POINTER_UP, 1,
                new int[] {30, 3, 11}, 0, 0));
        assertEquals(CONSUMED, send(gesture, 45, MotionEvent.ACTION_POINTER_UP, 0,
                new int[] {30, 11}, 0, 0));
        assertEquals(CONSUMED | COMPLETED,
                send(gesture, 46, MotionEvent.ACTION_UP, 0, new int[] {11}, 0, 0));
    }

    @Test
    public void cancelConsumesClaimedGestureAndAllowsFreshNextDown() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        assertEquals(CONSUMED, send(gesture, 41, MotionEvent.ACTION_CANCEL, 0, IDS, 0, 0));
        assertEquals(NONE, send(gesture, 42, MotionEvent.ACTION_UP, 0, new int[] {7}, 0, 0));
        claim(gesture, 40);
        assertEquals(CONSUMED | COMPLETED, finish(gesture, 80));
    }

    @Test
    public void newDownResetsAnUnfinishedClaim() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        assertEquals(NONE, send(gesture, 50, MotionEvent.ACTION_DOWN, 0, new int[] {7}, 0, 0));
        assertEquals(NONE, send(gesture, 51, MotionEvent.ACTION_UP, 0, new int[] {7}, 0, 0));
        claim(gesture, 40);
        assertEquals(CONSUMED | COMPLETED, finish(gesture, 80));
    }

    @Test
    public void stylusAmongFivePointersDoesNotClaim() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        send(gesture, 0, MotionEvent.ACTION_DOWN, 0, new int[] {7}, 0, 0);
        for (int count = 2; count <= 4; count++) {
            send(gesture, count * 10, MotionEvent.ACTION_POINTER_DOWN, count - 1,
                    Arrays.copyOf(IDS, count), 0, 0);
        }
        MotionEvent mixed = event(40, MotionEvent.ACTION_POINTER_DOWN, 4, IDS, 0, 0, 4);
        try {
            assertEquals(NONE, gesture.onTouchEvent(mixed));
        }
        finally {
            mixed.recycle();
        }
        assertEquals(NONE, send(gesture, 41, MotionEvent.ACTION_CANCEL, 0, IDS, 0, 0));
    }

    @Test
    public void releaseBeforeAssemblyCannotBeReplacedToCreateATap() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        send(gesture, 0, MotionEvent.ACTION_DOWN, 0, new int[] {7}, 0, 0);
        send(gesture, 10, MotionEvent.ACTION_POINTER_DOWN, 1, new int[] {7, 24}, 0, 0);
        assertEquals(NONE, send(gesture, 11, MotionEvent.ACTION_POINTER_UP, 1, new int[] {7, 24}, 0, 0));
        for (int count = 2; count <= 5; count++) {
            int expected = count == 5 ? CONSUMED | CLAIM_JUST_STARTED : NONE;
            assertEquals(expected, send(gesture, 20 + count, MotionEvent.ACTION_POINTER_DOWN, count - 1,
                    Arrays.copyOf(IDS, count), 0, 0));
        }
        assertEquals(CONSUMED, finish(gesture, 80));
    }

    @Test
    public void outOfOrderTimestampInvalidatesAndUnknownPointerDoesNotCrash() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        assertEquals(CONSUMED, send(gesture, 39, MotionEvent.ACTION_MOVE, 0, IDS, 0, 0));
        assertEquals(CONSUMED, send(gesture, 41, MotionEvent.ACTION_MOVE, 0,
                new int[] {7, 24, 3, 11, 29}, 0, 0));
        assertEquals(CONSUMED, finish(gesture, 80));
    }

    @Test
    public void missingDownAndExplicitResetCannotComplete() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        assertEquals(NONE, send(gesture, 40, MotionEvent.ACTION_POINTER_DOWN, 4, IDS, 0, 0));
        claim(gesture, 40);
        gesture.reset();
        assertEquals(NONE, send(gesture, 41, MotionEvent.ACTION_UP, 0, new int[] {30}, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FiveFingerTapGesture(-1));
        assertThrows(IllegalArgumentException.class, () -> new FiveFingerTapGesture(Float.NaN));
    }

    @Test
    public void canceledPointerUpInvalidatesButStillConsumesTheRemainingFourFingers() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        MotionEvent canceled = event(41, MotionEvent.ACTION_POINTER_UP, 0, IDS, 0, 0, -1,
                MotionEvent.FLAG_CANCELED);
        try {
            assertEquals(CONSUMED, gesture.onTouchEvent(canceled));
        }
        finally {
            canceled.recycle();
        }
        assertEquals(CONSUMED, finishFrom(gesture, 4, 80, 0));
    }

    @Test
    public void canceledFinalUpNeverCompletesAnOtherwiseValidTap() {
        FiveFingerTapGesture gesture = new FiveFingerTapGesture(10);
        claim(gesture, 40);
        assertEquals(CONSUMED, finishFrom(gesture, 5, 80, MotionEvent.FLAG_CANCELED));
    }

    private static void claim(FiveFingerTapGesture gesture, long fifthTime) {
        assertEquals(NONE, send(gesture, 0, MotionEvent.ACTION_DOWN, 0, new int[] {7}, 0, 0));
        for (int count = 2; count <= 5; count++) {
            int expected = count == 5 ? CONSUMED | CLAIM_JUST_STARTED : NONE;
            assertEquals(expected, send(gesture, fifthTime * (count - 1) / 4,
                    MotionEvent.ACTION_POINTER_DOWN, count - 1, Arrays.copyOf(IDS, count), 0, 0));
        }
    }

    private static int finish(FiveFingerTapGesture gesture, long finalTime) {
        return finishFrom(gesture, 5, finalTime, 0);
    }

    private static int finishFrom(FiveFingerTapGesture gesture, int remaining, long finalTime, int finalFlags) {
        for (int count = remaining; count > 1; count--) {
            assertEquals(CONSUMED, send(gesture, finalTime - count + 1, MotionEvent.ACTION_POINTER_UP,
                    0, Arrays.copyOfRange(IDS, 5 - count, 5), 0, 0));
        }
        MotionEvent finalUp = event(finalTime, MotionEvent.ACTION_UP, 0, new int[] {30}, 0, 0, -1, finalFlags);
        try {
            return gesture.onTouchEvent(finalUp);
        }
        finally {
            finalUp.recycle();
        }
    }

    private static int send(FiveFingerTapGesture gesture, long time, int action, int index,
                            int[] ids, float dx, float dy) {
        MotionEvent event = event(time, action, index, ids, dx, dy, -1);
        try {
            return gesture.onTouchEvent(event);
        }
        finally {
            event.recycle();
        }
    }

    private static MotionEvent event(long time, int action, int index, int[] ids,
                                     float dx, float dy, int stylusIndex) {
        return event(time, action, index, ids, dx, dy, stylusIndex, 0);
    }

    private static MotionEvent event(long time, int action, int index, int[] ids,
                                     float dx, float dy, int stylusIndex, int flags) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        for (int pointer = 0; pointer < ids.length; pointer++) {
            properties[pointer] = new MotionEvent.PointerProperties();
            properties[pointer].id = ids[pointer];
            properties[pointer].toolType = pointer == stylusIndex ?
                    MotionEvent.TOOL_TYPE_STYLUS : MotionEvent.TOOL_TYPE_FINGER;
        }
        return MotionEvent.obtain(0, time, action | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                ids.length, properties, coordinates(ids, dx, dy), 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, flags);
    }

    private static MotionEvent.PointerCoords[] coordinates(int[] ids, float dx, float dy) {
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int pointer = 0; pointer < ids.length; pointer++) {
            coordinates[pointer] = new MotionEvent.PointerCoords();
            coordinates[pointer].x = 100 + ids[pointer] * 20 + dx;
            coordinates[pointer].y = 100 + ids[pointer] * 10 + dy;
            coordinates[pointer].pressure = 1;
            coordinates[pointer].size = 1;
        }
        return coordinates;
    }
}
