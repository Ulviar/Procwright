package com.github.ulviar.icli;

/**
 * Structured diagnostic event kind.
 */
public enum DiagnosticEventType {
    /**
     * A command has been resolved into a launch plan.
     */
    COMMAND_PREPARED,

    /**
     * A process has been started.
     */
    PROCESS_STARTED,

    /**
     * Captured output exceeded its configured retention limit.
     */
    OUTPUT_TRUNCATED,

    /**
     * A scenario timeout has been reached.
     */
    TIMEOUT_REACHED,

    /**
     * Runtime shutdown has been requested.
     */
    SHUTDOWN_REQUESTED,

    /**
     * A streaming output listener failed.
     */
    LISTENER_FAILED,

    /**
     * A process has exited.
     */
    PROCESS_EXITED,

    /**
     * A process or runtime path failed before normal completion.
     */
    PROCESS_FAILED
}
