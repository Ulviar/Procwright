package com.github.ulviar.icli;

import java.util.Objects;

/**
 * Signals a streaming-session failure with bounded diagnostics.
 */
public final class StreamException extends RuntimeException {

    /**
     * Bounded diagnostics captured before the stream failed.
     */
    private final StreamTranscript diagnostics;

    /**
     * Creates a stream exception.
     *
     * @param message failure message
     * @param diagnostics bounded diagnostic transcript
     * @param cause failure cause
     */
    public StreamException(String message, StreamTranscript diagnostics, Throwable cause) {
        super(message, cause);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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
