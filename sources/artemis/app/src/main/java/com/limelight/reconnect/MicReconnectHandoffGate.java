package com.limelight.reconnect;

/**
 * Waits for the previous host session to release its microphone owner before a replacement
 * session starts. This gate belongs on the serialized stream worker, never the UI thread.
 */
public final class MicReconnectHandoffGate {
    public interface Eligibility {
        boolean allow();
    }

    interface Clock {
        long nowMillis();
    }

    interface Waiter {
        void waitMillis(long milliseconds) throws InterruptedException;
    }

    private static final long MICROPHONE_HANDOFF_MILLIS = 12000;
    private static final long MAX_WAIT_CHUNK_MILLIS = 250;

    private final Clock clock;
    private final Waiter waiter;
    private volatile long readyAtMillis;

    public MicReconnectHandoffGate() {
        this(new Clock() {
            @Override public long nowMillis() { return System.nanoTime() / 1000000L; }
        }, new Waiter() {
            @Override public void waitMillis(long milliseconds) throws InterruptedException {
                Thread.sleep(milliseconds);
            }
        });
    }

    MicReconnectHandoffGate(Clock clock, Waiter waiter) {
        if (clock == null || waiter == null) {
            throw new NullPointerException("clock and waiter are required");
        }
        this.clock = clock;
        this.waiter = waiter;
        // nanoTime() has an arbitrary origin and may be negative.
        readyAtMillis = clock.nowMillis();
    }

    /** Call after native transport stop has completed, not when stop is merely requested. */
    public void transportStopped(boolean microphoneEnabled) {
        readyAtMillis = clock.nowMillis() +
                (microphoneEnabled ? MICROPHONE_HANDOFF_MILLIS : 0);
    }

    /**
     * Returns false as soon as recovery is no longer eligible. Interrupts propagate to the
     * worker, and no wait exceeds 250 ms so an app becoming hidden is noticed promptly.
     */
    public boolean awaitReady(Eligibility eligibility) throws InterruptedException {
        return awaitReady(eligibility, false);
    }

    /** Bypass the legacy owner timeout only for a capability from an authenticated PC probe. */
    public boolean awaitReady(Eligibility eligibility, boolean supportsFastMicHandoff) throws InterruptedException {
        if (eligibility == null) {
            throw new NullPointerException("eligibility is required");
        }
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Microphone reconnect handoff interrupted");
            }
            if (!eligibility.allow()) {
                return false;
            }
            long remainingMillis = supportsFastMicHandoff ? 0 : readyAtMillis - clock.nowMillis();
            if (remainingMillis <= 0) {
                // Closing/hiding may have raced the clock read at the deadline.
                return eligibility.allow();
            }
            waiter.waitMillis(Math.min(MAX_WAIT_CHUNK_MILLIS, remainingMillis));
        }
    }
}
