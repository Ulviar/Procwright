package com.github.ulviar.icli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;

final class ExecutionPlanResolver {

    private ExecutionPlanResolver() {}

    static ExecutionPlan resolve(CommandSpec spec, RunOptions options, CommandInvocation invocation) {
        if (spec.usesShell()) {
            return resolveShell(spec, options, invocation);
        }
        return resolveDirect(spec, options, invocation);
    }

    private static ExecutionPlan resolveDirect(CommandSpec spec, RunOptions options, CommandInvocation invocation) {
        ArrayList<String> command = new ArrayList<>();
        command.add(spec.executable());
        command.addAll(spec.arguments());
        command.addAll(invocation.arguments());

        return plan(LaunchMode.DIRECT, command, spec, options, invocation);
    }

    private static ExecutionPlan resolveShell(CommandSpec spec, RunOptions options, CommandInvocation invocation) {
        if (!spec.arguments().isEmpty() || !invocation.arguments().isEmpty()) {
            throw new IllegalArgumentException("shell commands do not accept argv arguments");
        }
        return plan(LaunchMode.SHELL, SystemShell.command(spec.executable()), spec, options, invocation);
    }

    private static ExecutionPlan plan(
            LaunchMode launchMode,
            java.util.List<String> command,
            CommandSpec spec,
            RunOptions options,
            CommandInvocation invocation) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.putAll(spec.environment());
        environment.putAll(invocation.environment());

        Optional<Path> workingDirectory = invocation.workingDirectory().or(spec::workingDirectory);

        return new ExecutionPlan(
                launchMode,
                command,
                workingDirectory,
                environment,
                bounded(invocation.capturePolicy().orElse(options.capturePolicy())),
                invocation.shutdownPolicy().orElse(options.shutdownPolicy()),
                invocation.timeout().orElse(options.timeout()),
                invocation.charset().orElse(options.charset()),
                invocation.outputMode().orElse(options.outputMode()));
    }

    private static CapturePolicy.Bounded bounded(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Bounded bounded) {
            return bounded;
        }
        throw new IllegalArgumentException("only bounded capture is supported by the one-shot kernel");
    }
}
