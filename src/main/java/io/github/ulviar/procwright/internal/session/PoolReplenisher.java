/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns single-runner scheduling, retry backoff, and stop races for background replenishment. */
final class PoolReplenisher {

    private static final Duration MIN_BACKOFF = Duration.ofMillis(10);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(250);

    private final boolean enabled;
    private final Consumer<Runnable> starter;
    private final BooleanSupplier needed;
    private final Supplier<Step> step;
    private final Waiter waiter;
    private final Consumer<Throwable> fatalFailure;
    private boolean active;

    PoolReplenisher(
            boolean enabled,
            Consumer<Runnable> starter,
            BooleanSupplier needed,
            Supplier<Step> step,
            Waiter waiter,
            Consumer<Throwable> fatalFailure) {
        this.enabled = enabled;
        this.starter = Objects.requireNonNull(starter, "starter");
        this.needed = Objects.requireNonNull(needed, "needed");
        this.step = Objects.requireNonNull(step, "step");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
        this.fatalFailure = Objects.requireNonNull(fatalFailure, "fatalFailure");
    }

    void ensureStarted() {
        if (!enabled || !needed.getAsBoolean()) {
            return;
        }
        synchronized (this) {
            if (active) {
                return;
            }
            active = true;
        }
        start(Duration.ZERO);
    }

    private void start(Duration backoff) {
        try {
            starter.accept(() -> run(backoff));
        } catch (RuntimeException schedulingFailure) {
            fail(schedulingFailure);
        } catch (Error schedulingFailure) {
            fail(schedulingFailure);
            throw schedulingFailure;
        }
    }

    private void run(Duration initialBackoff) {
        Duration backoff = initialBackoff;
        try {
            while (waiter.await(backoff)) {
                switch (step.get()) {
                    case SUCCESS -> backoff = Duration.ZERO;
                    case RETRY -> backoff = nextBackoff(backoff);
                    case STOP -> {
                        if (!reactivateIfNeeded()) {
                            return;
                        }
                        backoff = Duration.ZERO;
                    }
                }
            }
            deactivate();
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

    private boolean reactivateIfNeeded() {
        deactivate();
        if (!enabled || !needed.getAsBoolean()) {
            return false;
        }
        synchronized (this) {
            if (active) {
                return false;
            }
            active = true;
            return true;
        }
    }

    private void fail(Throwable failure) {
        deactivate();
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
    interface Waiter {

        boolean await(Duration backoff);
    }
}
