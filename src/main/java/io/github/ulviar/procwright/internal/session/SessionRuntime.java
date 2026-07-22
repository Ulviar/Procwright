/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ProcessTransport;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;

public final class SessionRuntime {

    private SessionRuntime() {}

    public static DefaultSession open(SessionExecutionPlan plan) {
        return open(
                plan,
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled(), "session", () -> CommandEchoSupport.from(plan.launchPlan())));
    }

    public static DefaultSession open(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        return open(plan, diagnostics, diagnostics);
    }

    static DefaultSession openForStream(SessionExecutionPlan plan, DiagnosticEmitter diagnostics) {
        DiagnosticEmitter sessionDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled(), "stream-session", () -> CommandEchoSupport.from(plan.launchPlan()));
        return open(plan, diagnostics, sessionDiagnostics);
    }

    private static DefaultSession open(
            SessionExecutionPlan plan, DiagnosticEmitter lifecycleDiagnostics, DiagnosticEmitter sessionDiagnostics) {
        Process process;
        try {
            process = ProcessTransport.resolve(plan).start(plan);
        } catch (RuntimeException | Error failure) {
            lifecycleDiagnostics.emitProcessFailure(failure);
            throw failure;
        }
        try {
            return DefaultSession.openTransactionally(
                    process,
                    plan.idleTimeout(),
                    plan.shutdownPolicy(),
                    plan.charset(),
                    sessionDiagnostics,
                    () -> lifecycleDiagnostics.emit(
                            DiagnosticEventType.PROCESS_STARTED,
                            DiagnosticEmitter.attributes("pid", Long.toString(process.pid()))));
        } catch (RuntimeException | Error failure) {
            lifecycleDiagnostics.emitProcessFailure(failure);
            throw failure;
        }
    }
}
