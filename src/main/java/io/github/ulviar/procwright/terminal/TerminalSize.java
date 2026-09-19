/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

/**
 * Requested terminal dimensions for PTY-backed sessions.
 *
 * <p>Dimensions are character-cell counts, not pixels. This immutable value configures initial terminal size; it does
 * not resize an already opened session.
 *
 * @param columns terminal columns, from 1 through 65535
 * @param rows terminal rows, from 1 through 65535
 */
public record TerminalSize(int columns, int rows) {

    private static final TerminalSize DEFAULTS = new TerminalSize(80, 24);

    /**
     * Validates terminal dimensions.
     *
     * @param columns terminal columns, from 1 through 65535
     * @param rows terminal rows, from 1 through 65535
     * @throws IllegalArgumentException if either dimension lies outside the inclusive range 1–65535
     */
    public TerminalSize {
        if (columns <= 0 || columns > 65_535) {
            throw new IllegalArgumentException("columns must be between 1 and 65535");
        }
        if (rows <= 0 || rows > 65_535) {
            throw new IllegalArgumentException("rows must be between 1 and 65535");
        }
    }

    /**
     * Returns the default terminal size of 80 columns and 24 rows.
     *
     * @return default terminal size
     */
    public static TerminalSize defaults() {
        return DEFAULTS;
    }
}
