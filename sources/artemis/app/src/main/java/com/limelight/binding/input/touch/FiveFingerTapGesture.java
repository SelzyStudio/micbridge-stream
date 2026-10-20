package com.limelight.binding.input.touch;

import android.os.Build;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks a five-finger screen tap without dispatching any host input itself.
 * Call from the UI thread with the entire pointer stream, including its final UP
 * or CANCEL. The caller must cancel existing host contacts on CLAIM_JUST_STARTED.
 */
public final class FiveFingerTapGesture {
    public static final int NONE = 0;
    public static final int CONSUMED = 1;
    public static final int CLAIM_JUST_STARTED = 1 << 1;
    public static final int COMPLETED = 1 << 2;

    public static final long ASSEMBLY_TIMEOUT_MS = 300;
    public static final long TAP_TIMEOUT_MS = 500;

    private final double slopSquared;
    private final Map<Integer, StartPoint> contacts = new HashMap<>();
    private boolean tracking;
    private boolean claimed;
    private boolean valid;
    private boolean releasedAnyFinger;
    private int contactsAdded;
    private long firstDownTime;
    private long lastEventTime;

    public FiveFingerTapGesture(float moveSlopPx) {
        if (!finite(moveSlopPx) || moveSlopPx < 0) {
            throw new IllegalArgumentException("Move slop must be a finite nonnegative distance");
        }
        slopSquared = (double) moveSlopPx * moveSlopPx;
    }

    public void reset() {
        contacts.clear();
        tracking = false;
        claimed = false;
        valid = false;
        releasedAnyFinger = false;
        contactsAdded = 0;
        firstDownTime = 0;
        lastEventTime = 0;
    }

    /**
     * Returns a bit mask. Before five simultaneous finger contacts, events are
     * never consumed. Once claimed, even an invalid gesture consumes its tail.
     * COMPLETED is emitted only on the final ACTION_UP, never on finger five's
     * arrival. A new DOWN always starts an independent gesture.
     */
    public int onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            tracking = true;
            firstDownTime = lastEventTime = event.getEventTime();
            valid = event.getPointerCount() == 1 && allFingers(event) && !isCanceled(event);
            addContact(event, event.getActionIndex());
            return NONE;
        }
        if (!tracking) {
            return NONE;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            int result = claimed ? CONSUMED : NONE;
            reset();
            return result;
        }

        boolean fingerOnly = allFingers(event);
        int result = NONE;
        if (!claimed && event.getPointerCount() == 5 && fingerOnly) {
            claimed = true;
            result |= CLAIM_JUST_STARTED;
        }
        if (claimed) {
            result |= CONSUMED;
        }

        long eventTime = event.getEventTime();
        long elapsed = eventTime - firstDownTime;
        if (!fingerOnly || isCanceled(event) || eventTime < lastEventTime || elapsed < 0 ||
                elapsed > TAP_TIMEOUT_MS || contactsAdded > 5 ||
                ((!claimed || (result & CLAIM_JUST_STARTED) != 0) && elapsed > ASSEMBLY_TIMEOUT_MS)) {
            valid = false;
        }
        lastEventTime = eventTime;

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (releasedAnyFinger || event.getPointerCount() != contacts.size() + 1) {
                valid = false;
            }
            addContact(event, event.getActionIndex());
        }
        else if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_POINTER_UP &&
                action != MotionEvent.ACTION_UP) {
            valid = false;
        }

        checkCoordinates(event);
        if (contactsAdded > 5) {
            valid = false;
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (!claimed || event.getPointerCount() < 2) {
                valid = false;
            }
            removeContact(event, event.getActionIndex());
            releasedAnyFinger = true;
        }
        else if (action == MotionEvent.ACTION_UP) {
            if (claimed && valid && contactsAdded == 5 && contacts.size() == 1 &&
                    event.getPointerCount() == 1) {
                result |= COMPLETED;
            }
            reset();
        }
        return result;
    }

    private void addContact(MotionEvent event, int index) {
        if (index < 0 || index >= event.getPointerCount()) {
            valid = false;
            return;
        }
        int id = event.getPointerId(index);
        if (contacts.containsKey(id)) {
            valid = false;
            return;
        }
        float x = event.getX(index);
        float y = event.getY(index);
        if (!finite(x) || !finite(y)) {
            valid = false;
        }
        contacts.put(id, new StartPoint(x, y));
        contactsAdded++;
    }

    private void removeContact(MotionEvent event, int index) {
        if (index < 0 || index >= event.getPointerCount() ||
                contacts.remove(event.getPointerId(index)) == null) {
            valid = false;
        }
    }

    private void checkCoordinates(MotionEvent event) {
        if (event.getPointerCount() != contacts.size()) {
            valid = false;
        }
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < event.getPointerCount(); index++) {
            int id = event.getPointerId(index);
            StartPoint start = contacts.get(id);
            if (start == null || !seen.add(id)) {
                valid = false;
                continue;
            }
            for (int history = 0; history < event.getHistorySize(); history++) {
                checkDistance(start, event.getHistoricalX(index, history), event.getHistoricalY(index, history));
            }
            checkDistance(start, event.getX(index), event.getY(index));
        }
    }

    private void checkDistance(StartPoint start, float x, float y) {
        double dx = x - start.x;
        double dy = y - start.y;
        if (!finite(x) || !finite(y) || dx * dx + dy * dy > slopSquared) {
            valid = false;
        }
    }

    private static boolean allFingers(MotionEvent event) {
        for (int index = 0; index < event.getPointerCount(); index++) {
            if (event.getToolType(index) != MotionEvent.TOOL_TYPE_FINGER) {
                return false;
            }
        }
        return event.getPointerCount() != 0;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean isCanceled(MotionEvent event) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0;
    }

    private static final class StartPoint {
        final float x;
        final float y;

        StartPoint(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
