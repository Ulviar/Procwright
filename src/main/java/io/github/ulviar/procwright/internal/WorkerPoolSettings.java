/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Immutable pool capacity, lifecycle, and worker-hook settings. */
public record WorkerPoolSettings<W>(
        int maxSize,
        int warmupSize,
        int minIdle,
        Duration acquireTimeout,
        Duration hookTimeout,
        Duration closeTimeout,
        int maxRequestsPerWorker,
        Duration maxWorkerAge,
        boolean backgroundReplenishment,
        Consumer<W> resetHook,
        Predicate<W> healthCheck) {

    /** Maximum worker count supported by the process-wide retirement lifecycle. */
    public static final int MAX_SIZE = 256;

    public WorkerPoolSettings {
        maxSize = positive(maxSize, "maxSize");
        warmupSize = nonNegative(warmupSize, "warmupSize");
        minIdle = nonNegative(minIdle, "minIdle");
        acquireTimeout = DurationSupport.requirePositive(acquireTimeout, "acquireTimeout");
        hookTimeout = DurationSupport.requirePositive(hookTimeout, "hookTimeout");
        closeTimeout = DurationSupport.requirePositive(closeTimeout, "closeTimeout");
        maxRequestsPerWorker = positive(maxRequestsPerWorker, "maxRequestsPerWorker");
        maxWorkerAge = DurationSupport.requireNonNegative(maxWorkerAge, "maxWorkerAge");
        Objects.requireNonNull(resetHook, "resetHook");
        Objects.requireNonNull(healthCheck, "healthCheck");
    }

    public static <W> WorkerPoolSettings<W> defaults(Consumer<W> resetHook, Predicate<W> healthCheck) {
        return new WorkerPoolSettings<>(
                1,
                0,
                0,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Integer.MAX_VALUE,
                Duration.ZERO,
                true,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> validateForOpen() {
        if (maxSize > MAX_SIZE) {
            throw new IllegalArgumentException("maxSize must not exceed " + MAX_SIZE);
        }
        if (warmupSize > maxSize) {
            throw new IllegalArgumentException("warmupSize must not exceed maxSize");
        }
        if (minIdle > maxSize) {
            throw new IllegalArgumentException("minIdle must not exceed maxSize");
        }
        if (minIdle > 0 && !backgroundReplenishment) {
            throw new IllegalArgumentException("minIdle requires backgroundReplenishment");
        }
        return this;
    }

    public WorkerPoolSettings<W> withMaxSize(int value) {
        return copy(
                value,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withWarmupSize(int value) {
        return copy(
                maxSize,
                value,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withMinIdle(int value) {
        return copy(
                maxSize,
                warmupSize,
                value,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withAcquireTimeout(Duration value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                value,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withHookTimeout(Duration value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                value,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withCloseTimeout(Duration value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                value,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withMaxRequestsPerWorker(int value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                value,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withMaxWorkerAge(Duration value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                value,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withBackgroundReplenishment(boolean value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                value,
                resetHook,
                healthCheck);
    }

    public WorkerPoolSettings<W> withResetHook(Consumer<W> value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                value,
                healthCheck);
    }

    public WorkerPoolSettings<W> withHealthCheck(Predicate<W> value) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                value);
    }

    private static <W> WorkerPoolSettings<W> copy(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            Duration hookTimeout,
            Duration closeTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment,
            Consumer<W> resetHook,
            Predicate<W> healthCheck) {
        return new WorkerPoolSettings<>(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                closeTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
