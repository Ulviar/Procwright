package io.github.ulviar.procwright.diagnostics;

/**
 * Structured diagnostic event kind.
 *
 * <p>Diagnostic attributes are intentionally small and redaction-friendly. They must not contain raw argv values,
 * environment values, stdin, stdout, or stderr. Stable attributes are listed on each event below.
 */
public enum DiagnosticEventType {
    /**
     * A command has been resolved into a launch plan.
     *
     * <p>Stable attributes: none.
     */
    COMMAND_PREPARED,

    /**
     * A process has been started.
     *
     * <p>Stable attributes: {@code pid}.
     */
    PROCESS_STARTED,

    /**
     * Captured output exceeded its configured retention limit.
     *
     * <p>Stable attributes: {@code source}; one of {@code limitBytes} or {@code limitChars}.
     */
    OUTPUT_TRUNCATED,

    /**
     * A scenario timeout has been reached.
     *
     * <p>Stable attributes: none.
     */
    TIMEOUT_REACHED,

    /**
     * Runtime shutdown has been requested.
     *
     * <p>Stable attributes: {@code reason}.
     */
    SHUTDOWN_REQUESTED,

    /**
     * A streaming output listener failed.
     *
     * <p>Stable attributes: none.
     */
    LISTENER_FAILED,

    /**
     * A process has exited.
     *
     * <p>Stable attributes: {@code timedOut}; optional {@code exitCode}.
     */
    PROCESS_EXITED,

    /**
     * A process or runtime path failed before normal completion.
     *
     * <p>Stable attributes: {@code error}.
     */
    PROCESS_FAILED
}
