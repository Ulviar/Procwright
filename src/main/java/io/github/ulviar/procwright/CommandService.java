/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.internal.ProcessKernel;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import java.util.Objects;
import java.util.function.Supplier;

/** Scenario selector bound to one immutable base command. */
public final class CommandService {

    private final ScenarioRuntime runtime;

    CommandService(CommandSpec commandSpec, ProcessKernel processKernel) {
        runtime = new ScenarioRuntime(
                Objects.requireNonNull(commandSpec, "commandSpec"),
                Objects.requireNonNull(processKernel, "processKernel"));
    }

    /**
     * Selects finite one-shot execution.
     *
     * @return a new run draft initialized with run defaults
     */
    public RunScenario.Draft run() {
        return RunScenario.draft(runtime);
    }

    /**
     * Selects a raw interactive session.
     *
     * @return a new interactive-session draft
     */
    public InteractiveScenario.Draft interactive() {
        return InteractiveScenario.draft(runtime);
    }

    /**
     * Selects a line-oriented request/response session.
     *
     * @return a new line-session draft
     */
    public LineSessionScenario.Draft lineSession() {
        return LineSessionScenario.draft(runtime);
    }

    /**
     * Selects a listen-only streaming session.
     *
     * @return a new listen-only stream draft
     */
    public StreamScenario.Draft listen() {
        return StreamScenario.draft(runtime);
    }

    /**
     * Selects a reusable typed protocol session backed by a fresh-adapter factory.
     *
     * @param <I> request type
     * @param <O> response type
     * @param adapterFactory concurrent-safe factory that returns a fresh, non-null adapter for each opened session or
     *     pool worker; Procwright may invoke it concurrently and does not serialize calls
     * @return a persistent protocol-session draft that can also create pools
     */
    public <I extends Object, O extends Object> ProtocolSessionScenario.Draft<I, O> protocolSession(
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory) {
        return ProtocolSessionScenario.draft(runtime, adapterFactory);
    }
}
