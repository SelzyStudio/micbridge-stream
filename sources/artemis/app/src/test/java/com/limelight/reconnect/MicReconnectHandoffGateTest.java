package com.limelight.reconnect;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class MicReconnectHandoffGateTest {
    private FakeTime time;
    private MicReconnectHandoffGate gate;

    @Before public void setUp() {
        time = new FakeTime();
        gate = new MicReconnectHandoffGate(time, time);
    }

    @Test public void noHandoffIsImmediatelyReadyEvenWithNegativeMonotonicClockOrigin() throws Exception {
        time.now = -50000;
        gate = new MicReconnectHandoffGate(time, time);
        assertTrue(gate.awaitReady(() -> true));
        assertTrue(time.waits.isEmpty());
    }

    @Test public void disabledMicrophoneNeedsNoHandoffDelay() throws Exception {
        time.now = 3000;
        gate.transportStopped(false);
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(3000, time.now);
        assertTrue(time.waits.isEmpty());
    }

    @Test public void authenticatedFastHandoffNeedsNoFixedOwnerDelay() throws Exception {
        time.now = 3000;
        gate.transportStopped(true);
        assertTrue(gate.awaitReady(() -> true, true));
        assertEquals(3000, time.now);
        assertTrue(time.waits.isEmpty());
        // A single capable attempt must not erase the conservative deadline for another host.
        assertTrue(gate.awaitReady(() -> true, false));
        assertEquals(15000, time.now);
    }

    @Test public void fastHandoffStillRejectsHiddenOrClosingActivity() throws Exception {
        gate.transportStopped(true);
        assertFalse(gate.awaitReady(() -> false, true));
        assertTrue(time.waits.isEmpty());
        AtomicInteger checks = new AtomicInteger();
        assertFalse(gate.awaitReady(() -> checks.incrementAndGet() == 1, true));
        assertEquals(2, checks.get());
    }

    @Test public void fastHandoffStillHonorsThreadInterruption() {
        gate.transportStopped(true);
        Thread.currentThread().interrupt();
        try {
            gate.awaitReady(() -> true, true);
            fail("A cancelled fast handoff must not start another stream");
        } catch (InterruptedException expected) {
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(time.waits.isEmpty());
    }

    @Test public void microphoneWaitsTwelveSecondsFromCompletedStopInBoundedChunks() throws Exception {
        time.now = 5000;
        gate.transportStopped(true);
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(17000, time.now);
        assertEquals(48, time.waits.size());
        for (long wait : time.waits) {
            assertTrue(wait > 0 && wait <= 250);
        }
    }

    @Test public void elapsedTimeAfterStopReducesTheRemainingWorkerWait() throws Exception {
        gate.transportStopped(true);
        time.now = 3000;
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(12000, time.now);
        assertEquals(36, time.waits.size());
    }

    @Test public void oneMillisecondBeforeDeadlineStillWaits() throws Exception {
        gate.transportStopped(true);
        time.now = 11999;
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(12000, time.now);
        assertEquals(1, time.waits.size());
        assertEquals(Long.valueOf(1), time.waits.get(0));
    }

    @Test public void exactDeadlineDoesNotAddAnotherDelay() throws Exception {
        gate.transportStopped(true);
        time.now = 12000;
        assertTrue(gate.awaitReady(() -> true));
        assertTrue(time.waits.isEmpty());
    }

    @Test public void hiddenOrClosedBeforeWaitCancelsWithoutSleeping() throws Exception {
        gate.transportStopped(true);
        assertFalse(gate.awaitReady(() -> false));
        assertEquals(0, time.now);
        assertTrue(time.waits.isEmpty());
    }

    @Test public void appBecomingHiddenDuringWaitCancelsWithinOneChunk() throws Exception {
        AtomicBoolean visible = new AtomicBoolean(true);
        time.afterWait = () -> visible.set(false);
        gate.transportStopped(true);
        assertFalse(gate.awaitReady(visible::get));
        assertEquals(250, time.now);
        assertEquals(1, time.waits.size());
    }

    @Test public void waiterInterruptionPropagatesWithoutStartingTheReplacement() {
        gate.transportStopped(true);
        gate = new MicReconnectHandoffGate(time, milliseconds -> {
            throw new InterruptedException("worker canceled");
        });
        gate.transportStopped(true);
        try {
            gate.awaitReady(() -> true);
            fail("interrupted handoff must not proceed");
        } catch (InterruptedException expected) {
            assertEquals("worker canceled", expected.getMessage());
        }
        assertEquals(0, time.now);
    }

    @Test public void existingThreadInterruptCancelsEvenWhenTheDeadlineHasPassed() {
        Thread.currentThread().interrupt();
        try {
            gate.awaitReady(() -> true);
            fail("interrupted worker must not proceed");
        } catch (InterruptedException expected) {
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(time.waits.isEmpty());
    }

    @Test public void repeatedCompletedStopsRestartTheHandoffDeadline() throws Exception {
        gate.transportStopped(true);
        time.now = 10000;
        gate.transportStopped(true);
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(22000, time.now);
        assertEquals(48, time.waits.size());
    }

    @Test public void aNewCompletedStopDuringWaitExtendsTheObservedDeadline() throws Exception {
        gate.transportStopped(true);
        AtomicBoolean marked = new AtomicBoolean();
        time.afterWait = () -> {
            if (marked.compareAndSet(false, true)) {
                gate.transportStopped(true);
            }
        };
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(12250, time.now);
        assertEquals(49, time.waits.size());
    }

    @Test public void micDisabledByTheNextStoppedTransportClearsTheOldDelay() throws Exception {
        gate.transportStopped(true);
        time.now = 500;
        gate.transportStopped(false);
        assertTrue(gate.awaitReady(() -> true));
        assertEquals(500, time.now);
        assertTrue(time.waits.isEmpty());
    }

    @Test public void eligibilityIsCheckedAgainAtTheReadyBoundary() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        assertFalse(gate.awaitReady(() -> checks.incrementAndGet() == 1));
        assertEquals(2, checks.get());
        assertTrue(time.waits.isEmpty());
    }

    @Test public void becomingIneligibleAtDeadlineDoesNotStartReplacement() throws Exception {
        AtomicBoolean visible = new AtomicBoolean(true);
        gate.transportStopped(true);
        time.afterWait = () -> {
            if (time.now == 12000) visible.set(false);
        };
        assertFalse(gate.awaitReady(visible::get));
        assertEquals(12000, time.now);
    }

    private static final class FakeTime implements MicReconnectHandoffGate.Clock,
            MicReconnectHandoffGate.Waiter {
        long now;
        final List<Long> waits = new ArrayList<>();
        Runnable afterWait;
        @Override public long nowMillis() { return now; }
        @Override public void waitMillis(long milliseconds) {
            waits.add(milliseconds);
            now += milliseconds;
            if (afterWait != null) afterWait.run();
        }
    }
}
