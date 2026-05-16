package com.github.ulviar.icli;

import java.util.Objects;

sealed interface ProcessTransport permits PipeTransport, PtyTransport {

    static ProcessTransport resolve(SessionExecutionPlan plan) {
        return switch (plan.launchPlan().terminalPolicy()) {
            case DISABLED -> PipeTransport.INSTANCE;
            case AUTO -> plan.ptyProvider().available() ? new PtyTransport(plan.ptyProvider()) : PipeTransport.INSTANCE;
            case REQUIRED -> {
                if (!plan.ptyProvider().available()) {
                    throw new CommandExecutionException("Terminal is required but PTY provider is unavailable: "
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
        return provider.start(PtyRequest.from(plan.launchPlan(), plan.terminalSize()));
    }
}
