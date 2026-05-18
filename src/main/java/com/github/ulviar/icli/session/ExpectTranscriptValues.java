package com.github.ulviar.icli.session;

/**
 * Controls whether expect action transcript entries may include caller-provided values.
 */
public enum ExpectTranscriptValues {
    /**
     * Redacts send and expect values in transcript action entries.
     */
    REDACTED,

    /**
     * Records send and expect values verbatim in transcript action entries.
     */
    VERBATIM
}
