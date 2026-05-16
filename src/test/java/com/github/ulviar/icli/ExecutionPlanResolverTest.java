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

        ExecutionPlan plan = ExecutionPlanResolver.resolve(spec, RunOptions.defaults(), invocation);

        assertEquals(LaunchMode.DIRECT, plan.launchMode());
        assertEquals(java.util.List.of("tool", "base", "call"), plan.command());
        assertEquals(Path.of("call-dir"), plan.workingDirectory().orElseThrow());
        assertEquals("1", plan.environment().get("BASE"));
        assertEquals("2", plan.environment().get("CALL"));
    }

    @Test
    void rejectsArgumentsForShellCommand() {
        CommandSpec spec = CommandSpec.shell("echo hello");
        CommandInvocation invocation =
                CommandInvocation.builder().args("unexpected").build();

        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionPlanResolver.resolve(spec, RunOptions.defaults(), invocation));
    }
}
