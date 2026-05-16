package com.github.ulviar.icli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;

final class ExecutionPlanResolver {

    private ExecutionPlanResolver() {}

    static ExecutionPlan resolve(CommandSpec spec, RunOptions options, CommandInvocation invocation) {
        return resolve(ScenarioProfile.run(options), spec, invocation);
    }

    static ExecutionPlan resolve(ScenarioProfile profile, CommandSpec spec, CommandInvocation invocation) {
        if (spec.usesShell()) {
            return resolveShell(profile, spec, invocation);
        }
        return resolveDirect(profile, spec, invocation);
    }

    private static ExecutionPlan resolveDirect(
            ScenarioProfile profile, CommandSpec spec, CommandInvocation invocation) {
        ArrayList<String> command = new ArrayList<>();
        command.add(spec.executable());
        command.addAll(spec.arguments());
        command.addAll(invocation.arguments());

        return plan(LaunchMode.DIRECT, command, profile, spec, invocation);
    }

    private static ExecutionPlan resolveShell(ScenarioProfile profile, CommandSpec spec, CommandInvocation invocation) {
        if (!spec.arguments().isEmpty() || !invocation.arguments().isEmpty()) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        return plan(LaunchMode.SHELL, SystemShell.command(spec.executable()), profile, spec, invocation);
    }

    private static ExecutionPlan plan(
            LaunchMode launchMode,
            java.util.List<String> command,
            ScenarioProfile profile,
            CommandSpec spec,
            CommandInvocation invocation) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.putAll(spec.environment());
        environment.putAll(invocation.environment());

        Optional<Path> workingDirectory = invocation.workingDirectory().or(spec::workingDirectory);
        TerminalPolicy terminalPolicy = profile.terminalPolicy();
        if (profile.terminalPolicy() == TerminalPolicy.REQUIRED) {
            throw new IllegalArgumentException("run scenario does not have a terminal-capable transport yet");
        }

        return new ExecutionPlan(
                launchMode,
                command,
                workingDirectory,
                environment,
                bounded(invocation.capturePolicy().orElse(profile.capturePolicy())),
                invocation.shutdownPolicy().orElse(profile.shutdownPolicy()),
                invocation.timeout().orElse(profile.timeout()),
                invocation.charset().orElse(profile.charset()),
                invocation.outputMode().orElse(profile.outputMode()),
                invocation.input().orElse(profile.stdin()),
                terminalPolicy);
    }

    private static CapturePolicy.Bounded bounded(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Bounded bounded) {
            return bounded;
        }
        throw new IllegalArgumentException("only bounded capture is supported by the one-shot kernel");
    }
}
