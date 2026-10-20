package com.limelight.reconnect;

/**
 * Main-thread-confined retry policy for a stream that has connected at least once.
 * The listener owns transport teardown and connection work; this class never performs I/O.
 */
public final class StreamReconnectController {
    public interface Scheduler {
        void postDelayed(Runnable runnable, long delayMillis);
        void remove(Runnable runnable);
    }

    public interface Listener {
        void onAttempt(int attempt, long generation);
        void onRecoveryStateChanged(boolean recovering);
    }

    private static final long INITIAL_DELAY_MS = 1000;
    private static final long RESUME_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 15000;

    private final Scheduler scheduler;
    private final Listener listener;
    private boolean hasConnected;
    private boolean closed;
    private boolean recovering;
    private boolean foreground = true;
    private boolean networkAvailable = true;
    private boolean attemptInFlight;
    private boolean immediateNetworkRecovery;
    private int attemptCount;
    private long generation;
    private Runnable pendingAttempt;

    public StreamReconnectController(Scheduler scheduler, Listener listener) {
        if (scheduler == null || listener == null) {
            throw new NullPointerException("scheduler and listener are required");
        }
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /** Call only for the currently owned successful connection. */
    public void onConnected() {
        if (closed) {
            return;
        }
        cancelPendingAttempt();
        hasConnected = true;
        attemptInFlight = false;
        attemptCount = 0;
        immediateNetworkRecovery = false;
        generation++;
        setRecovering(false);
    }

    /**
     * Restores retry eligibility and backoff from a trusted in-memory Activity handoff.
     * Host resolution alone is not a successful streaming connection.
     */
    public void armForReconnect(int priorAttempts) {
        if (closed || recovering) {
            return;
        }
        hasConnected = true;
        attemptCount = Math.max(0, priorAttempts);
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Returns whether this failure is handled by recovery. Duplicate termination callbacks
     * coalesce; an initial connection failure remains the caller's normal error path.
     */
    public boolean onFailure() {
        if (closed || !hasConnected) {
            return false;
        }
        if (!recovering) {
            setRecovering(true);
            scheduleAttempt(nextDelayMillis());
        }
        return true;
    }

    /** A confirmed default-route change needs no initial backoff on an already paired session. */
    public boolean onNetworkChange() {
        if (closed || !hasConnected) return false;
        if (!recovering) {
            immediateNetworkRecovery = attemptCount == 0;
            setRecovering(true);
            scheduleAttempt(immediateNetworkRecovery ? 0 : nextDelayMillis());
        } else if (attemptCount == 0 && !attemptInFlight && !immediateNetworkRecovery) {
            // Upgrade a queued generic failure once a real route-change event arrives.
            immediateNetworkRecovery = true;
            scheduleAttempt(0);
        }
        // Repeated callbacks cannot reset backoff or overlap an in-flight attempt.
        return true;
    }

    public void attemptFailed(long attemptGeneration) {
        if (!isCurrentAttempt(attemptGeneration)) {
            return;
        }
        attemptInFlight = false;
        scheduleAttempt(nextDelayMillis());
    }

    /** Includes an attempt finishing while hidden or offline, so its result can settle safely. */
    public boolean isCurrentAttempt(long attemptGeneration) {
        return !closed && recovering && attemptInFlight && generation == attemptGeneration;
    }

    public void setForeground(boolean visible) {
        if (closed || foreground == visible) {
            return;
        }
        foreground = visible;
        if (visible) {
            scheduleAttempt(firstAvailableAttemptDelay());
        } else {
            cancelPendingAttempt();
        }
    }

    public void setNetworkAvailable(boolean available) {
        if (closed || networkAvailable == available) {
            return;
        }
        networkAvailable = available;
        if (available) {
            scheduleAttempt(firstAvailableAttemptDelay());
        } else {
            cancelPendingAttempt();
        }
    }

    /** Permanently stops recovery. A new streaming activity must create its own controller. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        generation++;
        attemptInFlight = false;
        cancelPendingAttempt();
        setRecovering(false);
    }

    public boolean isRecovering() {
        return recovering;
    }

    private void setRecovering(boolean value) {
        if (recovering != value) {
            recovering = value;
            listener.onRecoveryStateChanged(value);
        }
    }

    private long nextDelayMillis() {
        // Cap the shift to avoid overflow during a prolonged outage.
        return Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS << Math.min(attemptCount, 4));
    }

    private long firstAvailableAttemptDelay() {
        return immediateNetworkRecovery && attemptCount == 0 ? 0 : RESUME_DELAY_MS;
    }

    private void cancelPendingAttempt() {
        if (pendingAttempt != null) {
            scheduler.remove(pendingAttempt);
            pendingAttempt = null;
        }
    }

    private void scheduleAttempt(long delayMillis) {
        if (closed || !recovering || attemptInFlight || !foreground || !networkAvailable) {
            return;
        }
        cancelPendingAttempt();
        pendingAttempt = new Runnable() {
            @Override
            public void run() {
                // A callback may already be dispatched when remove() is called.
                if (pendingAttempt != this) {
                    return;
                }
                pendingAttempt = null;
                if (closed || !recovering || attemptInFlight || !foreground || !networkAvailable) {
                    return;
                }
                attemptInFlight = true;
                if (attemptCount < Integer.MAX_VALUE) {
                    attemptCount++;
                }
                long attemptGeneration = ++generation;
                listener.onAttempt(attemptCount, attemptGeneration);
            }
        };
        scheduler.postDelayed(pendingAttempt, delayMillis);
    }
}
