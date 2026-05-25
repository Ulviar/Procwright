package io.github.ulviar.icli;

import io.github.ulviar.icli.session.LineSession;
import io.github.ulviar.icli.session.LineSessionInvocation;
import io.github.ulviar.icli.session.LineSessionOptions;
import io.github.ulviar.icli.session.PooledLineSession;
import io.github.ulviar.icli.session.PooledLineSessionOptions;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Configures a pool of line-oriented request/response workers.
 */
public final class PooledLineSessionScenario {

    private final CommandService service;
    private final Consumer<LineSessionInvocation.Builder> configureWorker;
    private final LineSessionOptions lineOptions;
    private final PooledLineSessionOptions poolOptions;

    PooledLineSessionScenario(
            CommandService service,
            Consumer<LineSessionInvocation.Builder> configureWorker,
            LineSessionOptions lineOptions,
            PooledLineSessionOptions poolOptions) {
        this.service = Objects.requireNonNull(service, "service");
        this.configureWorker = Objects.requireNonNull(configureWorker, "configureWorker");
        this.lineOptions = Objects.requireNonNull(lineOptions, "lineOptions");
        this.poolOptions = Objects.requireNonNull(poolOptions, "poolOptions");
    }

    /**
     * Returns a copy with a different maximum live worker count.
     *
     * @param maxSize maximum live workers
     * @return updated scenario
     */
    public PooledLineSessionScenario withMaxSize(int maxSize) {
        return withOptions(poolOptions.withMaxSize(maxSize));
    }

    /**
     * Returns a copy with a different eager warmup size.
     *
     * @param warmupSize workers opened when the pool is created
     * @return updated scenario
     */
    public PooledLineSessionScenario withWarmupSize(int warmupSize) {
        return withOptions(poolOptions.withWarmupSize(warmupSize));
    }

    /**
     * Returns a copy with a different minimum idle target.
     *
     * @param minIdle minimum idle workers
     * @return updated scenario
     */
    public PooledLineSessionScenario withMinIdle(int minIdle) {
        return withOptions(poolOptions.withMinIdle(minIdle));
    }

    /**
     * Returns a copy with a different worker acquire timeout.
     *
     * @param acquireTimeout acquire timeout
     * @return updated scenario
     */
    public PooledLineSessionScenario withAcquireTimeout(Duration acquireTimeout) {
        return withOptions(poolOptions.withAcquireTimeout(acquireTimeout));
    }

    /**
     * Returns a copy with a different health/reset hook timeout.
     *
     * @param hookTimeout hook timeout
     * @return updated scenario
     */
    public PooledLineSessionScenario withHookTimeout(Duration hookTimeout) {
        return withOptions(poolOptions.withHookTimeout(hookTimeout));
    }

    /**
     * Returns a copy with a different per-worker request limit.
     *
     * @param maxRequestsPerWorker request limit
     * @return updated scenario
     */
    public PooledLineSessionScenario withMaxRequestsPerWorker(int maxRequestsPerWorker) {
        return withOptions(poolOptions.withMaxRequestsPerWorker(maxRequestsPerWorker));
    }

    /**
     * Returns a copy with a different maximum worker age.
     *
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @return updated scenario
     */
    public PooledLineSessionScenario withMaxWorkerAge(Duration maxWorkerAge) {
        return withOptions(poolOptions.withMaxWorkerAge(maxWorkerAge));
    }

    /**
     * Returns a copy with a different background replenishment policy.
     *
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     * @return updated scenario
     */
    public PooledLineSessionScenario withBackgroundReplenishment(boolean backgroundReplenishment) {
        return withOptions(poolOptions.withBackgroundReplenishment(backgroundReplenishment));
    }

    /**
     * Returns a copy with a reset hook run after successful requests.
     *
     * @param resetHook reset hook
     * @return updated scenario
     */
    public PooledLineSessionScenario withReset(Consumer<LineSession> resetHook) {
        return withOptions(poolOptions.withReset(resetHook));
    }

    /**
     * Returns a copy with a health check run before workers are leased.
     *
     * @param healthCheck health check
     * @return updated scenario
     */
    public PooledLineSessionScenario withHealthCheck(Predicate<LineSession> healthCheck) {
        return withOptions(poolOptions.withHealthCheck(healthCheck));
    }

    /**
     * Returns a copy with explicit pooled line-session options.
     *
     * @param poolOptions pooled line-session options
     * @return updated scenario
     */
    public PooledLineSessionScenario withOptions(PooledLineSessionOptions poolOptions) {
        return new PooledLineSessionScenario(service, configureWorker, lineOptions, poolOptions);
    }

    /**
     * Opens the configured worker pool.
     *
     * @return pooled line session
     */
    public PooledLineSession open() {
        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();
        configureWorker.accept(builder);
        return service.openPooledLineSession(builder.build(), lineOptions, poolOptions);
    }
}
