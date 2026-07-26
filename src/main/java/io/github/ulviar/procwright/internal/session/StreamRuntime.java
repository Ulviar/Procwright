/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.CommandEchoSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.StreamSession;

public final class StreamRuntime {

    private StreamRuntime() {}

    public static StreamSession open(StreamExecutionPlan plan) {
        DiagnosticEmitter diagnostics = DiagnosticEmitter.of(
                plan.diagnostics(),
                "listen",
                () -> CommandEchoSupport.from(plan.sessionPlan().launchPlan()));
        diagnostics.emitBestEffort(DiagnosticEventType.COMMAND_PREPARED);
        return SessionRuntime.openStream(plan, diagnostics);
    }
}
