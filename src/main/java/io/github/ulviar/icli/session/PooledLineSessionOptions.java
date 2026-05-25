package io.github.ulviar.icli.session;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Default policies for pooled line-oriented sessions.
 */
public final class PooledLineSessionOptions {

    private static final Consumer<LineSession> NO_RESET = worker -> {};
    private static final Predicate<LineSession> PROCESS_ALIVE =
            worker -> !worker.onExit().isDone();
    private static final PooledLineSessionOptions DEFAULTS = new PooledLineSessionOptions(
            1,
            0,
            0,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Integer.MAX_VALUE,
            Duration.ZERO,
            true,
            NO_RESET,
            PROCESS_ALIVE);

    private final int maxSize;
    private final int warmupSize;
    private final int minIdle;
    private final Duration acquireTimeout;
    private final Duration hookTimeout;
    private final int maxRequestsPerWorker;
    private final Duration maxWorkerAge;
    private final boolean backgroundReplenishment;
    private final Consumer<LineSession> resetHook;
    private final Predicate<LineSession> healthCheck;

    /**
     * Creates pooled line-session options from explicit policies.
     *
     * @param maxSize maximum live workers
     * @param warmupSize workers opened when the pool is created
     * @param acquireTimeout maximum time to wait for an available worker
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @param resetHook hook run after a successful request before returning a worker to the pool
     * @param healthCheck hook run before a worker is leased
     */
    public PooledLineSessionOptions(
            int maxSize,
            int warmupSize,
            Duration acquireTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            Consumer<LineSession> resetHook,
            Predicate<LineSession> healthCheck) {
        this(
                maxSize,
                warmupSize,
                0,
                acquireTimeout,
                Duration.ofSeconds(5),
                maxRequestsPerWorker,
                maxWorkerAge,
                true,
                resetHook,
                healthCheck);
    }

    /**
     * Creates pooled line-session options from explicit policies.
     *
     * @param maxSize maximum live workers
     * @param warmupSize workers opened when the pool is created
     * @param minIdle minimum idle workers the pool tries to replenish in the background
     * @param acquireTimeout maximum time to wait for an available worker
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     * @param resetHook hook run after a successful request before returning a worker to the pool
     * @param healthCheck hook run before a worker is leased
     */
    public PooledLineSessionOptions(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment,
            Consumer<LineSession> resetHook,
            Predicate<LineSession> healthCheck) {
        this(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                Duration.ofSeconds(5),
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Creates pooled line-session options from explicit policies.
     *
     * @param maxSize maximum live workers
     * @param warmupSize workers opened when the pool is created
     * @param minIdle minimum idle workers the pool tries to replenish in the background
     * @param acquireTimeout maximum time to wait for an available worker
     * @param hookTimeout maximum time to wait for one health or reset hook
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     * @param resetHook hook run after a successful request before returning a worker to the pool
     * @param healthCheck hook run before a worker is leased
     */
    public PooledLineSessionOptions(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            Duration hookTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment,
            Consumer<LineSession> resetHook,
            Predicate<LineSession> healthCheck) {
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
        this.resetHook = Objects.requireNonNull(resetHook, "resetHook");
        this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
    }

    /**
     * Returns default pooled line-session options.
     *
     * @return default options
     */
    public static PooledLineSessionOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different maximum pool size.
     *
     * @param maxSize maximum live workers
     * @return updated options
     */
    public PooledLineSessionOptions withMaxSize(int maxSize) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different warmup size.
     *
     * @param warmupSize workers opened when the pool is created
     * @return updated options
     */
    public PooledLineSessionOptions withWarmupSize(int warmupSize) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different minimum idle target.
     *
     * @param minIdle minimum idle workers to keep ready
     * @return updated options
     */
    public PooledLineSessionOptions withMinIdle(int minIdle) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different worker acquire timeout.
     *
     * @param acquireTimeout maximum time to wait for a worker
     * @return updated options
     */
    public PooledLineSessionOptions withAcquireTimeout(Duration acquireTimeout) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different health/reset hook timeout.
     *
     * @param hookTimeout maximum time to wait for one hook
     * @return updated options
     */
    public PooledLineSessionOptions withHookTimeout(Duration hookTimeout) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different per-worker request limit.
     *
     * @param maxRequestsPerWorker maximum user requests served by one worker
     * @return updated options
     */
    public PooledLineSessionOptions withMaxRequestsPerWorker(int maxRequestsPerWorker) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different maximum worker age.
     *
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @return updated options
     */
    public PooledLineSessionOptions withMaxWorkerAge(Duration maxWorkerAge) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different background replenishment policy.
     *
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     * @return updated options
     */
    public PooledLineSessionOptions withBackgroundReplenishment(boolean backgroundReplenishment) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different reset hook.
     *
     * @param resetHook hook run after a successful user request
     * @return updated options
     */
    public PooledLineSessionOptions withReset(Consumer<LineSession> resetHook) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    /**
     * Returns a copy with a different health check.
     *
     * @param healthCheck hook run before a worker is leased
     * @return updated options
     */
    public PooledLineSessionOptions withHealthCheck(Predicate<LineSession> healthCheck) {
        return copy(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
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

    /**
     * Returns the reset hook.
     *
     * @return reset hook
     */
    public Consumer<LineSession> resetHook() {
        return resetHook;
    }

    /**
     * Returns the health check.
     *
     * @return health check
     */
    public Predicate<LineSession> healthCheck() {
        return healthCheck;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PooledLineSessionOptions that)) {
            return false;
        }
        return maxSize == that.maxSize
                && warmupSize == that.warmupSize
                && minIdle == that.minIdle
                && maxRequestsPerWorker == that.maxRequestsPerWorker
                && backgroundReplenishment == that.backgroundReplenishment
                && acquireTimeout.equals(that.acquireTimeout)
                && hookTimeout.equals(that.hookTimeout)
                && maxWorkerAge.equals(that.maxWorkerAge)
                && resetHook.equals(that.resetHook)
                && healthCheck.equals(that.healthCheck);
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
                backgroundReplenishment,
                resetHook,
                healthCheck);
    }

    private PooledLineSessionOptions copy(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            Duration hookTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment,
            Consumer<LineSession> resetHook,
            Predicate<LineSession> healthCheck) {
        return new PooledLineSessionOptions(
                maxSize,
                warmupSize,
                minIdle,
                acquireTimeout,
                hookTimeout,
                maxRequestsPerWorker,
                maxWorkerAge,
                backgroundReplenishment,
                resetHook,
                healthCheck);
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
