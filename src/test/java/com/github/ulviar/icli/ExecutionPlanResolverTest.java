package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
        assertEquals(StdinPolicy.closed(), plan.stdin());
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

        assertEquals(StdinPolicy.input(CommandInput.utf8("hello")), plan.stdin());
    }

    @Test
    void resolvesInteractiveSessionFromSessionSpecificOverrides() {
        CommandSpec spec = CommandSpec.builder("tool")
                .args("base")
                .workingDirectory(Path.of("base-dir"))
                .putEnvironment("BASE", "1")
                .build();
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        SessionInvocation invocation = SessionInvocation.builder()
                .args("call")
                .workingDirectory(Path.of("call-dir"))
                .putEnvironment("CALL", "2")
                .shutdown(shutdownPolicy)
                .idleTimeout(Duration.ofSeconds(3))
                .charset(StandardCharsets.UTF_8)
                .terminal(TerminalPolicy.REQUIRED)
                .build();

        SessionExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.interactive(SessionOptions.defaults()), spec, invocation);

        assertEquals(LaunchMode.DIRECT, plan.launchPlan().launchMode());
        assertEquals(
                java.util.List.of("tool", "base", "call"), plan.launchPlan().command());
        assertEquals(Path.of("call-dir"), plan.launchPlan().workingDirectory().orElseThrow());
        assertEquals("1", plan.launchPlan().environment().get("BASE"));
        assertEquals("2", plan.launchPlan().environment().get("CALL"));
        assertEquals(OutputMode.SEPARATE, plan.launchPlan().outputMode());
        assertEquals(TerminalPolicy.REQUIRED, plan.launchPlan().terminalPolicy());
        assertEquals(shutdownPolicy, plan.shutdownPolicy());
        assertEquals(Duration.ofSeconds(3), plan.idleTimeout());
        assertEquals(StandardCharsets.UTF_8, plan.charset());
    }

    @Test
    void resolvesTerminalPolicyFromSessionDefaultsWhenCallDoesNotOverrideIt() {
        CommandSpec spec = CommandSpec.of("tool");
        SessionOptions options = SessionOptions.defaults().withTerminalPolicy(TerminalPolicy.AUTO);
        SessionInvocation invocation = SessionInvocation.builder().build();

        SessionExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.interactive(options), spec, invocation);

        assertEquals(TerminalPolicy.AUTO, plan.launchPlan().terminalPolicy());
        assertEquals(PtyProvider.system(), plan.ptyProvider());
        assertEquals(TerminalSize.defaults(), plan.terminalSize());
    }

    @Test
    void resolvesListenScenarioFromStreamSpecificOverrides() {
        CommandSpec spec = CommandSpec.builder("tool")
                .args("base")
                .workingDirectory(Path.of("base-dir"))
                .putEnvironment("BASE", "1")
                .build();
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        StreamListener listener = chunk -> {};
        StreamInvocation invocation = StreamInvocation.builder()
                .args("call")
                .workingDirectory(Path.of("call-dir"))
                .putEnvironment("CALL", "2")
                .shutdown(shutdownPolicy)
                .timeout(Duration.ofSeconds(3))
                .keepStdinOpen()
                .onOutput(listener)
                .build();

        StreamExecutionPlan plan =
                ExecutionPlanResolver.resolve(ScenarioProfile.stream(StreamOptions.defaults()), spec, invocation);

        assertEquals(LaunchMode.DIRECT, plan.sessionPlan().launchPlan().launchMode());
        assertEquals(
                java.util.List.of("tool", "base", "call"),
                plan.sessionPlan().launchPlan().command());
        assertEquals(
                Path.of("call-dir"),
                plan.sessionPlan().launchPlan().workingDirectory().orElseThrow());
        assertEquals("1", plan.sessionPlan().launchPlan().environment().get("BASE"));
        assertEquals("2", plan.sessionPlan().launchPlan().environment().get("CALL"));
        assertEquals(OutputMode.SEPARATE, plan.sessionPlan().launchPlan().outputMode());
        assertEquals(TerminalPolicy.DISABLED, plan.sessionPlan().launchPlan().terminalPolicy());
        assertEquals(shutdownPolicy, plan.sessionPlan().shutdownPolicy());
        assertEquals(Duration.ZERO, plan.sessionPlan().idleTimeout());
        assertEquals(StreamStdinPolicy.KEEP_OPEN, plan.stdinPolicy());
        assertEquals(listener, plan.listener());
        assertEquals(Duration.ofSeconds(3), plan.timeout());
    }

    @Test
    void rejectsTerminalRequiredForOneShotRunUntilRunPtyTransportExists() {
        CommandSpec spec = CommandSpec.of("tool");
        ScenarioProfile.Run terminalProfile = new ScenarioProfile.Run(
                StdinPolicy.closed(),
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
