package io.github.ulviar.procwright;

import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionInvocation;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionOptions;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Configures a pool of typed protocol-session workers.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class PooledProtocolSessionScenario<I, O> {

    private final CommandService service;
    private final Supplier<? extends ProtocolAdapter<I, O>> adapterFactory;
    private final Consumer<PooledProtocolSessionInvocation.Builder<I, O>> configureWorker;
    private final ProtocolSessionOptions protocolOptions;
    private final PooledProtocolSessionOptions poolOptions;

    PooledProtocolSessionScenario(
            CommandService service,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            Consumer<PooledProtocolSessionInvocation.Builder<I, O>> configureWorker,
            ProtocolSessionOptions protocolOptions,
            PooledProtocolSessionOptions poolOptions) {
        this.service = Objects.requireNonNull(service, "service");
        this.adapterFactory = Objects.requireNonNull(adapterFactory, "adapterFactory");
        this.configureWorker = Objects.requireNonNull(configureWorker, "configureWorker");
        this.protocolOptions = Objects.requireNonNull(protocolOptions, "protocolOptions");
        this.poolOptions = Objects.requireNonNull(poolOptions, "poolOptions");
    }

    /**
     * Returns a copy with a different maximum live worker count.
     *
     * @param maxSize maximum live workers
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withMaxSize(int maxSize) {
        return withOptions(poolOptions.withMaxSize(maxSize));
    }

    /**
     * Returns a copy with a different eager warmup size.
     *
     * @param warmupSize workers opened when the pool is created
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withWarmupSize(int warmupSize) {
        return withOptions(poolOptions.withWarmupSize(warmupSize));
    }

    /**
     * Returns a copy with a different minimum idle target.
     *
     * @param minIdle minimum idle workers
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withMinIdle(int minIdle) {
        return withOptions(poolOptions.withMinIdle(minIdle));
    }

    /**
     * Returns a copy with a different worker acquire timeout.
     *
     * @param acquireTimeout acquire timeout
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withAcquireTimeout(Duration acquireTimeout) {
        return withOptions(poolOptions.withAcquireTimeout(acquireTimeout));
    }

    /**
     * Returns a copy with a different health/reset hook timeout.
     *
     * @param hookTimeout hook timeout
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withHookTimeout(Duration hookTimeout) {
        return withOptions(poolOptions.withHookTimeout(hookTimeout));
    }

    /**
     * Returns a copy with a different per-worker request limit.
     *
     * @param maxRequestsPerWorker request limit
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withMaxRequestsPerWorker(int maxRequestsPerWorker) {
        return withOptions(poolOptions.withMaxRequestsPerWorker(maxRequestsPerWorker));
    }

    /**
     * Returns a copy with a different maximum worker age.
     *
     * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withMaxWorkerAge(Duration maxWorkerAge) {
        return withOptions(poolOptions.withMaxWorkerAge(maxWorkerAge));
    }

    /**
     * Returns a copy with a different background replenishment policy.
     *
     * @param backgroundReplenishment whether retired workers may be replenished in the background
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withBackgroundReplenishment(boolean backgroundReplenishment) {
        return withOptions(poolOptions.withBackgroundReplenishment(backgroundReplenishment));
    }

    /**
     * Returns a copy with a reset hook run after successful requests.
     *
     * @param resetHook reset hook
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withReset(Consumer<ProtocolSession<I, O>> resetHook) {
        return withWorker(builder -> builder.reset(resetHook));
    }

    /**
     * Returns a copy with a health check run before workers are leased.
     *
     * @param healthCheck health check
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withHealthCheck(Predicate<ProtocolSession<I, O>> healthCheck) {
        return withWorker(builder -> builder.healthCheck(healthCheck));
    }

    /**
     * Returns a copy with explicit pooled protocol-session options.
     *
     * @param poolOptions pooled protocol-session options
     * @return updated scenario
     */
    public PooledProtocolSessionScenario<I, O> withOptions(PooledProtocolSessionOptions poolOptions) {
        return new PooledProtocolSessionScenario<>(
                service, adapterFactory, configureWorker, protocolOptions, poolOptions);
    }

    /**
     * Opens the configured protocol worker pool.
     *
     * @return pooled protocol session
     */
    public PooledProtocolSession<I, O> open() {
        PooledProtocolSessionInvocation.Builder<I, O> builder =
                PooledProtocolSessionInvocation.builder(protocolOptions, poolOptions);
        configureWorker.accept(builder);
        return service.openPooledProtocolSession(adapterFactory, builder.build());
    }

    private PooledProtocolSessionScenario<I, O> withWorker(
            Consumer<PooledProtocolSessionInvocation.Builder<I, O>> step) {
        Objects.requireNonNull(step, "step");
        return new PooledProtocolSessionScenario<>(
                service, adapterFactory, configureWorker.andThen(step), protocolOptions, poolOptions);
    }
}
