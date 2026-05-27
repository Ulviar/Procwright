package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-pool launch and pooling overrides for typed protocol-session workers.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class PooledProtocolSessionInvocation<I, O> {

    private final ProtocolSessionInvocation<I, O> protocolSessionInvocation;
    private final PooledProtocolSessionOptions options;
    private final Consumer<ProtocolSession<I, O>> resetHook;
    private final Predicate<ProtocolSession<I, O>> healthCheck;

    private PooledProtocolSessionInvocation(Builder<I, O> builder) {
        protocolSessionInvocation = builder.protocolBuilder.build();
        options = builder.options();
        resetHook = builder.resetHook;
        healthCheck = builder.healthCheck;
    }

    /**
     * Creates a pooled protocol-session invocation builder with default pool options.
     *
     * @param protocolOptions protocol-session defaults
     * @param <I> request type
     * @param <O> response type
     * @return pooled protocol-session invocation builder
     */
    public static <I, O> Builder<I, O> builder(ProtocolSessionOptions protocolOptions) {
        return new Builder<>(protocolOptions, PooledProtocolSessionOptions.defaults());
    }

    /**
     * Creates a pooled protocol-session invocation builder.
     *
     * @param protocolOptions protocol-session defaults
     * @param poolOptions pooled protocol-session defaults
     * @param <I> request type
     * @param <O> response type
     * @return pooled protocol-session invocation builder
     */
    public static <I, O> Builder<I, O> builder(
            ProtocolSessionOptions protocolOptions, PooledProtocolSessionOptions poolOptions) {
        return new Builder<>(protocolOptions, poolOptions);
    }

    /**
     * Returns immutable per-worker protocol-session launch overrides.
     *
     * @return protocol-session invocation
     */
    public ProtocolSessionInvocation<I, O> protocolSessionInvocation() {
        return protocolSessionInvocation;
    }

    /**
     * Returns immutable pooling options.
     *
     * @return pooling options
     */
    public PooledProtocolSessionOptions options() {
        return options;
    }

    /**
     * Returns the reset hook run after successful user requests.
     *
     * @return reset hook
     */
    public Consumer<ProtocolSession<I, O>> resetHook() {
        return resetHook;
    }

    /**
     * Returns the health check run before workers are leased.
     *
     * @return health check
     */
    public Predicate<ProtocolSession<I, O>> healthCheck() {
        return healthCheck;
    }

    /**
     * Mutable builder used by pooled protocol callbacks.
     *
     * @param <I> request type
     * @param <O> response type
     */
    public static final class Builder<I, O> {

        private final ProtocolSessionInvocation.Builder<I, O> protocolBuilder;
        private int maxSize;
        private int warmupSize;
        private int minIdle;
        private Duration acquireTimeout;
        private Duration hookTimeout;
        private int maxRequestsPerWorker;
        private Duration maxWorkerAge;
        private boolean backgroundReplenishment;
        private Consumer<ProtocolSession<I, O>> resetHook = session -> {};
        private Predicate<ProtocolSession<I, O>> healthCheck =
                session -> !session.onExit().isDone();

        private Builder(ProtocolSessionOptions protocolOptions, PooledProtocolSessionOptions poolOptions) {
            protocolBuilder = ProtocolSessionInvocation.builder(protocolOptions);
            maxSize = poolOptions.maxSize();
            warmupSize = poolOptions.warmupSize();
            minIdle = poolOptions.minIdle();
            acquireTimeout = poolOptions.acquireTimeout();
            hookTimeout = poolOptions.hookTimeout();
            maxRequestsPerWorker = poolOptions.maxRequestsPerWorker();
            maxWorkerAge = poolOptions.maxWorkerAge();
            backgroundReplenishment = poolOptions.backgroundReplenishment();
        }

        /**
         * Adds one per-worker argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder<I, O> arg(String argument) {
            protocolBuilder.arg(argument);
            return this;
        }

        /**
         * Adds per-worker arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder<I, O> args(String... arguments) {
            protocolBuilder.args(arguments);
            return this;
        }

        /**
         * Adds per-worker arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder<I, O> args(Collection<String> arguments) {
            protocolBuilder.args(arguments);
            return this;
        }

        /**
         * Sets the per-worker working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder<I, O> workingDirectory(Path workingDirectory) {
            protocolBuilder.workingDirectory(workingDirectory);
            return this;
        }

        /**
         * Adds or replaces one per-worker environment override.
         *
         * @param name environment variable name
         * @param value environment variable value
         * @return this builder
         */
        public Builder<I, O> putEnvironment(String name, String value) {
            protocolBuilder.putEnvironment(name, value);
            return this;
        }

        /**
         * Inherits the current process environment before applying configured overrides for each worker.
         *
         * @return this builder
         */
        public Builder<I, O> inheritEnvironment() {
            protocolBuilder.inheritEnvironment();
            return this;
        }

        /**
         * Starts each worker with only configured environment overrides.
         *
         * @return this builder
         */
        public Builder<I, O> cleanEnvironment() {
            protocolBuilder.cleanEnvironment();
            return this;
        }

        /**
         * Sets the per-worker shutdown policy override.
         *
         * @param shutdownPolicy shutdown policy
         * @return this builder
         */
        public Builder<I, O> shutdown(ShutdownPolicy shutdownPolicy) {
            protocolBuilder.shutdown(shutdownPolicy);
            return this;
        }

        /**
         * Sets the per-worker caller-visible idle timeout override.
         *
         * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
         * @return this builder
         */
        public Builder<I, O> idleTimeout(Duration idleTimeout) {
            protocolBuilder.idleTimeout(idleTimeout);
            return this;
        }

        /**
         * Sets the per-worker terminal policy override.
         *
         * @param terminalPolicy terminal policy
         * @return this builder
         */
        public Builder<I, O> terminal(TerminalPolicy terminalPolicy) {
            protocolBuilder.terminal(terminalPolicy);
            return this;
        }

        /**
         * Sets the per-worker default request timeout.
         *
         * @param requestTimeout request timeout
         * @return this builder
         */
        public Builder<I, O> requestTimeout(Duration requestTimeout) {
            protocolBuilder.requestTimeout(requestTimeout);
            return this;
        }

        /**
         * Sets the per-worker retained transcript character limit.
         *
         * @param transcriptLimit transcript limit
         * @return this builder
         */
        public Builder<I, O> transcriptLimit(int transcriptLimit) {
            protocolBuilder.transcriptLimit(transcriptLimit);
            return this;
        }

        /**
         * Sets the per-worker maximum pending output bytes per stream.
         *
         * @param stdoutBacklogLimit output backlog limit
         * @return this builder
         */
        public Builder<I, O> stdoutBacklogLimit(int stdoutBacklogLimit) {
            protocolBuilder.stdoutBacklogLimit(stdoutBacklogLimit);
            return this;
        }

        /**
         * Sets the maximum pending output bytes per stream.
         *
         * @param outputBacklogLimit output backlog limit
         * @return this builder
         */
        public Builder<I, O> outputBacklogLimit(int outputBacklogLimit) {
            protocolBuilder.outputBacklogLimit(outputBacklogLimit);
            return this;
        }

        /**
         * Sets the per-worker request byte limit.
         *
         * @param maxRequestBytes request byte limit
         * @return this builder
         */
        public Builder<I, O> maxRequestBytes(int maxRequestBytes) {
            protocolBuilder.maxRequestBytes(maxRequestBytes);
            return this;
        }

        /**
         * Sets the per-worker request character limit for text writes.
         *
         * @param maxRequestChars request character limit
         * @return this builder
         */
        public Builder<I, O> maxRequestChars(int maxRequestChars) {
            protocolBuilder.maxRequestChars(maxRequestChars);
            return this;
        }

        /**
         * Sets the per-worker response byte limit.
         *
         * @param maxResponseBytes response byte limit
         * @return this builder
         */
        public Builder<I, O> maxResponseBytes(int maxResponseBytes) {
            protocolBuilder.maxResponseBytes(maxResponseBytes);
            return this;
        }

        /**
         * Sets the per-worker response character limit for text reads.
         *
         * @param maxResponseChars response character limit
         * @return this builder
         */
        public Builder<I, O> maxResponseChars(int maxResponseChars) {
            protocolBuilder.maxResponseChars(maxResponseChars);
            return this;
        }

        /**
         * Sets the per-worker protocol text charset policy.
         *
         * @param charsetPolicy charset policy
         * @return this builder
         */
        public Builder<I, O> charsetPolicy(CharsetPolicy charsetPolicy) {
            protocolBuilder.charsetPolicy(charsetPolicy);
            return this;
        }

        /**
         * Sets the readiness probe run before a worker becomes available to the pool.
         *
         * @param readinessProbe readiness probe
         * @return this builder
         */
        public Builder<I, O> readiness(Consumer<ProtocolSession<I, O>> readinessProbe) {
            protocolBuilder.readiness(readinessProbe);
            return this;
        }

        /**
         * Sets the readiness probe timeout.
         *
         * @param readinessTimeout readiness timeout
         * @return this builder
         */
        public Builder<I, O> readinessTimeout(Duration readinessTimeout) {
            protocolBuilder.readinessTimeout(readinessTimeout);
            return this;
        }

        /**
         * Sets the maximum live workers.
         *
         * @param maxSize maximum live workers
         * @return this builder
         */
        public Builder<I, O> maxSize(int maxSize) {
            this.maxSize = requirePositive(maxSize, "maxSize");
            return this;
        }

        /**
         * Sets the number of workers opened when the pool is created.
         *
         * @param warmupSize warmup size
         * @return this builder
         */
        public Builder<I, O> warmupSize(int warmupSize) {
            if (warmupSize < 0) {
                throw new IllegalArgumentException("warmupSize must not be negative");
            }
            this.warmupSize = warmupSize;
            return this;
        }

        /**
         * Sets the minimum idle worker target.
         *
         * @param minIdle minimum idle workers to keep ready
         * @return this builder
         */
        public Builder<I, O> minIdle(int minIdle) {
            if (minIdle < 0) {
                throw new IllegalArgumentException("minIdle must not be negative");
            }
            this.minIdle = minIdle;
            return this;
        }

        /**
         * Sets the maximum time to wait for an available worker.
         *
         * @param acquireTimeout acquire timeout
         * @return this builder
         */
        public Builder<I, O> acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
            return this;
        }

        /**
         * Sets the maximum time to wait for one health or reset hook.
         *
         * @param hookTimeout hook timeout
         * @return this builder
         */
        public Builder<I, O> hookTimeout(Duration hookTimeout) {
            this.hookTimeout = requirePositive(hookTimeout, "hookTimeout");
            return this;
        }

        /**
         * Sets the maximum user requests served by one worker.
         *
         * @param maxRequestsPerWorker request limit
         * @return this builder
         */
        public Builder<I, O> maxRequestsPerWorker(int maxRequestsPerWorker) {
            this.maxRequestsPerWorker = requirePositive(maxRequestsPerWorker, "maxRequestsPerWorker");
            return this;
        }

        /**
         * Sets the maximum worker age.
         *
         * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
         * @return this builder
         */
        public Builder<I, O> maxWorkerAge(Duration maxWorkerAge) {
            this.maxWorkerAge = requireNonNegative(maxWorkerAge, "maxWorkerAge");
            return this;
        }

        /**
         * Sets whether retired workers may be replenished in the background.
         *
         * @param backgroundReplenishment background replenishment flag
         * @return this builder
         */
        public Builder<I, O> backgroundReplenishment(boolean backgroundReplenishment) {
            this.backgroundReplenishment = backgroundReplenishment;
            return this;
        }

        /**
         * Sets the reset hook run after successful user requests.
         *
         * @param resetHook reset hook
         * @return this builder
         */
        public Builder<I, O> reset(Consumer<ProtocolSession<I, O>> resetHook) {
            this.resetHook = Objects.requireNonNull(resetHook, "resetHook");
            return this;
        }

        /**
         * Sets the health check run before workers are leased.
         *
         * @param healthCheck health check
         * @return this builder
         */
        public Builder<I, O> healthCheck(Predicate<ProtocolSession<I, O>> healthCheck) {
            this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
            return this;
        }

        /**
         * Builds an immutable pooled protocol-session invocation draft.
         *
         * @return immutable invocation draft
         */
        public PooledProtocolSessionInvocation<I, O> build() {
            return new PooledProtocolSessionInvocation<>(this);
        }

        private PooledProtocolSessionOptions options() {
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

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
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
}
