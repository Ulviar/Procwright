package io.github.ulviar.procwright.terminal;

/**
 * Requested terminal dimensions for PTY-backed sessions.
 *
 * @param columns terminal columns
 * @param rows terminal rows
 */
public record TerminalSize(int columns, int rows) {

    private static final TerminalSize DEFAULTS = new TerminalSize(80, 24);

    /**
     * Validates terminal dimensions.
     *
     * @param columns terminal columns
     * @param rows terminal rows
     */
    public TerminalSize {
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive");
        }
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be positive");
        }
    }

    /**
     * Returns the default terminal size used by PTY sessions.
     *
     * @return default terminal size
     */
    public static TerminalSize defaults() {
        return DEFAULTS;
    }
}
