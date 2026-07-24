/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import java.io.IOException;
import java.util.Objects;

/** Converts a resolved launch plan into one JDK process-builder invocation. */
final class ProcessLauncher {

    private ProcessLauncher() {}

    static Process start(LaunchPlan plan) {
        return start(plan, StdioConfig.pipes());
    }

    static Process start(LaunchPlan plan, StdioConfig stdio) {
        try {
            return builder(plan, stdio).start();
        } catch (IOException exception) {
            throw new CommandExecutionException(
                    CommandExecutionException.Reason.LAUNCH_FAILED,
                    "Could not start command: " + CommandEchoSupport.redactedSummary(plan),
                    exception);
        }
    }

    static ProcessBuilder builder(LaunchPlan plan, StdioConfig stdio) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(stdio, "stdio");
        ProcessBuilder builder = new ProcessBuilder(plan.command());
        plan.workingDirectory().ifPresent(path -> builder.directory(path.toFile()));
        if (plan.environmentPolicy() == EnvironmentPolicy.CLEAN) {
            builder.environment().clear();
        }
        builder.environment().putAll(plan.environment());
        builder.redirectErrorStream(plan.outputMode() == OutputMode.MERGED);
        builder.redirectInput(stdio.stdin());
        builder.redirectOutput(stdio.stdout());
        builder.redirectError(stdio.stderr());
        return builder;
    }
}
