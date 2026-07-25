/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.OutputMode;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;

/** Resolves one-shot redirects, stdin work, and the exact I/O task count before process launch. */
final class OneShotIoPlan {

    private final StdioConfig stdio;
    private final Optional<CapturePolicy.Bounded> boundedCapture;
    private final boolean capturesStderr;
    private final StdinOperation stdinOperation;

    private OneShotIoPlan(
            StdioConfig stdio,
            Optional<CapturePolicy.Bounded> boundedCapture,
            boolean capturesStderr,
            StdinOperation stdinOperation) {
        this.stdio = Objects.requireNonNull(stdio, "stdio");
        this.boundedCapture = Objects.requireNonNull(boundedCapture, "boundedCapture");
        this.capturesStderr = capturesStderr;
        this.stdinOperation = Objects.requireNonNull(stdinOperation, "stdinOperation");
    }

    static OneShotIoPlan resolve(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        StdinResolution stdin = resolveStdin(plan.input());
        Optional<CapturePolicy.Bounded> boundedCapture =
                plan.capturePolicy() instanceof CapturePolicy.Bounded bounded ? Optional.of(bounded) : Optional.empty();
        boolean capturesStderr = boundedCapture.isPresent() && plan.launchPlan().outputMode() == OutputMode.SEPARATE;
        return new OneShotIoPlan(
                new StdioConfig(
                        stdin.redirect(), resolveStdout(plan.capturePolicy()), resolveStderr(plan.capturePolicy())),
                boundedCapture,
                capturesStderr,
                stdin.operation());
    }

    StdioConfig stdio() {
        return stdio;
    }

    boolean capturesStdout() {
        return boundedCapture.isPresent();
    }

    boolean capturesStderr() {
        return capturesStderr;
    }

    CapturePolicy.Bounded boundedCapture() {
        return boundedCapture.orElseThrow(() -> new IllegalStateException("output capture is disabled"));
    }

    StdinOperation stdinOperation() {
        return stdinOperation;
    }

    int taskCount() {
        int count = capturesStdout() ? 1 : 0;
        count += capturesStderr ? 1 : 0;
        return stdinOperation.action() == StdinAction.WRITE ? count + 1 : count;
    }

    private static StdinResolution resolveStdin(Optional<CommandInput> input) {
        if (input.isEmpty()) {
            return new StdinResolution(ProcessBuilder.Redirect.PIPE, StdinOperation.close());
        }
        CommandInput selected = input.orElseThrow();
        return selected.path()
                .map(path -> {
                    if (!Files.isRegularFile(path)) {
                        throw new CommandExecutionException(
                                CommandExecutionException.Reason.LAUNCH_FAILED,
                                "Stdin source file does not exist or is not a regular file: " + path);
                    }
                    return new StdinResolution(ProcessBuilder.Redirect.from(path.toFile()), StdinOperation.redirect());
                })
                .orElseGet(() -> new StdinResolution(ProcessBuilder.Redirect.PIPE, StdinOperation.write(selected)));
    }

    private static ProcessBuilder.Redirect resolveStdout(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Discard) {
            return ProcessBuilder.Redirect.DISCARD;
        }
        if (capturePolicy instanceof CapturePolicy.ToPath toPath) {
            return ProcessBuilder.Redirect.to(toPath.stdout().toFile());
        }
        return ProcessBuilder.Redirect.PIPE;
    }

    private static ProcessBuilder.Redirect resolveStderr(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Discard) {
            return ProcessBuilder.Redirect.DISCARD;
        }
        if (capturePolicy instanceof CapturePolicy.ToPath toPath) {
            return toPath.stderr()
                    .map(path -> ProcessBuilder.Redirect.to(path.toFile()))
                    .orElse(ProcessBuilder.Redirect.DISCARD);
        }
        return ProcessBuilder.Redirect.PIPE;
    }

    enum StdinAction {
        CLOSE,
        WRITE,
        REDIRECT
    }

    static final class StdinOperation {

        private final StdinAction action;
        private final Optional<CommandInput> writeInput;

        private StdinOperation(StdinAction action, Optional<CommandInput> writeInput) {
            this.action = Objects.requireNonNull(action, "action");
            this.writeInput = Objects.requireNonNull(writeInput, "writeInput");
        }

        private static StdinOperation close() {
            return new StdinOperation(StdinAction.CLOSE, Optional.empty());
        }

        private static StdinOperation write(CommandInput input) {
            return new StdinOperation(StdinAction.WRITE, Optional.of(Objects.requireNonNull(input, "input")));
        }

        private static StdinOperation redirect() {
            return new StdinOperation(StdinAction.REDIRECT, Optional.empty());
        }

        StdinAction action() {
            return action;
        }

        CommandInput writeInput() {
            return writeInput.orElseThrow(() -> new IllegalStateException("stdin operation does not write input"));
        }
    }

    private record StdinResolution(ProcessBuilder.Redirect redirect, StdinOperation operation) {}
}
