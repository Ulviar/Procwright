/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ExecutionPlan(
        LaunchPlan launchPlan,
        CapturePolicy capturePolicy,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        CharsetPolicy charsetPolicy,
        Optional<CommandInput> input,
        DiagnosticsSettings diagnostics) {

    public ExecutionPlan {
        Objects.requireNonNull(launchPlan, "launchPlan");
        Objects.requireNonNull(capturePolicy, "capturePolicy");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        input = Objects.requireNonNull(input, "input");
        Objects.requireNonNull(diagnostics, "diagnostics");
        requireCaptureCompatibleWithOutputMode(capturePolicy, launchPlan.outputMode());
    }

    private static void requireCaptureCompatibleWithOutputMode(CapturePolicy capturePolicy, OutputMode outputMode) {
        if (!(capturePolicy instanceof CapturePolicy.ToPath toPath)) {
            return;
        }
        if (toPath.merged() && outputMode != OutputMode.MERGED) {
            throw new IllegalArgumentException(
                    "single-file capture requires OutputMode.MERGED; use CapturePolicy.toPath(stdout, stderr) for"
                            + " separate streams");
        }
        if (!toPath.merged() && outputMode != OutputMode.SEPARATE) {
            throw new IllegalArgumentException(
                    "two-file capture requires OutputMode.SEPARATE; use CapturePolicy.toPath(merged) for merged"
                            + " output");
        }
    }
}
