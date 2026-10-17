package com.authswitch.resilience;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Thread-safe circuit breaker modelled as an explicit state machine:
 * <pre>
 *   CLOSED --(N consecutive failures)--> OPEN --(cool-down elapsed)--> HALF_OPEN
 *   HALF_OPEN --(probe succeeds)--> CLOSED        HALF_OPEN --(probe fails)--> OPEN
 * </pre>
 * While HALF_OPEN exactly one probe request is admitted; everyone else is turned away.
 * The clock is injectable so state transitions are testable without sleeping.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;
    private final LongSupplier clockMillis;
    private final Consumer<State> onTransition;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, long openMillis, LongSupplier clockMillis, Consumer<State> onTransition) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.clockMillis = clockMillis;
        this.onTransition = onTransition;
    }

    /** @return true if the caller may contact the issuer. Every true must be followed by success/failure. */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clockMillis.getAsLong() - openedAt >= openMillis) {
                    transition(State.HALF_OPEN);
                    probeInFlight = true;
                    return true;
                }
                return false;
            default: // HALF_OPEN
                if (!probeInFlight) {
                    probeInFlight = true;
                    return true;
                }
                return false;
        }
    }

    public void recordSuccess() {
        State notify = null;
        synchronized (this) {
            consecutiveFailures = 0;
            probeInFlight = false;
            if (state == State.HALF_OPEN) notify = transitionQuietly(State.CLOSED);
        }
        if (notify != null) onTransition.accept(notify);
    }

    public void recordFailure() {
        State notify = null;
        synchronized (this) {
            probeInFlight = false;
            if (state == State.HALF_OPEN) {
                openedAt = clockMillis.getAsLong();
                notify = transitionQuietly(State.OPEN);
            } else if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
                openedAt = clockMillis.getAsLong();
                notify = transitionQuietly(State.OPEN);
            }
        }
        if (notify != null) onTransition.accept(notify);
    }

    public synchronized State state() {
        return state;
    }

    private void transition(State to) {
        State n = transitionQuietly(to);
        onTransition.accept(n); // reentrant-safe: listener must not call back into this breaker
    }

    private State transitionQuietly(State to) {
        state = to;
        return to;
    }
}
