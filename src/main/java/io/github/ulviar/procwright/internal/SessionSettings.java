/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Immutable launch, lifecycle, terminal, and diagnostics state shared by session scenarios. */
public record SessionSettings(
        LaunchSettings launch,
        ShutdownPolicy shutdownPolicy,
        Duration idleTimeout,
        Charset charset,
        TerminalSettings terminal,
        DiagnosticsSettings diagnostics) {

    public SessionSettings {
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        idleTimeout = DurationSupport.requireNonNegative(idleTimeout, "idleTimeout");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static SessionSettings defaults(LaunchSettings launch) {
        return new SessionSettings(
                launch,
                ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                TerminalSettings.defaults(),
                DiagnosticsSettings.disabled());
    }

    public SessionExecutionPlan plan() {
        return new SessionExecutionPlan(
                launch.plan(io.github.ulviar.procwright.command.OutputMode.SEPARATE, terminal.policy()),
                shutdownPolicy,
                idleTimeout,
                charset,
                terminal.provider(),
                terminal.size());
    }

    public SessionSettings withLaunch(LaunchSettings launch) {
        return new SessionSettings(launch, shutdownPolicy, idleTimeout, charset, terminal, diagnostics);
    }

    public SessionSettings withShutdownPolicy(ShutdownPolicy shutdownPolicy) {
        return new SessionSettings(launch, shutdownPolicy, idleTimeout, charset, terminal, diagnostics);
    }

    public SessionSettings withIdleTimeout(Duration idleTimeout) {
        return new SessionSettings(launch, shutdownPolicy, idleTimeout, charset, terminal, diagnostics);
    }

    public SessionSettings withCharset(Charset charset) {
        return new SessionSettings(launch, shutdownPolicy, idleTimeout, charset, terminal, diagnostics);
    }

    public SessionSettings withTerminal(TerminalSettings terminal) {
        return new SessionSettings(launch, shutdownPolicy, idleTimeout, charset, terminal, diagnostics);
    }

    public SessionSettings withDiagnostics(DiagnosticsSettings diagnostics) {
        return new SessionSettings(launch, shutdownPolicy, idleTimeout, charset, terminal, diagnostics);
    }
}
