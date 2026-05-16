package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        CommandResult result =
                new CommandResult(OptionalInt.empty(), "partial", "", false, false, true, Duration.ofMillis(100));

        CommandException exception = result.toException();

        assertFalse(result.succeeded());
        assertEquals("Command timed out", exception.getMessage());
    }
}
