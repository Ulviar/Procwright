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
    private boolean stopped;
    private Attempt active;

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
        Attempt attempt;
        synchronized (this) {
            if (active != null || stopped) {
                return;
            }
            active = attempt = new Attempt(Duration.ZERO);
        }
        schedule(attempt);
    }

    private void schedule(Attempt attempt) {
        try {
            Cancellation cancellation = Objects.requireNonNull(
                    scheduler.schedule(() -> run(attempt), attempt.backoff), "scheduler returned null cancellation");
            boolean cancelNow;
            synchronized (this) {
                cancelNow = active != attempt && !attempt.started;
                if (active == attempt && !attempt.started) {
                    attempt.cancellation = cancellation;
                }
            }
            if (cancelNow) {
                cancellation.cancel();
            }
        } catch (RuntimeException schedulingFailure) {
            fail(schedulingFailure);
        } catch (Error schedulingFailure) {
            fail(schedulingFailure);
            throw schedulingFailure;
        }
    }

    void stop() {
        Cancellation cancellation;
        synchronized (this) {
            stopped = true;
            cancellation = active == null ? null : active.cancellation;
            active = null;
        }
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    private void run(Attempt attempt) {
        synchronized (this) {
            if (active != attempt || attempt.started) {
                return;
            }
            attempt.started = true;
            attempt.cancellation = null;
        }
        Duration nextDelay;
        try {
            if (!needed.getAsBoolean()) {
                nextDelay = null;
            } else {
                nextDelay = switch (step.get()) {
                    case SUCCESS -> Duration.ZERO;
                    case RETRY -> nextBackoff(attempt.backoff);
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
        Attempt next;
        synchronized (this) {
            if (active != attempt) {
                return;
            }
            active = next = nextDelay == null ? null : new Attempt(nextDelay);
        }
        if (next != null) {
            schedule(next);
        } else {
            ensureStarted();
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    private void fail(Throwable failure) {
        stop();
        fatalFailure.accept(failure);
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

        Cancellation schedule(Runnable task, Duration delay);
    }

    @FunctionalInterface
    interface Cancellation {

        Cancellation NONE = () -> {};

        void cancel();
    }

    /** All mutable fields belong to the replenisher monitor, including attachment after execution or stop. */
    private static final class Attempt {

        private final Duration backoff;
        private boolean started;
        private Cancellation cancellation;

        private Attempt(Duration backoff) {
            this.backoff = backoff;
        }
    }
}
