package io.github.ulviar.icli.session;

import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.terminal.PtyProvider;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import io.github.ulviar.icli.terminal.TerminalSize;
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
            StandardCharsets.UTF_8,
            TerminalPolicy.DISABLED,
            PtyProvider.system(),
            TerminalSize.defaults());

    private final Duration idleTimeout;
    private final ShutdownPolicy shutdownPolicy;
    private final Charset charset;
    private final TerminalPolicy terminalPolicy;
    private final PtyProvider ptyProvider;
    private final TerminalSize terminalSize;

    /**
     * Creates session options from explicit lifecycle policies.
     *
     * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
     * @param shutdownPolicy shutdown policy used by close and idle timeout
     * @param charset charset used by text send helpers
     */
    public SessionOptions(Duration idleTimeout, ShutdownPolicy shutdownPolicy, Charset charset) {
        this(
                idleTimeout,
                shutdownPolicy,
                charset,
                TerminalPolicy.DISABLED,
                PtyProvider.system(),
                TerminalSize.defaults());
    }

    /**
     * Creates session options from explicit lifecycle and terminal policies.
     *
     * @param idleTimeout caller-visible idle timeout, or {@link Duration#ZERO} to disable it
     * @param shutdownPolicy shutdown policy used by close and idle timeout
     * @param charset charset used by text send helpers
     * @param terminalPolicy default terminal policy for session scenarios
     * @param ptyProvider PTY provider used when a session requests a terminal
     * @param terminalSize requested terminal dimensions for PTY-backed sessions
     */
    public SessionOptions(
            Duration idleTimeout,
            ShutdownPolicy shutdownPolicy,
            Charset charset,
            TerminalPolicy terminalPolicy,
            PtyProvider ptyProvider,
            TerminalSize terminalSize) {
        this.idleTimeout = requireNonNegative(idleTimeout, "idleTimeout");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.terminalPolicy = Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        this.ptyProvider = Objects.requireNonNull(ptyProvider, "ptyProvider");
        this.terminalSize = Objects.requireNonNull(terminalSize, "terminalSize");
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
        return new SessionOptions(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
    }

    /**
     * Returns a copy with a different shutdown policy.
     *
     * @param shutdownPolicy shutdown policy
     * @return updated options
     */
    public SessionOptions withShutdown(ShutdownPolicy shutdownPolicy) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
    }

    /**
     * Returns a copy with a different text-send charset.
     *
     * @param charset charset
     * @return updated options
     */
    public SessionOptions withCharset(Charset charset) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
    }

    /**
     * Returns a copy with a different default terminal policy.
     *
     * @param terminalPolicy terminal policy
     * @return updated options
     */
    public SessionOptions withTerminalPolicy(TerminalPolicy terminalPolicy) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
    }

    /**
     * Returns a copy with a different PTY provider.
     *
     * @param ptyProvider PTY provider
     * @return updated options
     */
    public SessionOptions withPtyProvider(PtyProvider ptyProvider) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
    }

    /**
     * Returns a copy with different terminal dimensions.
     *
     * @param terminalSize terminal dimensions
     * @return updated options
     */
    public SessionOptions withTerminalSize(TerminalSize terminalSize) {
        return new SessionOptions(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
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

    /**
     * Returns the default terminal policy.
     *
     * @return terminal policy
     */
    public TerminalPolicy terminalPolicy() {
        return terminalPolicy;
    }

    /**
     * Returns the PTY provider used when a session requests a terminal.
     *
     * @return PTY provider
     */
    public PtyProvider ptyProvider() {
        return ptyProvider;
    }

    /**
     * Returns the requested terminal dimensions.
     *
     * @return terminal dimensions
     */
    public TerminalSize terminalSize() {
        return terminalSize;
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
                && charset.equals(that.charset)
                && terminalPolicy == that.terminalPolicy
                && ptyProvider.equals(that.ptyProvider)
                && terminalSize.equals(that.terminalSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idleTimeout, shutdownPolicy, charset, terminalPolicy, ptyProvider, terminalSize);
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
