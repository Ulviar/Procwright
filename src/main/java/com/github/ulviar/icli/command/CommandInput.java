package com.github.ulviar.icli.command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bytes written to command stdin before stdin is closed.
 */
public final class CommandInput {

    private static final CommandInput CLOSED = new CommandInput(new byte[0]);

    private final byte[] bytes;

    private CommandInput(byte[] bytes) {
        this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    /**
     * Encodes text as UTF-8 command input.
     *
     * @param text input text
     * @return command input
     */
    public static CommandInput utf8(String text) {
        return text(text, StandardCharsets.UTF_8);
    }

    /**
     * Encodes text with the provided charset.
     *
     * @param text input text
     * @param charset input charset
     * @return command input
     */
    public static CommandInput text(String text, Charset charset) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        return bytes(text.getBytes(charset));
    }

    /**
     * Creates command input from bytes.
     *
     * @param bytes input bytes
     * @return command input
     */
    public static CommandInput bytes(byte[] bytes) {
        return new CommandInput(bytes);
    }

    static CommandInput closed() {
        return CLOSED;
    }

    /**
     * Returns a defensive copy of the input bytes.
     *
     * @return input bytes copy
     */
    public byte[] copyBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandInput that)) {
            return false;
        }
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
