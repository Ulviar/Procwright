package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Per-pool launch and pooling overrides for a pooled line-oriented scenario.
 */
public final class PooledLineSessionInvocation {

    private final LineSessionInvocation lineSessionInvocation;
    private final PooledLineSessionOptions options;

    private PooledLineSessionInvocation(Builder builder) {
        lineSessionInvocation = builder.lineSessionBuilder.build();
        options = builder.options();
    }

    /**
     * Creates a pooled line-session invocation builder.
     *
     * @return pooled line-session invocation builder
     */
    public static Builder builder() {
        return new Builder(PooledLineSessionOptions.defaults());
    }

    /**
     * Returns immutable line-session launch overrides.
     *
     * @return line-session invocation
     */
    public LineSessionInvocation lineSessionInvocation() {
        return lineSessionInvocation;
    }

    /**
     * Returns immutable pooling options.
     *
     * @return pooling options
     */
    public PooledLineSessionOptions options() {
        return options;
    }

    /**
     * Mutable builder used by pooled scenario callbacks.
     */
    public static final class Builder {

        private final LineSessionInvocation.Builder lineSessionBuilder = LineSessionInvocation.builder();
        private int maxSize;
        private int warmupSize;
        private int minIdle;
        private Duration acquireTimeout;
        private Duration hookTimeout;
        private int maxRequestsPerWorker;
        private Duration maxWorkerAge;
        private boolean backgroundReplenishment;
        private Consumer<LineSession> resetHook;
        private Predicate<LineSession> healthCheck;

        private Builder(PooledLineSessionOptions options) {
            PooledLineSessionOptions defaults = Objects.requireNonNull(options, "options");
            maxSize = defaults.maxSize();
            warmupSize = defaults.warmupSize();
            minIdle = defaults.minIdle();
            acquireTimeout = defaults.acquireTimeout();
            hookTimeout = defaults.hookTimeout();
            maxRequestsPerWorker = defaults.maxRequestsPerWorker();
            maxWorkerAge = defaults.maxWorkerAge();
            backgroundReplenishment = defaults.backgroundReplenishment();
            resetHook = defaults.resetHook();
            healthCheck = defaults.healthCheck();
        }

        /**
         * Adds one per-worker argument.
         *
         * @param argument argument value
         * @return this builder
         */
        public Builder arg(String argument) {
            lineSessionBuilder.arg(argument);
            return this;
        }

        /**
         * Adds per-worker arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder args(String... arguments) {
            lineSessionBuilder.args(arguments);
            return this;
        }

        /**
         * Adds per-worker arguments.
         *
         * @param arguments argument values
         * @return this builder
         */
        public Builder args(Collection<String> arguments) {
            lineSessionBuilder.args(arguments);
            return this;
        }

        /**
         * Sets the per-worker working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(Path workingDirectory) {
            lineSessionBuilder.workingDirectory(workingDirectory);
            return this;
        }

        /**
         * Adds or replaces one per-worker environment override.
         *
         * @param name environment variable name
         * @param value environment variable value
         * @return this builder
         */
        public Builder putEnvironment(String name, String value) {
            lineSessionBuilder.putEnvironment(name, value);
            return this;
        }

        /**
         * Inherits the current process environment before applying configured overrides for each worker.
         *
         * @return this builder
         */
        public Builder inheritEnvironment() {
            lineSessionBuilder.inheritEnvironment();
            return this;
        }

        /**
         * Starts each worker with only configured environment overrides.
         *
         * @return this builder
         */
        public Builder cleanEnvironment() {
            lineSessionBuilder.cleanEnvironment();
            return this;
        }

        /**
         * Sets the per-worker shutdown policy override.
         *
         * @param shutdownPolicy shutdown policy
         * @return this builder
         */
        public Builder shutdown(ShutdownPolicy shutdownPolicy) {
            lineSessionBuilder.shutdown(shutdownPolicy);
            return this;
        }

        /**
         * Sets the per-worker caller-visible idle timeout override.
         *
         * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
         * @return this builder
         */
        public Builder idleTimeout(Duration idleTimeout) {
            lineSessionBuilder.idleTimeout(idleTimeout);
            return this;
        }

        /**
         * Sets the per-worker terminal policy override.
         *
         * @param terminalPolicy terminal policy
         * @return this builder
         */
        public Builder terminal(TerminalPolicy terminalPolicy) {
            lineSessionBuilder.terminal(terminalPolicy);
            return this;
        }

        /**
         * Sets the maximum live workers.
         *
         * @param maxSize maximum live workers
         * @return this builder
         */
        public Builder maxSize(int maxSize) {
            this.maxSize = requirePositive(maxSize, "maxSize");
            return this;
        }

        /**
         * Sets the number of workers opened when the pool is created.
         *
         * @param warmupSize warmup size
         * @return this builder
         */
        public Builder warmupSize(int warmupSize) {
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
        public Builder minIdle(int minIdle) {
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
        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = requirePositive(acquireTimeout, "acquireTimeout");
            return this;
        }

        /**
         * Sets the maximum time to wait for one health or reset hook.
         *
         * @param hookTimeout hook timeout
         * @return this builder
         */
        public Builder hookTimeout(Duration hookTimeout) {
            this.hookTimeout = requirePositive(hookTimeout, "hookTimeout");
            return this;
        }

        /**
         * Sets the maximum user requests served by one worker.
         *
         * @param maxRequestsPerWorker request limit
         * @return this builder
         */
        public Builder maxRequestsPerWorker(int maxRequestsPerWorker) {
            this.maxRequestsPerWorker = requirePositive(maxRequestsPerWorker, "maxRequestsPerWorker");
            return this;
        }

        /**
         * Sets the maximum worker age.
         *
         * @param maxWorkerAge maximum worker age, or {@link Duration#ZERO} to disable age retirement
         * @return this builder
         */
        public Builder maxWorkerAge(Duration maxWorkerAge) {
            this.maxWorkerAge = requireNonNegative(maxWorkerAge, "maxWorkerAge");
            return this;
        }

        /**
         * Sets whether retired workers may be replenished in the background.
         *
         * @param backgroundReplenishment background replenishment flag
         * @return this builder
         */
        public Builder backgroundReplenishment(boolean backgroundReplenishment) {
            this.backgroundReplenishment = backgroundReplenishment;
            return this;
        }

        /**
         * Sets the reset hook run after successful user requests.
         *
         * @param resetHook reset hook
         * @return this builder
         */
        public Builder reset(Consumer<LineSession> resetHook) {
            this.resetHook = Objects.requireNonNull(resetHook, "resetHook");
            return this;
        }

        /**
         * Sets the health check run before workers are leased.
         *
         * @param healthCheck health check
         * @return this builder
         */
        public Builder healthCheck(Predicate<LineSession> healthCheck) {
            this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
            return this;
        }

        /**
         * Builds an immutable pooled line-session invocation draft.
         *
         * @return immutable invocation draft
         */
        public PooledLineSessionInvocation build() {
            return new PooledLineSessionInvocation(this);
        }

        private PooledLineSessionOptions options() {
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
