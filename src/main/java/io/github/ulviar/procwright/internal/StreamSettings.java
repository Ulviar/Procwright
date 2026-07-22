/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Fully normalized immutable state for listen-only streaming. */
public record StreamSettings(
        LaunchSettings launch,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        Charset charset,
        int diagnosticLimit,
        StreamListener listener,
        DiagnosticsSettings diagnostics) {

    public StreamSettings {
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        timeout = DurationSupport.requireNonNegative(timeout, "timeout");
        Objects.requireNonNull(charset, "charset");
        if (diagnosticLimit <= 0) {
            throw new IllegalArgumentException("diagnosticLimit must be positive");
        }
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static StreamSettings defaults(LaunchSettings launch) {
        return new StreamSettings(
                launch,
                ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                64 * 1024,
                StreamListener.noop(),
                DiagnosticsSettings.disabled());
    }

    public StreamExecutionPlan plan() {
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launch.plan(OutputMode.SEPARATE, TerminalPolicy.DISABLED),
                shutdownPolicy,
                Duration.ZERO,
                charset,
                PtyProvider.unavailable("listen scenario does not request PTY"),
                TerminalSize.defaults());
        return new StreamExecutionPlan(sessionPlan, timeout, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withLaunch(LaunchSettings launch) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withShutdownPolicy(ShutdownPolicy shutdownPolicy) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withTimeout(Duration timeout) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withCharset(Charset charset) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withDiagnosticLimit(int diagnosticLimit) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withListener(StreamListener listener) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }

    public StreamSettings withDiagnostics(DiagnosticsSettings diagnostics) {
        return new StreamSettings(launch, shutdownPolicy, timeout, charset, diagnosticLimit, listener, diagnostics);
    }
}
