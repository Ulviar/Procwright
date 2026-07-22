/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Fully normalized immutable state for one-shot execution. */
public record RunSettings(
        LaunchSettings launch,
        CapturePolicy capturePolicy,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        CharsetPolicy charsetPolicy,
        OutputMode outputMode,
        Optional<CommandInput> input,
        DiagnosticsSettings diagnostics) {

    public RunSettings {
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(capturePolicy, "capturePolicy");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        timeout = DurationSupport.requireNonNegative(timeout, "timeout");
        Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        Objects.requireNonNull(outputMode, "outputMode");
        input = Objects.requireNonNull(input, "input");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static RunSettings defaults(LaunchSettings launch) {
        return new RunSettings(
                launch,
                CapturePolicy.bounded(1024 * 1024),
                ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofSeconds(30),
                CharsetPolicy.replace(StandardCharsets.UTF_8),
                OutputMode.SEPARATE,
                Optional.empty(),
                DiagnosticsSettings.disabled());
    }

    public ExecutionPlan plan() {
        return new ExecutionPlan(
                launch.plan(outputMode, TerminalPolicy.DISABLED),
                capturePolicy,
                shutdownPolicy,
                timeout,
                charsetPolicy,
                input.map(StdinPolicy::input).orElseGet(StdinPolicy::closed),
                diagnostics);
    }

    public RunSettings withLaunch(LaunchSettings launch) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }

    public RunSettings withCapturePolicy(CapturePolicy capturePolicy) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }

    public RunSettings withShutdownPolicy(ShutdownPolicy shutdownPolicy) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }

    public RunSettings withTimeout(Duration timeout) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }

    public RunSettings withCharsetPolicy(CharsetPolicy charsetPolicy) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }

    public RunSettings withOutputMode(OutputMode outputMode) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }

    public RunSettings withInput(CommandInput input) {
        return new RunSettings(
                launch,
                capturePolicy,
                shutdownPolicy,
                timeout,
                charsetPolicy,
                outputMode,
                Optional.of(Objects.requireNonNull(input, "input")),
                diagnostics);
    }

    public RunSettings withDiagnostics(DiagnosticsSettings diagnostics) {
        return new RunSettings(
                launch, capturePolicy, shutdownPolicy, timeout, charsetPolicy, outputMode, input, diagnostics);
    }
}
