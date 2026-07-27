/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandResult;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Immutable service that creates an isolated lifecycle owner for each one-shot command. */
public final class ProcessKernel {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private static final ProcessKernel STANDARD = new ProcessKernel(process -> {});

    private final Dependencies dependencies;

    private ProcessKernel(Consumer<Process> postStartHook) {
        this(new Dependencies(postStartHook, ProcessLauncher::start, CLEANUP_TIMEOUT, System::nanoTime));
    }

    ProcessKernel(Dependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public static ProcessKernel standard() {
        return STANDARD;
    }

    public static ProcessKernel withPostStartHook(Consumer<Process> hook) {
        return new ProcessKernel(hook);
    }

    public CommandResult run(ExecutionPlan plan) {
        CaptureTargetValidator.validate(plan.capturePolicy());
        return new OneShotExecution(plan, dependencies).execute();
    }

    record Dependencies(
            Consumer<Process> postStartHook,
            ProcessStarter processStarter,
            Duration cleanupTimeout,
            LongSupplier nanoTime) {

        Dependencies {
            Objects.requireNonNull(postStartHook, "postStartHook");
            Objects.requireNonNull(processStarter, "processStarter");
            Objects.requireNonNull(cleanupTimeout, "cleanupTimeout");
            Objects.requireNonNull(nanoTime, "nanoTime");
            if (cleanupTimeout.isNegative() || cleanupTimeout.isZero()) {
                throw new IllegalArgumentException("cleanupTimeout must be positive");
            }
        }
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(LaunchPlan plan, StdioConfig stdio);
    }
}
