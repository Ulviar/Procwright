/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.internal.ProcessKernel;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reusable scenario selector bound to an immutable {@link CommandSpec}.
 *
 * <p>Obtain a service with {@link Procwright#command(String)} or {@link Procwright#command(CommandSpec)}.
 * Selecting a scenario returns a fresh immutable draft and starts no process. Each draft's terminal operation
 * starts an independent process; a line or protocol session can then exchange multiple requests with that process.
 * Scenario defaults are documented on the returned draft type.
 *
 * <p>This service has no resources to close and can be shared between threads. Live handles and pools returned by
 * {@code open()} belong to the caller and must be closed, normally with try-with-resources.
 */
public final class CommandService {

    private final ScenarioRuntime runtime;

    CommandService(CommandSpec commandSpec, ProcessKernel processKernel) {
        runtime = new ScenarioRuntime(
                Objects.requireNonNull(commandSpec, "commandSpec"),
                Objects.requireNonNull(processKernel, "processKernel"));
    }

    /**
     * Selects finite execution with stdin handling, concurrent output capture, and timeout supervision.
     * Defaults include a 30-second timeout and 1 MiB of retained bytes per output stream; see {@link RunScenario.Draft}.
     *
     * @return a new run draft initialized with run defaults
     */
    public RunScenario.Draft run() {
        return RunScenario.draft(runtime);
    }

    /**
     * Selects the interactive scenario family.
     *
     * <p>The returned entry can open a raw interactive session or branch to prompt automation with
     * {@link InteractiveScenario.Entry#expect()} before a process is launched.
     *
     * @return a new interactive scenario entry
     */
    public InteractiveScenario.Entry interactive() {
        return InteractiveScenario.draft(runtime);
    }

    /**
     * Selects repeated line-oriented requests through one process. The child must already implement a
     * request/response conversation over stdin and stdout; this does not make an arbitrary one-shot CLI persistent.
     * The default response decoder consumes one stdout line. See {@link LineSessionScenario.Draft} for defaults.
     *
     * @return a new line-session draft
     */
    public LineSessionScenario.Draft lineSession() {
        return LineSessionScenario.draft(runtime);
    }

    /**
     * Selects live text output callbacks with stdin closed. Chunks need not be complete lines.
     * The default listener ignores output and the absolute timeout is disabled; see {@link StreamScenario.Draft}.
     *
     * @return a new listen-only stream draft
     */
    public StreamScenario.Draft listen() {
        return StreamScenario.draft(runtime);
    }

    /**
     * Selects typed request/response exchanges through a long-lived process.
     * The child must already speak the protocol implemented by the adapter. Each open invokes the factory before
     * launching its process; selecting or configuring a draft does not invoke it. See {@link ProtocolSessionScenario.Draft}
     * for defaults and {@link ProtocolAdapter} for callback lifetime, flushing, and failure rules.
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
