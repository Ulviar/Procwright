/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable terminal outcome of a {@link StreamSession}.
 *
 * <p>A nonzero exit code is a normal result, not an exception by itself. An empty code means it was unavailable at
 * outcome selection. Timeout and caller close are distinct outcomes and cannot both be true. Natural completion has
 * both flags false. See {@link StreamSession#onExit()} for when callback delivery has finished.
 *
 * @param exitCode process exit code when known
 * @param timedOut true when the stream timeout stopped the process
 * @param closed true when caller close selected the terminal outcome
 * @param diagnostics bounded diagnostic transcript
 * @param duration non-negative elapsed time from stream-handle initialization to logical completion
 */
public record StreamExit(
        OptionalInt exitCode, boolean timedOut, boolean closed, StreamTranscript diagnostics, Duration duration) {

    /**
     * Validates a stream exit signal.
     *
     * @param exitCode process exit code when known
     * @param timedOut true when the stream timeout stopped the process
     * @param closed true when caller close selected the terminal outcome
     * @param diagnostics bounded diagnostic transcript
     * @param duration non-negative elapsed time from stream-handle initialization to logical completion
     * @throws IllegalArgumentException if duration is negative or both timedOut and closed are true
     */
    public StreamExit {
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (timedOut && closed) {
            throw new IllegalArgumentException("a stream exit cannot be both timed out and caller-closed");
        }
    }
}
