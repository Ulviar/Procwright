package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ExecutionPlanResolverTest {

    @Test
    void resolvesDirectCommandFromSpecDefaultsAndInvocationOverrides() {
        CommandSpec spec = CommandSpec.builder("tool")
                .args("base")
                .workingDirectory(Path.of("base-dir"))
                .putEnvironment("BASE", "1")
                .build();
        CommandInvocation invocation = CommandInvocation.builder()
                .args("call")
                .workingDirectory(Path.of("call-dir"))
                .putEnvironment("CALL", "2")
                .build();

        ExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.run(RunOptions.defaults()), spec, invocation);

        assertEquals(LaunchMode.DIRECT, plan.launchMode());
        assertEquals(java.util.List.of("tool", "base", "call"), plan.command());
        assertEquals(Path.of("call-dir"), plan.workingDirectory().orElseThrow());
        assertEquals("1", plan.environment().get("BASE"));
        assertEquals("2", plan.environment().get("CALL"));
        assertEquals(TerminalPolicy.DISABLED, plan.terminalPolicy());
        assertEquals(CommandInput.closed(), plan.stdin());
    }

    @Test
    void rejectsArgumentsForShellCommand() {
        CommandSpec spec = CommandSpec.shell("echo hello");
        CommandInvocation invocation =
                CommandInvocation.builder().args("unexpected").build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionPlanResolver.resolve(ScenarioProfile.run(RunOptions.defaults()), spec, invocation));
    }

    @Test
    void perCallInputOverridesRunProfileClosedStdin() {
        CommandSpec spec = CommandSpec.of("tool");
        CommandInvocation invocation =
                CommandInvocation.builder().input("hello").build();

        ExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.run(RunOptions.defaults()), spec, invocation);

        assertEquals(CommandInput.utf8("hello"), plan.stdin());
    }

    @Test
    void rejectsTerminalRequiredProfileUntilTerminalTransportExists() {
        CommandSpec spec = CommandSpec.of("tool");
        ScenarioProfile terminalProfile = new ScenarioProfile(
                "terminal-run",
                CommandInput.closed(),
                (CapturePolicy.Bounded) RunOptions.defaults().capturePolicy(),
                RunOptions.defaults().shutdownPolicy(),
                RunOptions.defaults().timeout(),
                RunOptions.defaults().charset(),
                RunOptions.defaults().outputMode(),
                TerminalPolicy.REQUIRED);

        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionPlanResolver.resolve(
                        terminalProfile, spec, CommandInvocation.builder().build()));
    }
}
