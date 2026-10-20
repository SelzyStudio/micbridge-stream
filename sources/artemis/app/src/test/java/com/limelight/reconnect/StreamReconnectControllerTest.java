package com.limelight.reconnect;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class StreamReconnectControllerTest {
    private FakeScheduler scheduler;
    private RecordingListener listener;
    private StreamReconnectController controller;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
        listener = new RecordingListener();
        controller = new StreamReconnectController(scheduler, listener);
    }

    @Test
    public void initialConnectionFailureUsesNormalErrorPath() {
        assertFalse(controller.onFailure());
        assertFalse(controller.isRecovering());
        assertNull(scheduler.pending);
        assertTrue(listener.states.isEmpty());
    }

    @Test public void knownDefaultRouteChangeStartsFirstAttemptWithoutFixedDelay() {
        controller.onConnected();
        assertTrue(controller.onNetworkChange());
        assertEquals(0, scheduler.delay);
        Runnable pending = scheduler.pending;
        assertTrue(controller.onNetworkChange());
        assertSame("duplicate route callbacks must coalesce", pending, scheduler.pending);
        scheduler.runPending();
        assertEquals(Arrays.asList(1), listener.attempts);
        assertTrue(controller.isCurrentAttempt(listener.lastGeneration()));
    }

    @Test public void routeChangeCannotAutomaticallyStartAnUnconnectedSession() {
        assertFalse(controller.onNetworkChange());
        assertFalse(controller.isRecovering());
        assertNull(scheduler.pending);
        assertTrue(listener.states.isEmpty());
    }

    @Test public void routeChangeUpgradesOnlyTheQueuedFirstGenericFailure() {
        controller.onConnected();
        controller.onFailure();
        Runnable delayed = scheduler.pending;
        assertEquals(1000, scheduler.delay);
        controller.onNetworkChange();
        assertEquals(0, scheduler.delay);
        Runnable immediate = scheduler.pending;
        assertNotSame(delayed, immediate);
        delayed.run();
        assertSame(immediate, scheduler.pending);
        assertTrue(listener.attempts.isEmpty());
        scheduler.runPending();
        assertEquals(Arrays.asList(1), listener.attempts);
    }

    @Test public void firstRouteAttemptWaitsForForegroundAndNetworkThenRunsImmediately() {
        controller.onConnected();
        controller.setForeground(false);
        controller.setNetworkAvailable(false);
        controller.onNetworkChange();
        assertNull(scheduler.pending);
        controller.setNetworkAvailable(true);
        assertNull(scheduler.pending);
        controller.setForeground(true);
        assertEquals(0, scheduler.delay);
        scheduler.runPending();
        assertEquals(Arrays.asList(1), listener.attempts);
    }

    @Test public void failedRouteAttemptKeepsExponentialBackoffDespiteDuplicateNetworkCallbacks() {
        controller.onConnected();
        controller.onNetworkChange();
        scheduler.runPending();
        controller.attemptFailed(listener.lastGeneration());
        assertEquals(2000, scheduler.delay);
        Runnable backoff = scheduler.pending;
        controller.onNetworkChange();
        controller.onNetworkChange();
        assertSame(backoff, scheduler.pending);
        assertEquals(2000, scheduler.delay);
        scheduler.runPending();
        controller.attemptFailed(listener.lastGeneration());
        assertEquals(4000, scheduler.delay);
    }

    @Test public void restoredAttemptHistoryCannotBeErasedByANetworkEvent() {
        controller.armForReconnect(3);
        controller.onNetworkChange();
        assertEquals(8000, scheduler.delay);
        assertEquals(3, controller.getAttemptCount());
    }

    @Test public void inFlightRouteAttemptAndExplicitStopNeverCreateAnOverlappingAttempt() {
        controller.onConnected();
        controller.onNetworkChange();
        Runnable canceled = scheduler.pending;
        controller.close();
        canceled.run();
        assertFalse(controller.onNetworkChange());
        assertNull(scheduler.pending);
        assertTrue(listener.attempts.isEmpty());
    }

    @Test
    public void retriesBackOffAndStayCappedForLongOutages() {
        controller.onConnected();
        assertTrue(controller.onFailure());
        long[] expectedDelays = {1000, 2000, 4000, 8000, 15000, 15000, 15000};
        for (int i = 0; i < expectedDelays.length; i++) {
            assertEquals(expectedDelays[i], scheduler.delay);
            scheduler.runPending();
            assertEquals(i + 1, listener.attempts.get(i).intValue());
            assertTrue(controller.isCurrentAttempt(listener.lastGeneration()));
            controller.attemptFailed(listener.lastGeneration());
        }
        assertEquals(Arrays.asList(true), listener.states);
        assertEquals(1, scheduler.maxPending);
    }

    @Test
    public void duplicateTerminationsDoNotResetTimerOrLaunchOverlappingAttempts() {
        controller.onConnected();
        assertTrue(controller.onFailure());
        Runnable firstTimer = scheduler.pending;
        assertTrue(controller.onFailure());
        assertSame(firstTimer, scheduler.pending);
        scheduler.runPending();
        assertTrue(controller.onFailure());
        assertNull(scheduler.pending);
        assertEquals(1, listener.attempts.size());
        assertEquals(Arrays.asList(true), listener.states);
    }

    @Test
    public void lateFailureCannotCancelOrRescheduleNewerAttempt() {
        startRecoveryAttempt();
        long first = listener.lastGeneration();
        controller.attemptFailed(first);
        Runnable secondTimer = scheduler.pending;
        controller.attemptFailed(first);
        assertSame(secondTimer, scheduler.pending);
        scheduler.runPending();
        long second = listener.lastGeneration();
        assertTrue(second > first);
        controller.attemptFailed(first);
        assertTrue(controller.isCurrentAttempt(second));
        assertNull(scheduler.pending);
    }

    @Test
    public void hiddenAppDoesNotPollAndReturningExpeditesRetry() {
        controller.onConnected();
        controller.onFailure();
        Runnable canceled = scheduler.pending;
        controller.setForeground(false);
        assertNull(scheduler.pending);
        canceled.run();
        assertTrue(listener.attempts.isEmpty());
        assertTrue(controller.isRecovering());
        controller.setForeground(true);
        assertEquals(500, scheduler.delay);
        scheduler.runPending();
        assertEquals(Arrays.asList(1), listener.attempts);
    }

    @Test
    public void failureWhileHiddenWaitsForForeground() {
        controller.onConnected();
        controller.setForeground(false);
        assertTrue(controller.onFailure());
        assertNull(scheduler.pending);
        controller.setForeground(true);
        assertEquals(500, scheduler.delay);
        scheduler.runPending();
        assertEquals(1, listener.attempts.size());
    }

    @Test
    public void offlineAppDoesNotPollAndNetworkReturnExpeditesRetry() {
        controller.onConnected();
        controller.setNetworkAvailable(false);
        controller.onFailure();
        assertNull(scheduler.pending);
        controller.setNetworkAvailable(true);
        assertEquals(500, scheduler.delay);
        scheduler.runPending();
        assertEquals(1, listener.attempts.size());
    }

    @Test
    public void hidingInFlightAttemptKeepsItsResultValidWithoutStartingAnother() {
        startRecoveryAttempt();
        long generation = listener.lastGeneration();
        controller.setForeground(false);
        assertTrue(controller.isCurrentAttempt(generation));
        controller.setForeground(true);
        assertNull(scheduler.pending);
        assertEquals(1, listener.attempts.size());
        controller.setForeground(false);
        controller.attemptFailed(generation);
        assertFalse(controller.isCurrentAttempt(generation));
        assertNull(scheduler.pending);
        controller.setForeground(true);
        assertEquals(500, scheduler.delay);
        scheduler.runPending();
        assertEquals(2, listener.attempts.size());
    }

    @Test
    public void networkFlapsDuringAttemptNeverLaunchOverlappingAttempt() {
        startRecoveryAttempt();
        long generation = listener.lastGeneration();
        for (int i = 0; i < 5; i++) {
            controller.setNetworkAvailable(false);
            controller.setNetworkAvailable(true);
        }
        assertTrue(controller.isCurrentAttempt(generation));
        assertNull(scheduler.pending);
        assertEquals(1, listener.attempts.size());
        controller.attemptFailed(generation);
        assertEquals(2000, scheduler.delay);
        scheduler.runPending();
        assertEquals(Arrays.asList(1, 2), listener.attempts);
    }

    @Test
    public void networkAndForegroundMustBothBeAvailable() {
        controller.onConnected();
        controller.setForeground(false);
        controller.setNetworkAvailable(false);
        controller.onFailure();
        controller.setNetworkAvailable(true);
        assertNull(scheduler.pending);
        controller.setForeground(true);
        assertNotNull(scheduler.pending);
        controller.setNetworkAvailable(false);
        assertNull(scheduler.pending);
        controller.setForeground(false);
        controller.setNetworkAvailable(true);
        assertNull(scheduler.pending);
        controller.setForeground(true);
        scheduler.runPending();
        assertEquals(1, listener.attempts.size());
    }

    @Test
    public void repeatedAvailableEventsDoNotPostponeScheduledRetry() {
        controller.onConnected();
        controller.onFailure();
        Runnable pending = scheduler.pending;
        controller.setForeground(true);
        controller.setNetworkAvailable(true);
        assertSame(pending, scheduler.pending);
        assertEquals(1000, scheduler.delay);
    }

    @Test
    public void canceledDispatchedRunnableCannotConsumeReplacementTimer() {
        controller.onConnected();
        controller.onFailure();
        Runnable canceled = scheduler.pending;
        controller.setForeground(false);
        controller.setForeground(true);
        Runnable replacement = scheduler.pending;
        canceled.run();
        assertSame(replacement, scheduler.pending);
        assertTrue(listener.attempts.isEmpty());
        scheduler.runPending();
        assertEquals(1, listener.attempts.size());
    }

    @Test
    public void successfulRecoveryResetsBackoffAndRejectsStaleAttemptResult() {
        startRecoveryAttempt();
        long generation = listener.lastGeneration();
        controller.onConnected();
        assertFalse(controller.isRecovering());
        assertFalse(controller.isCurrentAttempt(generation));
        controller.attemptFailed(generation);
        assertNull(scheduler.pending);
        assertEquals(Arrays.asList(true, false), listener.states);
        controller.onFailure();
        assertEquals(1000, scheduler.delay);
        scheduler.runPending();
        assertEquals(Arrays.asList(1, 1), listener.attempts);
        assertTrue(listener.lastGeneration() > generation);
    }

    @Test
    public void trustedHandoffPreservesBackoffUntilAStreamActuallyConnects() {
        controller.armForReconnect(3);
        assertEquals(3, controller.getAttemptCount());
        assertFalse(controller.isRecovering());
        assertTrue(listener.states.isEmpty());
        assertTrue(controller.onFailure());
        assertEquals(8000, scheduler.delay);
        scheduler.runPending();
        assertEquals(Arrays.asList(4), listener.attempts);
        assertEquals(4, controller.getAttemptCount());
        controller.onConnected();
        assertEquals(0, controller.getAttemptCount());
        controller.onFailure();
        assertEquals(1000, scheduler.delay);
        scheduler.runPending();
        assertEquals(Arrays.asList(4, 1), listener.attempts);
    }

    @Test
    public void restoringHandoffCannotReviveClosedController() {
        controller.close();
        controller.armForReconnect(3);
        assertFalse(controller.onFailure());
        assertEquals(0, controller.getAttemptCount());
        assertNull(scheduler.pending);
        assertTrue(listener.states.isEmpty());
    }

    @Test
    public void handoffCannotResetAnInFlightAttemptOrOverflowItsBackoff() {
        controller.armForReconnect(Integer.MAX_VALUE);
        controller.onFailure();
        assertEquals(15000, scheduler.delay);
        scheduler.runPending();
        long generation = listener.lastGeneration();
        assertEquals(Integer.MAX_VALUE, controller.getAttemptCount());
        controller.armForReconnect(0);
        assertEquals(Integer.MAX_VALUE, controller.getAttemptCount());
        assertTrue(controller.isCurrentAttempt(generation));
        controller.attemptFailed(generation);
        assertEquals(15000, scheduler.delay);
    }

    @Test
    public void successfulConnectionCancelsPendingRetry() {
        controller.onConnected();
        controller.onFailure();
        Runnable canceled = scheduler.pending;
        controller.onConnected();
        canceled.run();
        assertNull(scheduler.pending);
        assertTrue(listener.attempts.isEmpty());
        assertEquals(Arrays.asList(true, false), listener.states);
    }

    @Test
    public void closePermanentlyCancelsScheduledAndLateCallbacks() {
        controller.onConnected();
        controller.onFailure();
        Runnable canceled = scheduler.pending;
        controller.close();
        canceled.run();
        controller.close();
        controller.onConnected();
        controller.setForeground(false);
        controller.setForeground(true);
        controller.setNetworkAvailable(false);
        controller.setNetworkAvailable(true);
        assertFalse(controller.onFailure());
        assertFalse(controller.isRecovering());
        assertNull(scheduler.pending);
        assertTrue(listener.attempts.isEmpty());
        assertEquals(Arrays.asList(true, false), listener.states);
    }

    @Test
    public void closeInvalidatesInFlightAttempt() {
        startRecoveryAttempt();
        long generation = listener.lastGeneration();
        controller.close();
        assertFalse(controller.isCurrentAttempt(generation));
        controller.attemptFailed(generation);
        assertNull(scheduler.pending);
    }

    @Test
    public void synchronousAttemptFailureSchedulesExactlyOneNextAttempt() {
        StreamReconnectController[] holder = new StreamReconnectController[1];
        holder[0] = new StreamReconnectController(scheduler, new StreamReconnectController.Listener() {
            @Override public void onAttempt(int attempt, long generation) {
                holder[0].attemptFailed(generation);
            }
            @Override public void onRecoveryStateChanged(boolean recovering) { }
        });
        holder[0].onConnected();
        holder[0].onFailure();
        scheduler.runPending();
        assertEquals(2000, scheduler.delay);
        assertNotNull(scheduler.pending);
        assertEquals(1, scheduler.maxPending);
    }

    @Test
    public void closeFromRecoveryNotificationPreventsNewTimer() {
        StreamReconnectController[] holder = new StreamReconnectController[1];
        holder[0] = new StreamReconnectController(scheduler, new StreamReconnectController.Listener() {
            @Override public void onAttempt(int attempt, long generation) { fail("closed"); }
            @Override public void onRecoveryStateChanged(boolean recovering) {
                if (recovering) {
                    holder[0].close();
                }
            }
        });
        holder[0].onConnected();
        holder[0].onFailure();
        assertNull(scheduler.pending);
        assertFalse(holder[0].isRecovering());
    }

    private void startRecoveryAttempt() {
        controller.onConnected();
        assertTrue(controller.onFailure());
        scheduler.runPending();
    }

    private static final class RecordingListener implements StreamReconnectController.Listener {
        final List<Integer> attempts = new ArrayList<>();
        final List<Long> generations = new ArrayList<>();
        final List<Boolean> states = new ArrayList<>();
        @Override public void onAttempt(int attempt, long generation) {
            attempts.add(attempt);
            generations.add(generation);
        }
        @Override public void onRecoveryStateChanged(boolean recovering) { states.add(recovering); }
        long lastGeneration() { return generations.get(generations.size() - 1); }
    }

    private static final class FakeScheduler implements StreamReconnectController.Scheduler {
        Runnable pending;
        long delay;
        int maxPending;
        @Override public void postDelayed(Runnable runnable, long delayMillis) {
            assertNull("overlapping scheduled attempts", pending);
            pending = runnable;
            delay = delayMillis;
            maxPending = Math.max(maxPending, 1);
        }
        @Override public void remove(Runnable runnable) {
            if (pending == runnable) {
                pending = null;
            }
        }
        void runPending() {
            assertNotNull("no scheduled attempt", pending);
            Runnable runnable = pending;
            pending = null;
            runnable.run();
        }
    }
}
