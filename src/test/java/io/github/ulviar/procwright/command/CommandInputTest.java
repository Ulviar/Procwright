/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class CommandInputTest {

    @Test
    void bytesAreDefensivelyCopied() {
        byte[] source = {1, 2};
        CommandInput input = CommandInput.bytes(source);

        source[0] = 9;
        byte[] copy = input.copyBytes();
        copy[1] = 9;

        assertArrayEquals(new byte[] {1, 2}, input.copyBytes());
    }

    @Test
    void textUsesRequestedCharset() {
        CommandInput input = CommandInput.text("ok", StandardCharsets.UTF_16LE);

        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_16LE), input.copyBytes());
    }

    @Test
    void comparesInputsByByteContent() {
        CommandInput left = CommandInput.bytes(new byte[] {1, 2});
        CommandInput right = CommandInput.bytes(new byte[] {1, 2});

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> CommandInput.utf8(null));
        assertThrows(NullPointerException.class, () -> CommandInput.text(null, StandardCharsets.UTF_8));
        assertThrows(NullPointerException.class, () -> CommandInput.text("x", null));
        assertThrows(NullPointerException.class, () -> CommandInput.bytes(null));
        assertThrows(NullPointerException.class, () -> CommandInput.fromPath(null));
    }

    @Test
    void pathInputExposesPathAndCarriesNoBytes() {
        CommandInput input = CommandInput.fromPath(java.nio.file.Path.of("stdin.bin"));

        assertEquals(java.nio.file.Path.of("stdin.bin"), input.path().orElseThrow());
        assertThrows(IllegalStateException.class, input::copyBytes);
    }

    @Test
    void inMemoryInputHasNoPath() {
        assertEquals(java.util.Optional.empty(), CommandInput.utf8("x").path());
    }

    @Test
    void comparesPathInputsByPath() {
        assertEquals(
                CommandInput.fromPath(java.nio.file.Path.of("a")), CommandInput.fromPath(java.nio.file.Path.of("a")));
        assertNotEquals(
                CommandInput.fromPath(java.nio.file.Path.of("a")), CommandInput.fromPath(java.nio.file.Path.of("b")));
        assertNotEquals(CommandInput.fromPath(java.nio.file.Path.of("a")), CommandInput.bytes(new byte[0]));
    }
}
