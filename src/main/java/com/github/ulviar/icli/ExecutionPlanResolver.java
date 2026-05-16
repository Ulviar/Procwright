package com.github.ulviar.icli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ExecutionPlanResolver {

    private ExecutionPlanResolver() {}

    static ExecutionPlan resolve(CommandSpec spec, RunOptions options, CommandInvocation invocation) {
        return resolve(ScenarioProfile.run(options), spec, invocation);
    }

    static ExecutionPlan resolve(ScenarioProfile.Run profile, CommandSpec spec, CommandInvocation invocation) {
        InvocationShape invocationShape = shape(invocation);
        LaunchPlan launchPlan = launchPlan(profile, spec, invocationShape, outputMode(profile, invocation));
        return new ExecutionPlan(
                launchPlan,
                bounded(invocation.capturePolicy().orElse(profile.capturePolicy())),
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                invocation.timeout().orElse(profile.timeout()),
                invocation.charset().orElse(profile.charset()),
                invocation.input().map(StdinPolicy::input).orElse(profile.stdin()));
    }

    static SessionExecutionPlan resolve(
            ScenarioProfile.Interactive profile, CommandSpec spec, SessionInvocation invocation) {
        InvocationShape invocationShape = shape(invocation);
        LaunchPlan launchPlan = launchPlan(profile, spec, invocationShape, OutputMode.SEPARATE);
        return new SessionExecutionPlan(
                launchPlan,
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                invocation.idleTimeout().orElse(profile.idleTimeout()),
                invocation.charset().orElse(profile.charset()));
    }

    private static LaunchPlan launchPlan(
            ScenarioProfile profile, CommandSpec spec, InvocationShape invocation, OutputMode outputMode) {
        TerminalPolicy terminalPolicy = profile.terminalPolicy();
        if (terminalPolicy == TerminalPolicy.REQUIRED) {
            throw new IllegalArgumentException(
                    profile.name() + " scenario does not have a terminal-capable transport yet");
        }

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

    private static InvocationShape shape(CommandInvocation invocation) {
        return new InvocationShape(invocation.arguments(), invocation.workingDirectory(), invocation.environment());
    }

    private static InvocationShape shape(SessionInvocation invocation) {
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
