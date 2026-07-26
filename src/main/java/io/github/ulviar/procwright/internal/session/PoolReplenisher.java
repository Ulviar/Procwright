/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns one-at-a-time scheduling, retry backoff, and stop races for background replenishment. */
final class PoolReplenisher {

    private static final Duration MIN_BACKOFF = Duration.ofMillis(10);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(250);

    private final Scheduler scheduler;
    private final BooleanSupplier needed;
    private final Supplier<Step> step;
    private final Consumer<Throwable> fatalFailure;
    private boolean active;
    private boolean stopped;
    private PoolScheduledAttempt pending;

    PoolReplenisher(
            Scheduler scheduler, BooleanSupplier needed, Supplier<Step> step, Consumer<Throwable> fatalFailure) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.needed = Objects.requireNonNull(needed, "needed");
        this.step = Objects.requireNonNull(step, "step");
        this.fatalFailure = Objects.requireNonNull(fatalFailure, "fatalFailure");
    }

    void ensureStarted() {
        if (!needed.getAsBoolean()) {
            return;
        }
        synchronized (this) {
            if (active || stopped) {
                return;
            }
            active = true;
        }
        start(Duration.ZERO);
    }

    private void start(Duration backoff) {
        PoolScheduledAttempt attempt;
        synchronized (this) {
            if (stopped) {
                active = false;
                return;
            }
            attempt = new PoolScheduledAttempt(selected -> run(selected, backoff));
            pending = attempt;
        }
        try {
            attempt.attach(scheduler.schedule(attempt, backoff));
        } catch (RuntimeException schedulingFailure) {
            discard(attempt);
            fail(schedulingFailure);
        } catch (Error schedulingFailure) {
            discard(attempt);
            fail(schedulingFailure);
            throw schedulingFailure;
        }
    }

    void stop() {
        PoolScheduledAttempt attempt;
        synchronized (this) {
            stopped = true;
            active = false;
            attempt = pending;
            pending = null;
        }
        if (attempt != null) {
            attempt.cancel();
        }
    }

    private void run(PoolScheduledAttempt attempt, Duration initialBackoff) {
        if (!claim(attempt)) {
            return;
        }
        Duration nextDelay;
        try {
            if (!needed.getAsBoolean()) {
                nextDelay = null;
            } else {
                nextDelay = switch (step.get()) {
                    case SUCCESS -> Duration.ZERO;
                    case RETRY -> nextBackoff(initialBackoff);
                    case STOP -> null;
                };
                if (nextDelay != null && !needed.getAsBoolean()) {
                    nextDelay = null;
                }
            }
        } catch (RuntimeException | Error failure) {
            Throwable terminalFailure = failure;
            try {
                fail(failure);
            } catch (RuntimeException | Error reportingFailure) {
                terminalFailure = FailureAggregation.combine(
                        terminalFailure,
                        reportingFailure,
                        "Pool replenishment and terminal failure handling both failed");
            }
            rethrow(terminalFailure);
            throw new AssertionError("unreachable");
        }
        if (nextDelay == null) {
            restartIfNeeded();
        } else {
            start(nextDelay);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    private void deactivate() {
        synchronized (this) {
            active = false;
        }
    }

    private boolean claim(PoolScheduledAttempt attempt) {
        synchronized (this) {
            if (pending != attempt) {
                return false;
            }
            pending = null;
            return !stopped;
        }
    }

    private void discard(PoolScheduledAttempt attempt) {
        synchronized (this) {
            if (pending == attempt) {
                pending = null;
            }
        }
        attempt.cancel();
    }

    private void restartIfNeeded() {
        deactivate();
        ensureStarted();
    }

    private void fail(Throwable failure) {
        try {
            fatalFailure.accept(failure);
        } finally {
            deactivate();
        }
    }

    private static Duration nextBackoff(Duration current) {
        if (current.isZero()) {
            return MIN_BACKOFF;
        }
        long doubledMillis = Math.min(MAX_BACKOFF.toMillis(), Math.multiplyExact(current.toMillis(), 2));
        return Duration.ofMillis(doubledMillis);
    }

    enum Step {
        SUCCESS,
        RETRY,
        STOP
    }

    @FunctionalInterface
    interface Scheduler {

        PoolScheduledAttempt.Cancellation schedule(Runnable task, Duration delay);
    }
}
