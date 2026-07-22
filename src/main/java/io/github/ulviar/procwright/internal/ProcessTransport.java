/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import java.util.Objects;

public sealed interface ProcessTransport permits PipeTransport, PtyTransport {

    public static ProcessTransport resolve(SessionExecutionPlan plan) {
        return switch (plan.launchPlan().terminalPolicy()) {
            case DISABLED -> PipeTransport.INSTANCE;
            case AUTO -> plan.ptyProvider().available() ? new PtyTransport(plan.ptyProvider()) : PipeTransport.INSTANCE;
            case REQUIRED -> {
                if (!plan.ptyProvider().available()) {
                    throw new CommandExecutionException(
                            CommandExecutionException.Reason.LAUNCH_FAILED,
                            "Terminal is required but PTY provider is unavailable: "
                                    + plan.ptyProvider().description());
                }
                yield new PtyTransport(plan.ptyProvider());
            }
        };
    }

    Process start(SessionExecutionPlan plan);
}

final class PipeTransport implements ProcessTransport {

    static final PipeTransport INSTANCE = new PipeTransport();

    private PipeTransport() {}

    @Override
    public Process start(SessionExecutionPlan plan) {
        return ProcessLifecycle.start(plan.launchPlan());
    }
}

final class PtyTransport implements ProcessTransport {

    private final PtyProvider provider;

    PtyTransport(PtyProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public Process start(SessionExecutionPlan plan) {
        LaunchPlan launchPlan = plan.launchPlan();
        Process process = provider.start(new PtyRequest(
                launchPlan.command(),
                launchPlan.workingDirectory(),
                launchPlan.environmentPolicy(),
                launchPlan.environment(),
                plan.terminalSize()));
        return ProcessTreeScanner.shared().guard(process);
    }
}
