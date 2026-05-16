package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CommandServiceTest {

    @Test
    void createsServiceForExecutableWithDefaultOptions() {
        CommandService service = CommandService.forCommand("git");

        assertEquals("git", service.commandSpec().executable());
        assertEquals(RunOptions.defaults(), service.runOptions());
    }

    @Test
    void runValidatesConfigurationCallbackBeforeExecutionExists() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(NullPointerException.class, () -> service.run(null));
    }

    @Test
    void runScenarioIsExplicitlyUnavailableUntilKernelExists() {
        CommandService service = CommandService.forCommand("git");

        assertThrows(
                UnsupportedOperationException.class,
                () -> service.run(call -> call.args("status").capture(CapturePolicy.bounded(1024))));
    }
}
