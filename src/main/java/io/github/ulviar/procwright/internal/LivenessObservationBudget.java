/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Owns the deadline and provenance of one guarded liveness observation. */
final class LivenessObservationBudget {

    private final long observationDeadlineNanos;
    private final boolean lifecycleLimited;
    private final LongSupplier nanoTime;

    static LivenessObservationBudget fromRemainingLifecycle(
            Duration lifecycleBudget, Duration providerOperationTimeout) {
        return fromRemainingLifecycle(lifecycleBudget, providerOperationTimeout, System::nanoTime);
    }

    static LivenessObservationBudget untilLifecycleDeadline(
            long lifecycleDeadlineNanos, Duration providerOperationTimeout) {
        return untilLifecycleDeadline(lifecycleDeadlineNanos, providerOperationTimeout, System::nanoTime);
    }

    static LivenessObservationBudget providerLimited(Duration providerOperationTimeout) {
        return providerLimited(providerOperationTimeout, System::nanoTime);
    }

    static LivenessObservationBudget fromRemainingLifecycle(
            Duration lifecycleBudget, Duration providerOperationTimeout, LongSupplier nanoTime) {
        Duration lifecycle = DurationSupport.requireNonNegative(lifecycleBudget, "lifecycleBudget");
        Duration provider = DurationSupport.requirePositive(providerOperationTimeout, "providerOperationTimeout");
        LongSupplier clock = Objects.requireNonNull(nanoTime, "nanoTime");
        long started = clock.getAsLong();
        boolean lifecycleLimited = lifecycle.compareTo(provider) <= 0;
        Duration applied = lifecycleLimited ? lifecycle : provider;
        return new LivenessObservationBudget(DurationSupport.deadlineFrom(started, applied), lifecycleLimited, clock);
    }

    static LivenessObservationBudget untilLifecycleDeadline(
            long lifecycleDeadlineNanos, Duration providerOperationTimeout, LongSupplier nanoTime) {
        LongSupplier clock = Objects.requireNonNull(nanoTime, "nanoTime");
        long started = clock.getAsLong();
        long remaining = lifecycleDeadlineNanos - started;
        Duration lifecycleBudget = remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
        return fromRemainingLifecycle(lifecycleBudget, providerOperationTimeout, started, clock);
    }

    private static LivenessObservationBudget providerLimited(Duration providerOperationTimeout, LongSupplier nanoTime) {
        Duration provider = DurationSupport.requirePositive(providerOperationTimeout, "providerOperationTimeout");
        LongSupplier clock = Objects.requireNonNull(nanoTime, "nanoTime");
        return new LivenessObservationBudget(DurationSupport.deadlineFrom(clock.getAsLong(), provider), false, clock);
    }

    private static LivenessObservationBudget fromRemainingLifecycle(
            Duration lifecycleBudget, Duration providerOperationTimeout, long started, LongSupplier nanoTime) {
        Duration lifecycle = DurationSupport.requireNonNegative(lifecycleBudget, "lifecycleBudget");
        Duration provider = DurationSupport.requirePositive(providerOperationTimeout, "providerOperationTimeout");
        boolean lifecycleLimited = lifecycle.compareTo(provider) <= 0;
        Duration applied = lifecycleLimited ? lifecycle : provider;
        return new LivenessObservationBudget(
                DurationSupport.deadlineFrom(started, applied), lifecycleLimited, nanoTime);
    }

    private LivenessObservationBudget(long observationDeadlineNanos, boolean lifecycleLimited, LongSupplier nanoTime) {
        this.observationDeadlineNanos = observationDeadlineNanos;
        this.lifecycleLimited = lifecycleLimited;
        this.nanoTime = nanoTime;
    }

    Optional<Duration> remainingOperationBudget(String operation) {
        Objects.requireNonNull(operation, "operation");
        long remaining = observationDeadlineNanos - nanoTime.getAsLong();
        if (remaining > 0) {
            return Optional.of(Duration.ofNanos(remaining));
        }
        if (lifecycleLimited) {
            return Optional.empty();
        }
        throw ProcessTreeScanner.operationDeadlineExceeded(operation);
    }

    boolean lifecycleLimited() {
        return lifecycleLimited;
    }
}
