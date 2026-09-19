/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

/**
 * Structured diagnostic event kind.
 *
 * <p>Diagnostic attributes are intentionally small and redaction-friendly. They must not contain raw argv values,
 * environment values, stdin, stdout, or stderr. Stable attributes are listed on each event below; all values are
 * strings. {@link DiagnosticEvent} rejects missing, additional, or malformed attributes. Event delivery is best effort,
 * so consumers must tolerate gaps in a lifecycle's sequence.
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
     * <p>Stable attributes: {@code pid}, a decimal {@code long} process identifier.
     */
    PROCESS_STARTED,

    /**
     * Captured output exceeded its configured retention limit.
     *
     * <p>Stable attributes: {@code source} ({@code stdout}, {@code stderr}, or {@code diagnostics}) and exactly one
     * positive decimal {@code int} limit: {@code limitBytes} for bytes or {@code limitChars} for UTF-16 code units.
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
     * <p>Stable attributes: {@code reason}, one of {@code timeout}, {@code close}, {@code failure}, {@code idleTimeout},
     * or {@code interrupted}.
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
     * <p>Stable attributes: {@code timedOut} ({@code true} or {@code false}); optional {@code exitCode} as a decimal
     * {@code int}. Absence of {@code exitCode} means no exit status is available.
     */
    PROCESS_EXITED,

    /**
     * A process or runtime path failed before normal completion.
     *
     * <p>Stable attributes: {@code error}, a Java throwable class name. Failure messages, stack traces, and process
     * output are excluded from this field.
     */
    PROCESS_FAILED
}
