package io.github.ulviar.icli.session;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Completion signal for a streaming session.
 *
 * @param exitCode process exit code when known
 * @param timedOut true when the stream timeout stopped the process
 * @param closed true when caller close stopped or joined the session
 * @param diagnostics bounded diagnostic transcript
 * @param duration elapsed stream duration
 */
public record StreamExit(
        OptionalInt exitCode, boolean timedOut, boolean closed, StreamTranscript diagnostics, Duration duration) {

    /**
     * Validates a stream exit signal.
     *
     * @param exitCode process exit code when known
     * @param timedOut true when the stream timeout stopped the process
     * @param closed true when caller close stopped or joined the session
     * @param diagnostics bounded diagnostic transcript
     * @param duration elapsed stream duration
     */
    public StreamExit {
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }
}
