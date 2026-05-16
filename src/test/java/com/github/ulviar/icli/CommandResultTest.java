package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CommandResultTest {

    @Test
    void zeroExitCodeSucceeds() {
        CommandResult result = new CommandResult(0, "out", "");

        assertTrue(result.succeeded());
    }

    @Test
    void nonZeroExitCodeCreatesExceptionWithResult() {
        CommandResult result = new CommandResult(2, "", "error");

        CommandException exception = result.toException();

        assertFalse(result.succeeded());
        assertSame(result, exception.result());
        assertEquals("Command exited with code 2", exception.getMessage());
    }
}
