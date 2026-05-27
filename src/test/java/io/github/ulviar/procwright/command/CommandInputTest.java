package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
