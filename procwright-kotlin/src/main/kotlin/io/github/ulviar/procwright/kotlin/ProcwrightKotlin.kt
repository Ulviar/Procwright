/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.CommandService
import io.github.ulviar.procwright.LineSessionScenario
import io.github.ulviar.procwright.command.CharsetPolicy
import io.github.ulviar.procwright.command.CommandInvocation
import io.github.ulviar.procwright.command.CommandResult
import io.github.ulviar.procwright.command.ShutdownPolicy
import io.github.ulviar.procwright.session.LineResponse
import io.github.ulviar.procwright.session.LineSession
import io.github.ulviar.procwright.session.PooledLineSession
import io.github.ulviar.procwright.session.PooledProtocolSession
import io.github.ulviar.procwright.session.ProtocolAdapter
import io.github.ulviar.procwright.session.ProtocolReaders
import io.github.ulviar.procwright.session.ProtocolSession
import io.github.ulviar.procwright.session.ProtocolWriter
import io.github.ulviar.procwright.session.ResponseDecoder
import io.github.ulviar.procwright.session.Session
import io.github.ulviar.procwright.session.SessionExit
import io.github.ulviar.procwright.session.SessionInvocation
import io.github.ulviar.procwright.session.StreamChunk
import io.github.ulviar.procwright.session.StreamExit
import io.github.ulviar.procwright.session.StreamInvocation
import io.github.ulviar.procwright.session.StreamSession
import io.github.ulviar.procwright.terminal.TerminalPolicy
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration as KotlinDuration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** Runs a one-shot command using a Kotlin receiver-style invocation builder. */
fun CommandService.runCommand(configure: CommandInvocation.Builder.() -> Unit = {}): CommandResult =
    run().configuredBy { builder -> builder.configure() }.execute()

/** Runs a one-shot command from a coroutine-friendly blocking boundary. */
suspend fun CommandService.runCommandAwait(
    configure: CommandInvocation.Builder.() -> Unit = {}
): CommandResult = withContext(Dispatchers.IO) { runCommand(configure) }

/** Sets the command timeout from Kotlin's duration type. */
fun CommandInvocation.Builder.timeout(timeout: KotlinDuration): CommandInvocation.Builder =
    timeout(timeout.toJavaDuration())

/** Opens an interactive session using a Kotlin receiver-style invocation builder. */
fun CommandService.openSession(configure: SessionInvocation.Builder.() -> Unit = {}): Session =
    interactive().configuredBy { builder -> builder.configure() }.open()

/** Sets the raw session idle timeout from Kotlin's duration type. */
fun SessionInvocation.Builder.idleTimeout(timeout: KotlinDuration): SessionInvocation.Builder =
    idleTimeout(timeout.toJavaDuration())

/** Sets the raw session readiness timeout from Kotlin's duration type. */
fun SessionInvocation.Builder.readinessTimeout(timeout: KotlinDuration): SessionInvocation.Builder =
    readinessTimeout(timeout.toJavaDuration())

/**
 * Waits for a raw session to exit without blocking the caller coroutine.
 *
 * Cancelling the awaiting coroutine cancels only this call's exit-future view; the shared session
 * exit future and the session lifecycle are not affected.
 */
suspend fun Session.awaitExit(): SessionExit = onExit().await()

/** Sends one request to a line session without blocking the caller coroutine. */
suspend fun LineSession.requestAwait(line: String, timeout: Duration? = null): LineResponse =
    withContext(Dispatchers.IO) {
        if (timeout == null) {
            request(line)
        } else {
            request(line, timeout)
        }
    }

/** Sends one request to a line session using Kotlin's duration type. */
suspend fun LineSession.requestAwait(line: String, timeout: KotlinDuration): LineResponse =
    requestAwait(line, timeout.toJavaDuration())

/**
 * Sends one request to a typed protocol session without blocking the caller coroutine.
 *
 * Passing a `null` timeout uses the session default request timeout.
 */
suspend fun <I, O> ProtocolSession<I, O>.requestAwait(request: I, timeout: Duration? = null): O =
    withContext(Dispatchers.IO) {
        if (timeout == null) {
            request(request)
        } else {
            request(request, timeout)
        }
    }

/** Sends one request to a typed protocol session using Kotlin's duration type. */
suspend fun <I, O> ProtocolSession<I, O>.requestAwait(request: I, timeout: KotlinDuration): O =
    requestAwait(request, timeout.toJavaDuration())

/** Sends one pooled line-session request using Kotlin's duration type. */
fun PooledLineSession.request(line: String, timeout: KotlinDuration): LineResponse =
    request(line, timeout.toJavaDuration())

/**
 * Sends one pooled line-session request without blocking the caller coroutine.
 *
 * Passing a `null` timeout uses the worker line-session default request timeout.
 */
suspend fun PooledLineSession.requestAwait(line: String, timeout: Duration? = null): LineResponse =
    withContext(Dispatchers.IO) {
        if (timeout == null) {
            request(line)
        } else {
            request(line, timeout)
        }
    }

/** Sends one pooled line-session request using Kotlin's duration type. */
suspend fun PooledLineSession.requestAwait(line: String, timeout: KotlinDuration): LineResponse =
    requestAwait(line, timeout.toJavaDuration())

/**
 * Sends one pooled protocol-session request without blocking the caller coroutine.
 *
 * Passing a `null` timeout uses the worker protocol-session default request timeout.
 */
suspend fun <I, O> PooledProtocolSession<I, O>.requestAwait(
    request: I,
    timeout: Duration? = null,
): O =
    withContext(Dispatchers.IO) {
        if (timeout == null) {
            request(request)
        } else {
            request(request, timeout)
        }
    }

/** Sends one pooled protocol-session request using Kotlin's duration type. */
suspend fun <I, O> PooledProtocolSession<I, O>.requestAwait(
    request: I,
    timeout: KotlinDuration,
): O = requestAwait(request, timeout.toJavaDuration())

/** Waits for a pooled line-session pool to drain using Kotlin's duration type. */
fun PooledLineSession.awaitDrained(timeout: KotlinDuration): Boolean =
    awaitDrained(timeout.toJavaDuration())

/**
 * Waits for a streaming session to finish without blocking the caller coroutine.
 *
 * Cancelling the awaiting coroutine cancels only this call's exit-future view; the shared session
 * exit future and the session lifecycle are not affected.
 */
suspend fun StreamSession.awaitExit(): StreamExit = onExit().await()

/** Sets the stream timeout from Kotlin's duration type. */
fun StreamInvocation.Builder.timeout(timeout: KotlinDuration): StreamInvocation.Builder =
    timeout(timeout.toJavaDuration())

/** Sets the Flow stream timeout from Kotlin's duration type. */
fun ListenFlowInvocation.timeout(timeout: KotlinDuration): ListenFlowInvocation =
    timeout(timeout.toJavaDuration())

/**
 * Configures and opens a pooled line-session scenario with separate worker and pool scopes.
 *
 * The DSL still starts from the pooled line-session scenario. It does not expose worker leases or
 * create a second pooling runtime.
 */
fun CommandService.pooledLineSession(
    configure: PooledLineSessionDsl.() -> Unit = {}
): PooledLineSession {
    val builder = DefaultPooledLineSessionDsl(this)
    builder.configure()
    return builder.open()
}

/**
 * Builds a typed protocol adapter from Kotlin handlers.
 *
 * Both request writer and response reader handlers must be configured. The returned adapter uses
 * the core `ProtocolAdapter` runtime; it does not introduce another protocol engine.
 */
fun <I, O> protocolAdapter(configure: ProtocolAdapterDsl<I, O>.() -> Unit): ProtocolAdapter<I, O> {
    val builder = DefaultProtocolAdapterDsl<I, O>()
    builder.configure()
    return builder.build()
}

/** Opens a listen-only streaming session as a cold Flow of output chunks. */
fun CommandService.listenFlow(configure: ListenFlowInvocation.() -> Unit = {}): Flow<StreamChunk> =
    callbackFlow {
            val session =
                listen()
                    .configuredBy { builder ->
                        DefaultListenFlowInvocation(builder).configure()
                        builder.onOutput { chunk ->
                            // Blocks the pump thread while the buffer is full to preserve
                            // backpressure. A closed/failed result means the collector is gone;
                            // remaining chunks are dropped instead of failing the pump.
                            trySendBlocking(chunk)
                        }
                    }
                    .open()
            session.onExit().whenComplete { _, throwable ->
                if (throwable == null) {
                    close()
                } else {
                    close(throwable)
                }
            }
            awaitClose { session.close() }
        }
        .buffer(capacity = 0)

private class DefaultListenFlowInvocation(private val builder: StreamInvocation.Builder) :
    ListenFlowInvocation {

    override fun arg(argument: String): ListenFlowInvocation = apply { builder.arg(argument) }

    override fun args(vararg arguments: String): ListenFlowInvocation = apply {
        builder.args(*arguments)
    }

    override fun args(arguments: Collection<String>): ListenFlowInvocation = apply {
        builder.args(arguments)
    }

    override fun workingDirectory(workingDirectory: Path): ListenFlowInvocation = apply {
        builder.workingDirectory(workingDirectory)
    }

    override fun putEnvironment(name: String, value: String): ListenFlowInvocation = apply {
        builder.putEnvironment(name, value)
    }

    override fun shutdown(shutdownPolicy: ShutdownPolicy): ListenFlowInvocation = apply {
        builder.shutdown(shutdownPolicy)
    }

    override fun timeout(timeout: Duration): ListenFlowInvocation = apply {
        builder.timeout(timeout)
    }

    override fun keepStdinOpen(): ListenFlowInvocation = apply { builder.keepStdinOpen() }

    override fun closeStdinOnStart(): ListenFlowInvocation = apply { builder.closeStdinOnStart() }
}

private class DefaultPooledLineSessionDsl(private val service: CommandService) :
    PooledLineSessionDsl {

    private var workerScenario: LineSessionScenario = service.lineSession()
    private var poolScenario:
        ((LineSessionScenario) -> io.github.ulviar.procwright.PooledLineSessionScenario) =
        {
            it.pooled()
        }

    override fun worker(configure: LineWorkerDsl.() -> Unit): PooledLineSessionDsl = apply {
        DefaultLineWorkerDsl(workerScenario) { workerScenario = it }.configure()
    }

    override fun maxSize(maxSize: Int): PooledLineSessionDsl = apply {
        updatePool { it.withMaxSize(maxSize) }
    }

    override fun warmupSize(warmupSize: Int): PooledLineSessionDsl = apply {
        updatePool { it.withWarmupSize(warmupSize) }
    }

    override fun minIdle(minIdle: Int): PooledLineSessionDsl = apply {
        updatePool { it.withMinIdle(minIdle) }
    }

    override fun acquireTimeout(timeout: Duration): PooledLineSessionDsl = apply {
        updatePool { it.withAcquireTimeout(timeout) }
    }

    override fun acquireTimeout(timeout: KotlinDuration): PooledLineSessionDsl =
        acquireTimeout(timeout.toJavaDuration())

    override fun hookTimeout(timeout: Duration): PooledLineSessionDsl = apply {
        updatePool { it.withHookTimeout(timeout) }
    }

    override fun hookTimeout(timeout: KotlinDuration): PooledLineSessionDsl =
        hookTimeout(timeout.toJavaDuration())

    override fun maxRequestsPerWorker(maxRequestsPerWorker: Int): PooledLineSessionDsl = apply {
        updatePool { it.withMaxRequestsPerWorker(maxRequestsPerWorker) }
    }

    override fun maxWorkerAge(maxWorkerAge: Duration): PooledLineSessionDsl = apply {
        updatePool { it.withMaxWorkerAge(maxWorkerAge) }
    }

    override fun maxWorkerAge(maxWorkerAge: KotlinDuration): PooledLineSessionDsl =
        maxWorkerAge(maxWorkerAge.toJavaDuration())

    override fun backgroundReplenishment(enabled: Boolean): PooledLineSessionDsl = apply {
        updatePool { it.withBackgroundReplenishment(enabled) }
    }

    override fun reset(resetHook: (LineSession) -> Unit): PooledLineSessionDsl = apply {
        updatePool { it.withReset { session -> resetHook(session) } }
    }

    override fun healthCheck(healthCheck: (LineSession) -> Boolean): PooledLineSessionDsl = apply {
        updatePool { it.withHealthCheck { session -> healthCheck(session) } }
    }

    internal fun open(): PooledLineSession = poolScenario(workerScenario).open()

    private fun updatePool(
        step:
            (
                io.github.ulviar.procwright.PooledLineSessionScenario
            ) -> io.github.ulviar.procwright.PooledLineSessionScenario
    ) {
        val previous = poolScenario
        poolScenario = { worker -> step(previous(worker)) }
    }
}

private class DefaultLineWorkerDsl(
    initialScenario: LineSessionScenario,
    private val updateScenario: (LineSessionScenario) -> Unit,
) : LineWorkerDsl {

    private var scenario = initialScenario

    override fun arg(argument: String): LineWorkerDsl = update { it.withArg(argument) }

    override fun args(vararg arguments: String): LineWorkerDsl = update { it.withArgs(*arguments) }

    override fun args(arguments: Collection<String>): LineWorkerDsl = update {
        it.withArgs(arguments)
    }

    override fun workingDirectory(workingDirectory: Path): LineWorkerDsl = update {
        it.withWorkingDirectory(workingDirectory)
    }

    override fun putEnvironment(name: String, value: String): LineWorkerDsl = update {
        it.withEnvironment(name, value)
    }

    override fun inheritEnvironment(): LineWorkerDsl = update { it.withInheritedEnvironment() }

    override fun cleanEnvironment(): LineWorkerDsl = update { it.withCleanEnvironment() }

    override fun shutdown(shutdownPolicy: ShutdownPolicy): LineWorkerDsl = update {
        it.withShutdown(shutdownPolicy)
    }

    override fun idleTimeout(timeout: Duration): LineWorkerDsl = update {
        it.withIdleTimeout(timeout)
    }

    override fun idleTimeout(timeout: KotlinDuration): LineWorkerDsl =
        idleTimeout(timeout.toJavaDuration())

    override fun terminal(terminalPolicy: TerminalPolicy): LineWorkerDsl = update {
        it.withTerminal(terminalPolicy)
    }

    override fun readiness(readinessProbe: (LineSession) -> Unit): LineWorkerDsl = update {
        it.withReadiness { session -> readinessProbe(session) }
    }

    override fun readinessTimeout(timeout: Duration): LineWorkerDsl = update {
        it.withReadinessTimeout(timeout)
    }

    override fun readinessTimeout(timeout: KotlinDuration): LineWorkerDsl =
        readinessTimeout(timeout.toJavaDuration())

    override fun requestTimeout(timeout: Duration): LineWorkerDsl = update {
        it.withRequestTimeout(timeout)
    }

    override fun requestTimeout(timeout: KotlinDuration): LineWorkerDsl =
        requestTimeout(timeout.toJavaDuration())

    override fun transcriptLimit(limit: Int): LineWorkerDsl = update {
        it.withTranscriptLimit(limit)
    }

    override fun stdoutBacklogLimit(limit: Int): LineWorkerDsl = update {
        it.withStdoutBacklogLines(limit)
    }

    override fun maxLineChars(maxLineChars: Int): LineWorkerDsl = update {
        it.withMaxLineChars(maxLineChars)
    }

    override fun charset(charset: Charset): LineWorkerDsl = update { it.withCharset(charset) }

    override fun charsetPolicy(charsetPolicy: CharsetPolicy): LineWorkerDsl = update {
        it.withCharsetPolicy(charsetPolicy)
    }

    override fun responseDecoder(responseDecoder: ResponseDecoder): LineWorkerDsl = update {
        it.withResponseDecoder(responseDecoder)
    }

    private fun update(step: (LineSessionScenario) -> LineSessionScenario): LineWorkerDsl = apply {
        scenario = step(scenario)
        updateScenario(scenario)
    }
}

private class DefaultProtocolAdapterDsl<I, O> : ProtocolAdapterDsl<I, O> {

    private var writeRequestHandler: ((I, ProtocolWriter) -> Unit)? = null
    private var readResponseHandler: ((ProtocolReaders) -> O)? = null

    override fun writeRequest(
        handler: (request: I, writer: ProtocolWriter) -> Unit
    ): ProtocolAdapterDsl<I, O> = apply { writeRequestHandler = handler }

    override fun readResponse(handler: (readers: ProtocolReaders) -> O): ProtocolAdapterDsl<I, O> =
        apply {
            readResponseHandler = handler
        }

    internal fun build(): ProtocolAdapter<I, O> {
        val writer =
            writeRequestHandler
                ?: throw IllegalStateException("Protocol adapter DSL requires writeRequest")
        val reader =
            readResponseHandler
                ?: throw IllegalStateException("Protocol adapter DSL requires readResponse")

        return KotlinProtocolAdapter(writer, reader)
    }
}

private class KotlinProtocolAdapter<I, O>(
    private val writer: (I, ProtocolWriter) -> Unit,
    private val reader: (ProtocolReaders) -> O,
) : ProtocolAdapter<I, O> {

    override fun writeRequest(request: I, writerTarget: ProtocolWriter) {
        writer(request, writerTarget)
    }

    override fun readResponse(readers: ProtocolReaders): O = reader(readers)
}
