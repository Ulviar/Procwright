package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;

public record SessionExecutionPlan(
        LaunchPlan launchPlan,
        ShutdownPolicy shutdownPolicy,
        Duration idleTimeout,
        Charset charset,
        PtyProvider ptyProvider,
        TerminalSize terminalSize) {

    public SessionExecutionPlan {
        Objects.requireNonNull(launchPlan, "launchPlan");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative");
        }
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(ptyProvider, "ptyProvider");
        Objects.requireNonNull(terminalSize, "terminalSize");
    }
}
