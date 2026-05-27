package io.github.ulviar.procwright.session;

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

    /**
     * Returns a stable lowercase diagnostic label.
     *
     * @return stream label
     */
    public String label() {
        return label;
    }
}
