/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import io.github.ulviar.procwright.session.StreamSession;
import java.util.Objects;

public final class StreamRuntime {

    private StreamRuntime() {}

    public static StreamSession open(StreamExecutionPlan plan) {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                plan.diagnostics(),
                "listen",
                () -> CommandEchoSupport.from(plan.sessionPlan().launchPlan()));
        diagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        DefaultSession session = SessionRuntime.openForStream(plan.sessionPlan(), diagnostics);
        return finishOpen(session, plan, diagnostics, DefaultStreamSession::new);
    }

    static StreamSession finishOpen(
            DefaultSession session,
            StreamExecutionPlan plan,
            DiagnosticEmitter diagnostics,
            StreamSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        try {
            return factory.open(session, plan, diagnostics);
        } catch (RuntimeException | Error failure) {
            diagnostics.emitProcessFailure(failure);
            closePreserving(session, failure);
            throw failure;
        }
    }

    static void closePreserving(AutoCloseable resource, Throwable primaryFailure) {
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            SuppressionSupport.attach(primaryFailure, cleanupFailure);
        }
    }

    @FunctionalInterface
    interface StreamSessionFactory {

        StreamSession open(DefaultSession session, StreamExecutionPlan plan, DiagnosticEmitter diagnostics);
    }
}
