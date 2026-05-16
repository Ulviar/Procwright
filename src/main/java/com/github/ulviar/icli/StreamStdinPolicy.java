package com.github.ulviar.icli;

/**
 * Describes stdin handling for listen-only streaming scenarios.
 */
public enum StreamStdinPolicy {
    /**
     * Closes process stdin immediately after the stream session starts.
     */
    CLOSE_ON_START,

    /**
     * Keeps process stdin open until the caller explicitly closes it or closes the stream session.
     */
    KEEP_OPEN
}
