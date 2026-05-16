package com.github.ulviar.icli;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Default lifecycle policies for interactive sessions.
 *
 * <p>Idle timeout is measured from caller-visible session activity: successful writes to stdin, closing stdin, and
 * successful reads from stdout or stderr. Output that the caller does not read does not reset the idle timer.
 */
public final class SessionOptions {

    private static final SessionOptions DEFAULTS = new SessionOptions(
            Duration.ZERO,
            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            StandardCharsets.UTF_8);

    private final Duration idleTimeout;
    private final ShutdownPolicy shutdownPolicy;
    private final Charset charset;

    /**
     * Creates session options from explicit lifecycle policies.
     *
     * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
     * @param shutdownPolicy shutdown policy used by close and idle timeout
     * @param charset charset used by text send helpers
     */
    public SessionOptions(Duration idleTimeout, ShutdownPolicy shutdownPolicy, Charset charset) {
        this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    /**
     * Returns default session options.
     *
     * @return default session options
     */
    public static SessionOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different caller-visible idle timeout.
     *
     * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
     * @return updated options
     */
    public SessionOptions withIdleTimeout(Duration idleTimeout) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset);
    }

    /**
     * Returns a copy with a different shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated options
     */
    public SessionOptions withShutdown(ShutdownPolicy shutdownPolicy) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset);
    }

    /**
     * Returns a copy with a different text-send charset.
     *
     * @param charset charset
     * @return updated options
     */
    public SessionOptions withCharset(Charset charset) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset);
    }

    /**
     * Returns the caller-visible idle timeout.
     *
     * @return idle timeout
     */
    public Duration idleTimeout() {
        return idleTimeout;
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
     * Returns the text-send charset.
     *
     * @return charset
     */
    public Charset charset() {
        return charset;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionOptions that)) {
            return false;
        }
        return idleTimeout.equals(that.idleTimeout)
                && shutdownPolicy.equals(that.shutdownPolicy)
                && charset.equals(that.charset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idleTimeout, shutdownPolicy, charset);
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
