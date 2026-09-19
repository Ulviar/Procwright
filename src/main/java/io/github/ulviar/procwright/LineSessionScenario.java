/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Configuration API for persistent line-oriented workers and their pools.
 *
 * <p>Obtain a {@link Draft} from {@link CommandService#lineSession()}. Open one worker or use
 * {@link Draft#pooled()} to configure several interchangeable workers.
 */
public final class LineSessionScenario {

    private LineSessionScenario() {}

    static Draft draft(ScenarioRuntime runtime) {
        return LineSessionDrafts.create(runtime);
    }

    /**
     * Immutable configuration for line-oriented workers, obtained from {@link CommandService#lineSession()}.
     *
     * <p>Each {@code with*} call returns a new draft without changing this one or starting a process. Launch settings
     * begin with the service's command specification. Defaults are a five-second request timeout, UTF-8 replacement
     * decoding, and {@link ResponseDecoder#firstLine()} responses. Idle timeout and terminal transport are disabled;
     * there is no readiness probe. Request, response, and transcript limits are documented on their setters.
     *
     * <p>The Draft retains its PTY provider, readiness probe, response decoder, diagnostic listener, and transcript
     * sink. One opened worker serializes requests and response-decoder calls. Concurrent {@link #open()} calls, or
     * workers created through {@link #pooled()}, can still invoke the same retained instances concurrently. Retained
     * instances must be thread-safe; otherwise, use separate Draft branches with separate callback or provider
     * instances.
     */
    public interface Draft {
        /**
         * Appends one argument after the base command's arguments. The string is passed as one argv element;
         * spaces, quotes, wildcard characters, and shell operators are not interpreted.
         *
         * @param argument argument to append; may be empty, but must not contain NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if the argument contains NUL or the base command is a shell command
         */
        Draft withArg(String argument);

        /**
         * Appends arguments in array order, after copying the array. Each element is passed literally as one
         * argv element. An empty array adds nothing.
         *
         * @param arguments arguments to append; elements must be non-null and contain no NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if an argument contains NUL, or nonempty arguments are appended to a shell command
         */
        Draft withArgs(String... arguments);

        /**
         * Appends arguments in iteration order, after copying the collection. Each element is passed literally
         * as one argv element. An empty collection adds nothing.
         *
         * @param arguments arguments to append; elements must be non-null and contain no NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if an argument contains NUL, or nonempty arguments are appended to a shell command
         */
        Draft withArgs(Collection<String> arguments);

        /**
         * Sets the child process's working directory. This does not change the application's working directory;
         * the operating system validates the directory when the process starts.
         *
         * @param workingDirectory child working directory; otherwise inherited from the base command
         * @return updated immutable draft
         */
        Draft withWorkingDirectory(Path workingDirectory);

        /**
         * Adds or replaces a child environment entry. Explicit entries are applied after the selected inherited
         * or clean environment, independently of the order of configuration calls.
         *
         * @param name nonblank variable name containing neither NUL nor {@code =}
         * @param value variable value; may be empty, but must not contain NUL
         * @return updated immutable draft
         * @throws IllegalArgumentException if the name or value violates these constraints
         */
        Draft withEnvironment(String name, String value);

        /**
         * Inherits the parent environment and then applies all explicitly configured entries.
         * This is the default for a new command specification.
         *
         * @return updated immutable draft
         */
        Draft withInheritedEnvironment();

        /**
         * Starts from an empty child environment and then applies all explicitly configured entries.
         * This does not remove entries already configured on the command or draft.
         *
         * @return updated immutable draft
         */
        Draft withCleanEnvironment();

        /**
         * Sets the graceful and forceful shutdown waits. The default waits up to two seconds after requesting
         * graceful termination, then up to five seconds after requesting forceful termination.
         * Shutdown can extend the time taken to return from an operation whose own timeout has elapsed.
         *
         * @param shutdownPolicy shutdown escalation and wait budgets
         * @return updated immutable draft
         */
        Draft withShutdown(ShutdownPolicy shutdownPolicy);

        /**
         * Sets the inactivity timeout for the underlying process session. Zero disables it, which is the default.
         * Successful reads and writes through the session's streams refresh activity; this is not a total process
         * lifetime or request deadline. Expiry starts process shutdown.
         *
         * @param idleTimeout non-negative inactivity timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code idleTimeout} is negative
         */
        Draft withIdleTimeout(Duration idleTimeout);

        /**
         * Selects pipe transport or a pseudo-terminal. The default is {@link TerminalPolicy#DISABLED}.
         * {@link TerminalPolicy#AUTO} permits pipe fallback when the provider is unavailable;
         * {@link TerminalPolicy#REQUIRED} fails the open instead. See {@link PtyProvider} for platform requirements.
         *
         * @param terminalPolicy terminal requirement
         * @return updated immutable draft
         */
        Draft withTerminal(TerminalPolicy terminalPolicy);

        /**
         * Sets the provider consulted for {@link TerminalPolicy#AUTO} or {@link TerminalPolicy#REQUIRED}.
         * The default is {@link PtyProvider#system()}. Setting a provider does not itself enable terminal transport.
         * A shared provider must support concurrent opens and follow the timing contract of {@link PtyProvider}.
         *
         * @param ptyProvider retained terminal provider
         * @return updated immutable draft
         */
        Draft withPtyProvider(PtyProvider ptyProvider);

        /**
         * Sets the dimensions requested when a pseudo-terminal is opened. The default is 80 columns by 24 rows.
         * This is an initial size request, not a resize operation on an existing session; pipe transport ignores it.
         *
         * @param terminalSize requested terminal dimensions
         * @return updated immutable draft
         */
        Draft withTerminalSize(TerminalSize terminalSize);

        /**
         * Sets the probe that must succeed before {@link #open()} returns. No probe is configured by default.
         * The probe receives the new handle and may perform its startup conversation. Its I/O consumes real output;
         * use the handle's normal protocol operations. Failure closes the new process. Configure its wait with
         * {@link #withReadinessTimeout(Duration)}.
         *
         * <p>The retained probe runs once per session or pool worker on a task thread. Concurrent launches may
         * invoke it concurrently; state shared between invocations must be thread-safe.
         *
         * @param readinessProbe probe operating on the newly opened handle
         * @return updated immutable draft
         */
        Draft withReadiness(Consumer<LineSession> readinessProbe);

        /**
         * Sets the wait for a configured readiness probe. The default is five seconds.
         * This setting has no effect without a probe and does not bound process launch itself. Timeout closes the
         * new session; a probe that ignores interruption may continue after the open fails.
         *
         * @param readinessTimeout strictly positive probe timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code readinessTimeout} is zero or negative
         */
        Draft withReadinessTimeout(Duration readinessTimeout);

        /**
         * Sets the default timeout for one request/response exchange; the default is five seconds.
         * A direct request includes time waiting for that session's serialized request slot. Pool acquisition and
         * reset have separate budgets, so a pooled call can take longer. See the session's request contract for
         * which failures leave it reusable.
         *
         * @param requestTimeout strictly positive request timeout
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code requestTimeout} is zero or negative
         */
        Draft withRequestTimeout(Duration requestTimeout);

        /**
         * Bounds the retained diagnostic tail shared by stdout and stderr, including labels.
         * The default is 65,536 UTF-16 code units. Older diagnostic text is evicted when full. This does not
         * control how much unread output the protocol can consume; use the response limits for that.
         *
         * @param transcriptLimit positive number of UTF-16 code units
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code transcriptLimit} is not positive
         */
        Draft withTranscriptLimit(int transcriptLimit);

        /**
         * Sets the encoded byte limit for a request, including the appended line feed. The default is 1 MiB.
         * The request character limit is checked independently before encoding.
         *
         * @param maxRequestBytes positive byte limit
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code maxRequestBytes} is not positive
         */
        Draft withMaxRequestBytes(int maxRequestBytes);

        /**
         * Sets the request text limit before encoding, excluding the appended line feed. The default is
         * 1,048,576 UTF-16 code units. The encoded byte limit applies independently.
         *
         * @param maxRequestChars positive number of UTF-16 code units
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code maxRequestChars} is not positive
         */
        Draft withMaxRequestChars(int maxRequestChars);

        /**
         * Bounds the number of stdout lines consumed for one response; the default is 1,024.
         * The same value bounds queued unread stdout lines. Exceeding either limit fails the session, including
         * excess unsolicited output produced before a request. The decoder determines when the response ends.
         *
         * @param maxResponseLines positive response and pending-output line limit
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code maxResponseLines} is not positive
         */
        Draft withMaxResponseLines(int maxResponseLines);

        /**
         * Bounds total text consumed through the response decoder's reader, excluding LF/CRLF separators. The default
         * is 1,048,576 UTF-16 code units. The same value bounds unread stdout text and each partial line, so
         * excess unsolicited output can fail the session even outside a request.
         *
         * @param maxResponseChars positive number of UTF-16 code units
         * @return updated immutable draft
         * @throws IllegalArgumentException if {@code maxResponseChars} is not positive
         */
        Draft withMaxResponseChars(int maxResponseChars);

        /**
         * Selects the charset for request encoding and replacement decoding of stdout and stderr.
         * The default is UTF-8. Requests append a line feed encoded in the same charset.
         *
         * @param charset line protocol charset
         * @return updated immutable draft
         */
        Draft withCharset(Charset charset);

        /**
         * Sets the charset for text encoding and the malformed/unmappable-input action for response decoding.
         * The default replaces malformed UTF-8 output. The reporting action applies to decoding, not request
         * encoding; unsupported input characters are replaced during text encoding.
         *
         * @param charsetPolicy response decoding policy and text charset
         * @return updated immutable draft
         */
        Draft withCharsetPolicy(CharsetPolicy charsetPolicy);

        /**
         * Sets how stdout lines form one response. The default is {@link ResponseDecoder#firstLine()}.
         * A custom decoder may consume multiple lines, subject to the request deadline and response limits.
         * Its reader is valid only on the callback thread until that callback returns; see {@link ResponseDecoder}.
         *
         * <p>The retained decoder is serialized within one worker, but different sessions and pooled workers
         * may invoke it concurrently. Keep per-response state local to the invocation.
         *
         * @param responseDecoder retained response decoder
         * @return updated immutable draft
         */
        Draft withResponseDecoder(ResponseDecoder responseDecoder);

        /**
         * Observes structured lifecycle events through an asynchronous, best-effort listener.
         * Events contain command metadata, not stdout or stderr. Delivery need not finish before the operation returns.
         * See {@link DiagnosticListener} for concurrency and failure isolation.
         *
         * @param listener retained event listener; the default ignores events
         * @return updated immutable draft
         */
        Draft withDiagnosticListener(DiagnosticListener listener);

        /**
         * Records structured lifecycle events through an asynchronous, best-effort sink.
         * The sink receives {@link io.github.ulviar.procwright.diagnostics.DiagnosticEvent} values, not captured process
         * output. Delivery is independent of the diagnostic listener and need not finish before the operation returns.
         *
         * @param transcriptSink retained event sink; the default ignores events
         * @return updated immutable draft
         */
        Draft withDiagnosticTranscriptSink(DiagnosticTranscriptSink transcriptSink);

        /**
         * Branches into unopened pool configuration.
         *
         * @return pool draft carrying this worker configuration
         */
        PoolDraft pooled();

        /**
         * Starts an independent process and returns its handle after the configured readiness probe succeeds.
         * Procwright owns output reading; do not attach another reader to the child streams. The caller must close the handle, preferably with try-with-resources.
         *
         * <p>Readiness runs once on a task thread. Failure or timeout closes the newly opened process before the
         * failure is reported; a probe that ignores interruption can outlive the failed open. The readiness
         * timeout does not bound process launch. See {@link LineSession} for operation and close semantics.
         *
         * @return newly opened handle owned by the caller
         * @throws io.github.ulviar.procwright.command.CommandExecutionException if launch or setup fails, or readiness
         *     fails ({@code READINESS_FAILED}) or times out ({@code READINESS_TIMEOUT})
         */
        LineSession open();
    }

    /**
     * Immutable pool configuration carrying the worker settings selected before {@link Draft#pooled()}.
     *
     * <p>Configuration starts no processes. Each {@code with*} call returns a new draft and each {@link #open()}
     * creates an independent pool. Defaults are maximum size 1, no warmup or minimum idle workers, five-second
     * acquire and hook timeouts, 15-second close timeout, {@link Integer#MAX_VALUE} requests per worker, no age
     * retirement, and no user health or reset hook.
     *
     * <p>The PoolDraft retains the worker callbacks carried by {@link Draft#pooled()} and its health and reset callbacks.
     * One worker runs health, request, and reset work without overlap, but different workers or pools opened from this
     * PoolDraft can invoke the same retained callback instances concurrently. Retained instances must be thread-safe;
     * otherwise, use separate Draft or PoolDraft branches with separate callback instances.
     *
     * <p>A pool's configured maximum accepts values from 1 through 256 and defaults to 1. It counts every starting, idle,
     * leased, and retiring worker in that pool. Separate pools and directly opened sessions do not share a worker quota;
     * applications control their aggregate process count through the pools and sessions they create.
     */
    public interface PoolDraft {
        /**
         * Sets this pool's maximum occupied worker slots. Starting, idle, leased, and retiring workers all count.
         * The default is one. The upper bound of 256 is checked by {@link #open()}; configure size before warmup
         * or minimum idle as needed. Other pools and direct sessions have independent capacity.
         *
         * @param maxSize per-pool worker limit from 1 through 256
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code maxSize} is not positive
         */
        PoolDraft withMaxSize(int maxSize);

        /**
         * Sets how many workers must start successfully before {@link #open()} returns. The default is zero.
         * This is one-time startup, not a maintained idle floor. The value must not exceed maximum size when
         * opened; a partial warmup failure closes workers already started by that open.
         *
         * @param warmupSize non-negative number of workers to start synchronously
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code warmupSize} is negative
         */
        PoolDraft withWarmupSize(int warmupSize);

        /**
         * Sets the maintained idle-worker floor. Zero disables background replenishment, which is the default.
         * A positive value starts establishing the floor asynchronously when the pool opens and restores it as
         * workers are acquired or retired, without exceeding maximum size. Open does not wait for this floor;
         * use {@link #withWarmupSize(int)} for synchronous startup. The floor must not exceed maximum size at open.
         *
         * @param minIdle non-negative target idle-worker count
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code minIdle} is negative
         */
        PoolDraft withMinIdle(int minIdle);

        /**
         * Bounds waiting for an eligible worker, including startup, readiness, and health checks during acquisition.
         * The default is five seconds. This is separate from the worker request timeout and the reset-hook budget.
         * A health check is capped by both the remaining acquisition time and the hook timeout.
         *
         * @param acquireTimeout strictly positive acquisition timeout
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code acquireTimeout} is zero or negative
         */
        PoolDraft withAcquireTimeout(Duration acquireTimeout);

        /**
         * Bounds the wait for each configured health or reset callback. The default is five seconds.
         * A health check is additionally capped by the acquisition deadline. Reset has its own budget after a
         * successful request. Expiry retires the worker; Java code that ignores interruption may continue running.
         *
         * @param hookTimeout strictly positive callback timeout
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code hookTimeout} is zero or negative
         */
        PoolDraft withHookTimeout(Duration hookTimeout);

        /**
         * Bounds the caller's wait in {@link PooledLineSession#close()} for active requests and worker shutdown.
         * The default is 15 seconds. Expiry does not abandon cleanup or cancel a healthy active request;
         * {@link PooledLineSession#closeAsync()} observes eventual logical completion. Potentially blocking physical
         * stream close is outside that completion boundary.
         *
         * @param closeTimeout strictly positive close wait
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code closeTimeout} is zero or negative
         */
        PoolDraft withCloseTimeout(Duration closeTimeout);

        /**
         * Sets the request-count retirement threshold; the default is {@link Integer#MAX_VALUE}.
         * The worker is retired after reaching the threshold instead of being returned for another request.
         * This is a worker reuse limit, not a limit on requests accepted by the pool.
         *
         * @param maxRequestsPerWorker positive request limit per worker
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code maxRequestsPerWorker} is not positive
         */
        PoolDraft withMaxRequestsPerWorker(int maxRequestsPerWorker);

        /**
         * Sets the age after which a worker is no longer reused. Zero disables age retirement, which is the default.
         * Age retirement does not interrupt a healthy request in progress; the worker is checked when considered
         * for reuse. This is independent of the worker's idle and request timeouts.
         *
         * @param maxWorkerAge non-negative worker age limit
         * @return updated immutable pool draft
         * @throws IllegalArgumentException if {@code maxWorkerAge} is negative
         */
        PoolDraft withMaxWorkerAge(Duration maxWorkerAge);

        /**
         * Sets the callback run after a successful request before a worker can return to idle. No reset is
         * configured by default. An ordinary reset failure or timeout retires the worker but preserves the
         * successful response; it does not ask the caller to repeat an already completed request.
         *
         * <p>The retained hook runs on a task thread within the hook timeout. It may use the worker's request API
         * but must not retain or close the worker. Health, user request, and reset work do not overlap within
         * one worker; different workers and pools can invoke the same hook concurrently.
         *
         * @param resetHook retained worker-reset callback
         * @return updated immutable pool draft
         */
        PoolDraft withReset(Consumer<LineSession> resetHook);

        /**
         * Sets a predicate evaluated during acquisition before a worker serves a request. Without one, the pool
         * checks whether the worker is still alive. A false result retires the worker and lets acquisition try
         * another within its remaining budget. A callback exception or timeout retires the worker and fails
         * the current pooled call.
         *
         * <p>The retained predicate runs on a task thread and may use the worker's request API, but must not
         * retain or close the worker. It does not overlap with request or reset work on that worker. Different
         * workers and pools can invoke the same predicate concurrently.
         *
         * @param healthCheck retained predicate returning true when the worker may serve the request
         * @return updated immutable pool draft
         */
        PoolDraft withHealthCheck(Predicate<LineSession> healthCheck);

        /**
         * Opens an independently owned pool and completes its configured synchronous warmup.
         * With no warmup, workers start on demand or through minimum-idle replenishment. The caller must close
         * the pool, preferably with try-with-resources; see {@link PooledLineSession#close()} for drain and timeout rules.
         *
         * @return newly opened pool owned by the caller
         * @throws IllegalArgumentException if maximum size exceeds 256, or warmup or minimum idle exceeds maximum size
         * @throws PooledSessionException with reason {@link PooledSessionException.Reason#STARTUP_FAILED} if
         *     synchronous warmup fails
         */
        PooledLineSession open();
    }
}
