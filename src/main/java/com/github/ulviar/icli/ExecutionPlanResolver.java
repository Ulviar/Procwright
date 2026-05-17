package com.github.ulviar.icli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ExecutionPlanResolver {

    private ExecutionPlanResolver() {}

    static ExecutionPlan resolve(CommandSpec spec, RunOptions options, CommandInvocation invocation) {
        return resolve(ScenarioProfile.run(options), spec, invocation, DiagnosticsOptions.defaults());
    }

    static ExecutionPlan resolve(ScenarioProfile.Run profile, CommandSpec spec, CommandInvocation invocation) {
        return resolve(profile, spec, invocation, DiagnosticsOptions.defaults());
    }

    static ExecutionPlan resolve(
            ScenarioProfile.Run profile,
            CommandSpec spec,
            CommandInvocation invocation,
            DiagnosticsOptions diagnosticsOptions) {
        rejectTerminalPolicy(profile.terminalPolicy(), "run");
        InvocationShape invocationShape = shape(invocation);
        LaunchPlan launchPlan = launchPlan(profile, spec, invocationShape, outputMode(profile, invocation));
        return new ExecutionPlan(
                launchPlan,
                bounded(invocation.capturePolicy().orElse(profile.capturePolicy())),
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                invocation.timeout().orElse(profile.timeout()),
                invocation.charset().orElse(profile.charset()),
                invocation.input().map(StdinPolicy::input).orElse(profile.stdin()),
                diagnosticsOptions);
    }

    static SessionExecutionPlan resolve(
            ScenarioProfile.Interactive profile, CommandSpec spec, SessionInvocation invocation) {
        InvocationShape invocationShape = shape(invocation);
        LaunchPlan launchPlan = launchPlan(
                profile,
                spec,
                invocationShape,
                OutputMode.SEPARATE,
                invocation.terminalPolicy().orElse(profile.terminalPolicy()));
        return new SessionExecutionPlan(
                launchPlan,
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                invocation.idleTimeout().orElse(profile.idleTimeout()),
                invocation.charset().orElse(profile.charset()),
                profile.ptyProvider(),
                profile.terminalSize());
    }

    static SessionExecutionPlan resolve(
            ScenarioProfile.Interactive profile, CommandSpec spec, LineSessionInvocation invocation) {
        InvocationShape invocationShape = shape(invocation);
        LaunchPlan launchPlan = launchPlan(
                profile,
                spec,
                invocationShape,
                OutputMode.SEPARATE,
                invocation.terminalPolicy().orElse(profile.terminalPolicy()));
        return new SessionExecutionPlan(
                launchPlan,
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                invocation.idleTimeout().orElse(profile.idleTimeout()),
                profile.charset(),
                profile.ptyProvider(),
                profile.terminalSize());
    }

    static StreamExecutionPlan resolve(ScenarioProfile.Stream profile, CommandSpec spec, StreamInvocation invocation) {
        return resolve(profile, spec, invocation, DiagnosticsOptions.defaults());
    }

    static StreamExecutionPlan resolve(
            ScenarioProfile.Stream profile,
            CommandSpec spec,
            StreamInvocation invocation,
            DiagnosticsOptions diagnosticsOptions) {
        rejectTerminalPolicy(profile.terminalPolicy(), "listen");
        InvocationShape invocationShape = shape(invocation);
        LaunchPlan launchPlan = launchPlan(profile, spec, invocationShape, OutputMode.SEPARATE);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                Duration.ZERO,
                profile.charset(),
                PtyProvider.unavailable("listen scenario does not request PTY"),
                TerminalSize.defaults());
        return new StreamExecutionPlan(
                sessionPlan,
                invocation.timeout().orElse(profile.timeout()),
                invocation.stdinPolicy(),
                profile.diagnosticLimit(),
                invocation.listener(),
                diagnosticsOptions);
    }

    private static LaunchPlan launchPlan(
            ScenarioProfile profile, CommandSpec spec, InvocationShape invocation, OutputMode outputMode) {
        return launchPlan(profile, spec, invocation, outputMode, profile.terminalPolicy());
    }

    private static LaunchPlan launchPlan(
            ScenarioProfile profile,
            CommandSpec spec,
            InvocationShape invocation,
            OutputMode outputMode,
            TerminalPolicy terminalPolicy) {
        if (spec.usesShell()) {
            if (!spec.arguments().isEmpty() || !invocation.arguments().isEmpty()) {
                throw new IllegalArgumentException("shell commands do not accept argv arguments");
            }
            return plan(
                    LaunchMode.SHELL,
                    SystemShell.command(spec.executable()),
                    terminalPolicy,
                    outputMode,
                    spec,
                    invocation);
        }

        ArrayList<String> command = new ArrayList<>();
        command.add(spec.executable());
        command.addAll(spec.arguments());
        command.addAll(invocation.arguments());
        return plan(LaunchMode.DIRECT, command, terminalPolicy, outputMode, spec, invocation);
    }

    private static LaunchPlan plan(
            LaunchMode launchMode,
            List<String> command,
            TerminalPolicy terminalPolicy,
            OutputMode outputMode,
            CommandSpec spec,
            InvocationShape invocation) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.putAll(spec.environment());
        environment.putAll(invocation.environment());

        Optional<Path> workingDirectory = invocation.workingDirectory().or(spec::workingDirectory);

        return new LaunchPlan(launchMode, command, workingDirectory, environment, outputMode, terminalPolicy);
    }

    private static OutputMode outputMode(ScenarioProfile.Run profile, CommandInvocation invocation) {
        return invocation.outputMode().orElse(profile.outputMode());
    }

    private static void rejectTerminalPolicy(TerminalPolicy terminalPolicy, String scenario) {
        if (terminalPolicy != TerminalPolicy.DISABLED) {
            throw new IllegalArgumentException(scenario + " scenario does not request terminal capability");
        }
    }

    private static InvocationShape shape(CommandInvocation invocation) {
        return new InvocationShape(invocation.arguments(), invocation.workingDirectory(), invocation.environment());
    }

    private static InvocationShape shape(SessionInvocation invocation) {
        return new InvocationShape(invocation.arguments(), invocation.workingDirectory(), invocation.environment());
    }

    private static InvocationShape shape(LineSessionInvocation invocation) {
        return new InvocationShape(invocation.arguments(), invocation.workingDirectory(), invocation.environment());
    }

    private static InvocationShape shape(StreamInvocation invocation) {
        return new InvocationShape(invocation.arguments(), invocation.workingDirectory(), invocation.environment());
    }

    private static CapturePolicy.Bounded bounded(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Bounded bounded) {
            return bounded;
        }
        throw new IllegalArgumentException("only bounded capture is supported by the one-shot kernel");
    }

    private record InvocationShape(
            List<String> arguments, Optional<Path> workingDirectory, Map<String, String> environment) {

        private InvocationShape {
            arguments = List.copyOf(arguments);
            workingDirectory = java.util.Objects.requireNonNull(workingDirectory, "workingDirectory");
            environment = Map.copyOf(environment);
        }
    }
}
