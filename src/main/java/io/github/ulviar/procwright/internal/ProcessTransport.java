/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;

/** Starts the transport selected by a normalized session plan. */
public final class ProcessTransport {

    private ProcessTransport() {}

    public static Process start(SessionExecutionPlan plan) {
        return switch (plan.launchPlan().terminalPolicy()) {
            case DISABLED -> ProcessLauncher.start(plan.launchPlan());
            case AUTO ->
                plan.ptyProvider().available()
                        ? startPty(plan, plan.ptyProvider())
                        : ProcessLauncher.start(plan.launchPlan());
            case REQUIRED -> {
                if (!plan.ptyProvider().available()) {
                    throw new CommandExecutionException(
                            CommandExecutionException.Reason.LAUNCH_FAILED,
                            "Terminal is required but PTY provider is unavailable: "
                                    + plan.ptyProvider().description());
                }
                yield startPty(plan, plan.ptyProvider());
            }
        };
    }

    private static Process startPty(SessionExecutionPlan plan, PtyProvider provider) {
        LaunchPlan launch = plan.launchPlan();
        return provider.start(new PtyRequest(
                launch.command(),
                launch.workingDirectory(),
                launch.environmentPolicy(),
                launch.environment(),
                plan.terminalSize()));
    }
}
