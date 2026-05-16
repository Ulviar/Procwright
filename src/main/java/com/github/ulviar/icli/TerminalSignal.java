package com.github.ulviar.icli;

/**
 * Terminal-generated control signal that can be sent through a session stdin stream.
 */
public enum TerminalSignal {
    /**
     * Interrupt signal, commonly produced by Ctrl+C.
     */
    INTERRUPT(0x03);

    private final byte value;

    TerminalSignal(int value) {
        this.value = (byte) value;
    }

    byte[] bytes() {
        return new byte[] {value};
    }
}
