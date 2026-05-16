package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class CommandResultTest {

    @Test
    void zeroExitCodeSucceeds() {
        CommandResult result = new CommandResult(0, "out", "");

        assertTrue(result.succeeded());
        assertEquals(0, result.exitCode().orElseThrow());
    }

    @Test
    void nonZeroExitCodeCreatesExceptionWithResult() {
        CommandResult result = new CommandResult(2, "", "error");

        CommandException exception = result.toException();

        assertFalse(result.succeeded());
        assertSame(result, exception.result());
        assertEquals("Command exited with code 2", exception.getMessage());
    }

    @Test
    void timedOutResultDoesNotSucceed() {
        CommandResult result = new CommandResult(
                OptionalInt.empty(),
                "partial".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[0],
                "partial",
                "",
                false,
                false,
                true,
                Duration.ofMillis(100));

        CommandException exception = result.toException();

        assertFalse(result.succeeded());
        assertEquals("Command timed out", exception.getMessage());
    }

    @Test
    void byteAccessorsReturnDefensiveCopies() {
        byte[] stdout = {0, 1, 2};
        byte[] stderr = {3, 4};
        CommandResult result = new CommandResult(
                OptionalInt.of(0),
                stdout,
                stderr,
                "\u0000\u0001\u0002",
                "\u0003\u0004",
                false,
                false,
                false,
                Duration.ZERO);

        stdout[0] = 99;
        byte[] stdoutCopy = result.stdoutBytes();
        stdoutCopy[1] = 99;

        assertEquals(0, result.stdoutBytes()[0]);
        assertEquals(1, result.stdoutBytes()[1]);
        assertEquals(3, result.stderrBytes()[0]);
        assertNotSame(stdoutCopy, result.stdoutBytes());
    }

    @Test
    void comparesByteSnapshotsByContent() {
        CommandResult left = new CommandResult(
                OptionalInt.of(0), new byte[] {1}, new byte[] {2}, "a", "b", false, false, false, Duration.ZERO);
        CommandResult right = new CommandResult(
                OptionalInt.of(0), new byte[] {1}, new byte[] {2}, "a", "b", false, false, false, Duration.ZERO);

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void convenienceConstructorValidatesTextBeforeEncoding() {
        NullPointerException stdoutException =
                assertThrows(NullPointerException.class, () -> new CommandResult(0, null, ""));
        NullPointerException stderrException =
                assertThrows(NullPointerException.class, () -> new CommandResult(0, "", null));

        assertEquals("stdout", stdoutException.getMessage());
        assertEquals("stderr", stderrException.getMessage());
    }
}
