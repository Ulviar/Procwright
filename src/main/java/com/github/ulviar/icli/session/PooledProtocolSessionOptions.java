package com.github.ulviar.icli.session;

import java.time.Duration;
import java.util.Objects;

/**
 * Default pooling policies for typed protocol-session workers.
 */
public final class PooledProtocolSessionOptions {

    private static final PooledProtocolSessionOptions DEFAULTS = new PooledProtocolSessionOptions(
            1, 0, 0, Duration.ofSeconds(5), Duration.ofSeconds(5), Integer.MAX_VALUE, Duration.ZERO, true);

    private final int maxSize;
    private final int warmupSize;
    private final int minIdle;
    private final Duration acquireTimeout;
    private final Duration hookTimeout;
    private final int maxRequestsPerWorker;
    private final Duration maxWorkerAge;
    private final boolean backgroundReplenishment;

    /**
     * Creates pooled protocol-session options from explicit policies.
     *
     * @param maxSize maximum live workers
     * @param warmupSize workers opened when the pool is created
     * @param minIdle minimum idle workers the pool tries to replenish in the background
     * @param acquireTimeout maximum time to wait for an available worker
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     */
    public PooledProtocolSessionOptions(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment) {
        this(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                Duration.ofSeconds(5),
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Creates pooled protocol-session options from explicit policies.
     *
     * @param maxSize maximum live workers
     * @param warmupSize workers opened when the pool is created
     * @param minIdle minimum idle workers the pool tries to replenish in the background
     * @param acquireTimeout maximum time to wait for an available worker
     * @param hookTimeout maximum time to wait for one health or reset hook
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     */
    public PooledProtocolSessionOptions(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            Duration hookTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (warmupSize < 0) {
            throw new IllegalArgumentException("warmupSize must not be negative");
        }
        if (minIdle < 0) {
            throw new IllegalArgumentException("minIdle must not be negative");
        }
        if (warmupSize > maxSize) {
            throw new IllegalArgumentException("warmupSize must not exceed maxSize");
        }
        if (minIdle > maxSize) {
            throw new IllegalArgumentException("minIdle must not exceed maxSize");
        }
        if (maxRequestsPerWorker <= 0) {
            throw new IllegalArgumentException("maxRequestsPerWorker must be positive");
        }
        this.maxSize = maxSize;
        this.warmupSize = warmupSize;
        this.minIdle = minIdle;
        this.acquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
        this.hookTimeout = requirePositive(hookTimeout, "hookTimeout");
        this.maxRequestsPerWorker = maxRequestsPerWorker;
        this.maxWorkerAge = requireNonNegative(maxWorkerAge, "maxWorkerAge");
        this.backgroundReplenishment = backgroundReplenishment;
    }

    /**
     * Returns default pooled protocol-session options.
     *
     * @return default options
     */
    public static PooledProtocolSessionOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different maximum pool size.
     *
     * @param maxSize maximum live workers
     * @return updated options
     */
    public PooledProtocolSessionOptions withMaxSize(int maxSize) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different warmup size.
     *
     * @param warmupSize workers opened when the pool is created
     * @return updated options
     */
    public PooledProtocolSessionOptions withWarmupSize(int warmupSize) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different minimum idle target.
     *
     * @param minIdle minimum idle workers to keep ready
     * @return updated options
     */
    public PooledProtocolSessionOptions withMinIdle(int minIdle) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different worker acquire timeout.
     *
     * @param acquireTimeout maximum time to wait for a worker
     * @return updated options
     */
    public PooledProtocolSessionOptions withAcquireTimeout(Duration acquireTimeout) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different health/reset hook timeout.
     *
     * @param hookTimeout maximum time to wait for one hook
     * @return updated options
     */
    public PooledProtocolSessionOptions withHookTimeout(Duration hookTimeout) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different per-worker request limit.
     *
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @return updated options
     */
    public PooledProtocolSessionOptions withMaxRequestsPerWorker(int maxRequestsPerWorker) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different maximum worker age.
     *
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @return updated options
     */
    public PooledProtocolSessionOptions withMaxWorkerAge(Duration maxWorkerAge) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns a copy with a different background replenishment policy.
     *
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     * @return updated options
     */
    public PooledProtocolSessionOptions withBackgroundReplenishment(boolean backgroundReplenishment) {
        return new PooledProtocolSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    /**
     * Returns maximum live workers.
     *
     * @return max size
     */
    public int maxSize() {
        return maxSize;
    }

    /**
     * Returns workers opened when the pool is created.
     *
     * @return warmup size
     */
    public int warmupSize() {
        return warmupSize;
    }

    /**
     * Returns the minimum idle target.
     *
     * @return minimum idle workers
     */
    public int minIdle() {
        return minIdle;
    }

    /**
     * Returns maximum time to wait for a worker.
     *
     * @return acquire timeout
     */
    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    /**
     * Returns maximum time to wait for one health or reset hook.
     *
     * @return hook timeout
     */
    public Duration hookTimeout() {
        return hookTimeout;
    }

    /**
     * Returns maximum user requests served by one worker.
     *
     * @return request limit
     */
    public int maxRequestsPerWorker() {
        return maxRequestsPerWorker;
    }

    /**
     * Returns maximum worker age.
     *
     * @return maximum worker age, or {@link Duration#ZERO} when disabled
     */
    public Duration maxWorkerAge() {
        return maxWorkerAge;
    }

    /**
     * Returns whether retired workers may be replenished in the background.
     *
     * @return background replenishment flag
     */
    public boolean backgroundReplenishment() {
        return backgroundReplenishment;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PooledProtocolSessionOptions that)) {
            return false;
        }
        return maxSize == that.maxSize
                && warmupSize == that.warmupSize
                && minIdle == that.minIdle
                && maxRequestsPerWorker == that.maxRequestsPerWorker
                && backgroundReplenishment == that.backgroundReplenishment
                && acquireTimeout.equals(that.acquireTimeout)
                && hookTimeout.equals(that.hookTimeout)
                && maxWorkerAge.equals(that.maxWorkerAge);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
