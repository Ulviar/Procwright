package io.github.ulviar.icli.kotlin

import io.github.ulviar.icli.CommandService
import io.github.ulviar.icli.command.CommandInvocation
import io.github.ulviar.icli.command.CommandResult
import io.github.ulviar.icli.command.ShutdownPolicy
import io.github.ulviar.icli.session.LineResponse
import io.github.ulviar.icli.session.LineSession
import io.github.ulviar.icli.session.Session
import io.github.ulviar.icli.session.SessionExit
import io.github.ulviar.icli.session.StreamChunk
import io.github.ulviar.icli.session.StreamExit
import io.github.ulviar.icli.session.StreamSession
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Runs a one-shot command using a Kotlin receiver-style invocation builder. */
fun CommandService.runCommand(configure: CommandInvocation.Builder.() -> Unit = {}): CommandResult =
    run().configuredBy { builder -> builder.configure() }.execute()

/** Runs a one-shot command from a coroutine-friendly blocking boundary. */
suspend fun CommandService.runCommandAwait(
    configure: CommandInvocation.Builder.() -> Unit = {}
): CommandResult = withContext(Dispatchers.IO) { runCommand(configure) }

/** Opens an interactive session using a Kotlin receiver-style invocation builder. */
fun CommandService.openSession(
    configure: io.github.ulviar.icli.session.SessionInvocation.Builder.() -> Unit = {}
): Session = interactive().configuredBy { builder -> builder.configure() }.open()

/** Waits for a raw session to exit without blocking the caller coroutine. */
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

/** Waits for a streaming session to finish without blocking the caller coroutine. */
suspend fun StreamSession.awaitExit(): StreamExit = onExit().await()

/**
 * Receiver used by [CommandService.listenFlow].
 *
 * This adapter intentionally does not expose the low-level output listener setter. Flow owns chunk
 * delivery and preserves the core listen scenario backpressure semantics.
 */
interface ListenFlowInvocation {

    /** Adds one per-stream argument. */
    fun arg(argument: String): ListenFlowInvocation

    /** Adds per-stream arguments. */
    fun args(vararg arguments: String): ListenFlowInvocation

    /** Adds per-stream arguments. */
    fun args(arguments: Collection<String>): ListenFlowInvocation

    /** Sets the per-stream working directory. */
    fun workingDirectory(workingDirectory: Path): ListenFlowInvocation

    /** Adds or replaces one per-stream environment override. */
    fun putEnvironment(name: String, value: String): ListenFlowInvocation

    /** Sets the per-stream shutdown policy override. */
    fun shutdown(shutdownPolicy: ShutdownPolicy): ListenFlowInvocation

    /** Sets the per-stream absolute timeout override. */
    fun timeout(timeout: Duration): ListenFlowInvocation

    /** Keeps stdin open after the stream starts. */
    fun keepStdinOpen(): ListenFlowInvocation

    /** Closes stdin immediately after the stream starts. */
    fun closeStdinOnStart(): ListenFlowInvocation
}

/** Opens a listen-only streaming session as a cold Flow of output chunks. */
fun CommandService.listenFlow(configure: ListenFlowInvocation.() -> Unit = {}): Flow<StreamChunk> =
    callbackFlow {
            val session =
                listen()
                    .configuredBy { builder ->
                        DefaultListenFlowInvocation(builder).configure()
                        builder.onOutput { chunk -> runBlocking { send(chunk) } }
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

private class DefaultListenFlowInvocation(
    private val builder: io.github.ulviar.icli.session.StreamInvocation.Builder
) : ListenFlowInvocation {

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

private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, throwable ->
            if (!continuation.isActive) {
                return@whenComplete
            }
            if (throwable == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(throwable)
            }
        }
    }
