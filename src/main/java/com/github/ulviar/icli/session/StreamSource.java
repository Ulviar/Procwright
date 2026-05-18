package com.github.ulviar.icli.session;

/**
 * Output stream that produced a streaming chunk.
 */
public enum StreamSource {
    /**
     * Process stdout.
     */
    STDOUT("stdout"),

    /**
     * Process stderr.
     */
    STDERR("stderr");

    private final String label;

    StreamSource(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
