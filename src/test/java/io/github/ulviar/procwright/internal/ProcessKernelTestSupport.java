/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

abstract class ProcessKernelTestSupport {

    static ProcessKernel kernel(ProcessKernel.ProcessStarter processStarter) {
        return kernel(process -> {}, processStarter);
    }

    static ProcessKernel kernel(Consumer<Process> postStartHook, ProcessKernel.ProcessStarter processStarter) {
        return kernel(postStartHook, processStarter, Duration.ofSeconds(5), System::nanoTime);
    }

    static ProcessKernel kernel(
            Consumer<Process> postStartHook, ProcessKernel.ProcessStarter processStarter, Duration cleanupTimeout) {
        return kernel(postStartHook, processStarter, cleanupTimeout, System::nanoTime);
    }

    static ProcessKernel kernel(
            Consumer<Process> postStartHook,
            ProcessKernel.ProcessStarter processStarter,
            Duration cleanupTimeout,
            LongSupplier nanoTime) {
        return new ProcessKernel(
                new ProcessKernel.Dependencies(postStartHook, processStarter, cleanupTimeout, nanoTime));
    }

    static int terminalCount(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    static ExecutionPlan executionPlan(
            DiagnosticsSettings diagnostics, Optional<CommandInput> input, OutputMode outputMode, Duration timeout) {
        return executionPlan(CapturePolicy.bounded(8), diagnostics, input, outputMode, timeout);
    }

    static ExecutionPlan executionPlan(
            CapturePolicy capturePolicy,
            DiagnosticsSettings diagnostics,
            Optional<CommandInput> input,
            OutputMode outputMode,
            Duration timeout) {
        return new ExecutionPlan(
                new LaunchPlan(
                        List.of("unused"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        Map.of(),
                        outputMode,
                        TerminalPolicy.DISABLED),
                capturePolicy,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                timeout,
                CharsetPolicy.report(StandardCharsets.UTF_8),
                input,
                diagnostics);
    }

    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static ExecutionPlan executionPlan(Charset charset) {
        return executionPlan(charset, DiagnosticsSettings.disabled());
    }

    static ExecutionPlan executionPlan(Charset charset, DiagnosticsSettings diagnostics) {
        return new ExecutionPlan(
                new LaunchPlan(
                        List.of("unused"),
                        Optional.empty(),
                        EnvironmentPolicy.INHERIT,
                        Map.of(),
                        OutputMode.SEPARATE,
                        TerminalPolicy.DISABLED),
                CapturePolicy.bounded(8),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ofSeconds(1),
                CharsetPolicy.report(charset),
                Optional.empty(),
                diagnostics);
    }

    static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
