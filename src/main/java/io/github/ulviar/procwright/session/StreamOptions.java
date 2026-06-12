/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default policies for listen-only streaming scenarios.
 */
public final class StreamOptions {

    private static final StreamOptions DEFAULTS = new StreamOptions(
            Duration.ZERO,
            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            StandardCharsets.UTF_8,
            64 * 1024);

    private final Duration timeout;
    private final ShutdownPolicy shutdownPolicy;
    private final Charset charset;
    private final int diagnosticLimit;

    /**
     * Creates stream options from explicit policies.
     *
     * @param timeout absolute stream timeout, or {@link Duration#ZERO} to disable it
     * @param shutdownPolicy shutdown policy used by timeout and close
     * @param charset charset used to decode output chunks
     * @param diagnosticLimit maximum retained diagnostic characters
     */
    public StreamOptions(Duration timeout, ShutdownPolicy shutdownPolicy, Charset charset, int diagnosticLimit) {
        this.timeout = requireNonNegative(timeout, "timeout");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.charset = Objects.requireNonNull(charset, "charset");
        if (diagnosticLimit <= 0) {
            throw new IllegalArgumentException("diagnosticLimit must be positive");
        }
        this.diagnosticLimit = diagnosticLimit;
    }

    /**
     * Returns default stream options: timeout disabled ({@link Duration#ZERO}), shutdown
     * {@code interruptThenKill(2 s, 5 s)}, charset UTF-8, and a 65,536-character diagnostic window.
     *
     * @return default stream options
     */
    public static StreamOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different absolute stream timeout.
     *
     * @param timeout absolute stream timeout, or {@link Duration#ZERO} to disable it
     * @return updated options
     */
    public StreamOptions withTimeout(Duration timeout) {
        return new StreamOptions(timeout, shutdownPolicy, charset, diagnosticLimit);
    }

    /**
     * Returns a copy with a different shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated options
     */
    public StreamOptions withShutdown(ShutdownPolicy shutdownPolicy) {
        return new StreamOptions(timeout, shutdownPolicy, charset, diagnosticLimit);
    }

    /**
     * Returns a copy with a different output charset.
     *
     * @param charset charset
     * @return updated options
     */
    public StreamOptions withCharset(Charset charset) {
        return new StreamOptions(timeout, shutdownPolicy, charset, diagnosticLimit);
    }

    /**
     * Returns a copy with a different diagnostic window limit.
     *
     * @param diagnosticLimit maximum retained diagnostic characters
     * @return updated options
     */
    public StreamOptions withDiagnosticLimit(int diagnosticLimit) {
        return new StreamOptions(timeout, shutdownPolicy, charset, diagnosticLimit);
    }

    /**
     * Returns the absolute stream timeout.
     *
     * @return timeout, or {@link Duration#ZERO} when disabled
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the shutdown policy.
     *
     * @return shutdown policy
     */
    public ShutdownPolicy shutdownPolicy() {
        return shutdownPolicy;
    }

    /**
     * Returns the output charset.
     *
     * @return charset
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Returns the maximum retained diagnostic characters.
     *
     * @return diagnostic limit
     */
    public int diagnosticLimit() {
        return diagnosticLimit;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamOptions that)) {
            return false;
        }
        return diagnosticLimit == that.diagnosticLimit
                && timeout.equals(that.timeout)
                && shutdownPolicy.equals(that.shutdownPolicy)
                && charset.equals(that.charset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeout, shutdownPolicy, charset, diagnosticLimit);
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
