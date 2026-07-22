/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Signals a streaming-session failure with bounded diagnostics.
 */
@SuppressWarnings("serial")
public final class StreamException extends ProcwrightException {

    /**
     * Stable streaming failure categories.
     */
    public enum Reason {
        /** The consumer's output listener failed. */
        LISTENER_FAILED,
        /** Procwright could not read process stdout or stderr. */
        OUTPUT_READ_FAILED,
        /** The underlying process session failed. */
        PROCESS_FAILED
    }

    /** Stable category used for programmatic failure handling. */
    private final Reason reason;

    /**
     * Bounded diagnostics captured before the stream failed.
     */
    private final StreamTranscript diagnostics;

    /**
     * Creates a stream exception.
     *
     * @param reason stable failure category
     * @param message failure message
     * @param diagnostics bounded diagnostic transcript
     * @param cause failure cause, or {@code null} when unavailable
     */
    public StreamException(Reason reason, String message, StreamTranscript diagnostics, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Returns the stable failure category.
     *
     * @return failure category
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns bounded diagnostics captured before the failure.
     *
     * @return diagnostic transcript
     */
    public StreamTranscript diagnostics() {
        return diagnostics;
    }
}
