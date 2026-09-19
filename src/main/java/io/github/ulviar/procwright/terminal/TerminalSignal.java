/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

/**
 * Terminal-generated control signal that can be sent through a session stdin stream.
 *
 * <p>{@link io.github.ulviar.procwright.session.Session#sendSignal(TerminalSignal)} writes the control byte. A terminal
 * driver may interpret it as a signal according to its settings; with ordinary pipes it is just input data. This API
 * does not directly send an operating-system signal.
 */
public enum TerminalSignal {
    /**
     * The {@code 0x03} control byte, commonly produced by Ctrl+C and interpreted as interrupt by a terminal driver.
     */
    INTERRUPT(0x03);

    private final byte value;

    TerminalSignal(int value) {
        this.value = (byte) value;
    }

    /**
     * Returns bytes written to terminal stdin for this signal.
     *
     * @return a new one-byte array containing the control byte; the caller may modify it
     */
    public byte[] bytes() {
        return new byte[] {value};
    }
}
