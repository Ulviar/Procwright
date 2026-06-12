/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Command stdin contents: either in-memory bytes written before stdin is closed, or a file the operating system
 * streams into stdin.
 *
 * <p>In-memory inputs are written by Procwright and stdin is closed afterwards. Path-based inputs created through
 * {@link #fromPath(Path)} are redirected at the operating-system level, so arbitrarily large files stream into the
 * process without being loaded into memory. The file must exist when the command is launched; a missing file fails
 * the run with a typed {@link CommandExecutionException}.
 */
public final class CommandInput {

    private final byte[] bytes;
    private final Path path;

    private CommandInput(byte[] bytes) {
        this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
        this.path = null;
    }

    private CommandInput(Path path) {
        this.bytes = null;
        this.path = Objects.requireNonNull(path, "path");
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

    /**
     * Creates command input streamed from a file.
     *
     * <p>The file is redirected into stdin at the operating-system level, so it is never loaded into Procwright memory.
     * Existence is checked when the command is launched: a missing file fails the run with a typed
     * {@link CommandExecutionException}.
     *
     * @param path stdin source file
     * @return command input
     */
    public static CommandInput fromPath(Path path) {
        return new CommandInput(path);
    }

    /**
     * Returns the stdin source file for path-based input.
     *
     * @return stdin source file, or empty for in-memory input
     */
    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Returns a defensive copy of the input bytes.
     *
     * @return input bytes copy
     * @throws IllegalStateException when this input is path-based and carries no in-memory bytes
     */
    public byte[] copyBytes() {
        if (bytes == null) {
            throw new IllegalStateException("path-based command input has no in-memory bytes");
        }
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
        return Arrays.equals(bytes, that.bytes) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + Objects.hashCode(path);
    }
}
