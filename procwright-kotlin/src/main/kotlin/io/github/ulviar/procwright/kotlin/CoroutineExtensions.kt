/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.RunScenario
import io.github.ulviar.procwright.command.CommandResult
import io.github.ulviar.procwright.session.LineResponse
import io.github.ulviar.procwright.session.LineSession
import io.github.ulviar.procwright.session.PooledLineSession
import io.github.ulviar.procwright.session.PooledProtocolSession
import io.github.ulviar.procwright.session.ProtocolSession
import io.github.ulviar.procwright.session.Session
import io.github.ulviar.procwright.session.SessionExit
import io.github.ulviar.procwright.session.StreamExit
import io.github.ulviar.procwright.session.StreamSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Executes this run draft without blocking the caller thread.
 *
 * Cancellation interrupts the core execution call, which applies the draft's shutdown policy to the
 * process owned by this execution.
 */
suspend fun RunScenario.Draft.executeAwait(): CommandResult = runProcwrightInterruptible {
    execute()
}

/**
 * Waits for an interactive session to exit without blocking the caller thread.
 *
 * Cancelling this wait cancels only the per-call future view returned by `onExit()`. It does not
 * cancel the session's shared exit state or close the session.
 */
suspend fun Session.awaitExit(): SessionExit = onExit().awaitDetached()

/**
 * Waits for a line session to exit without blocking the caller thread.
 *
 * Cancelling this wait cancels only the per-call future view returned by `onExit()`. It does not
 * cancel the session's shared exit state or close the session.
 */
suspend fun LineSession.awaitExit(): SessionExit = onExit().awaitDetached()

/**
 * Waits for a typed protocol session to exit without blocking the caller thread.
 *
 * Cancelling this wait cancels only the per-call future view returned by `onExit()`. It does not
 * cancel the session's shared exit state or close the session.
 */
suspend fun <I : Any, O : Any> ProtocolSession<I, O>.awaitExit(): SessionExit =
    onExit().awaitDetached()

/**
 * Waits for a streaming session to exit without blocking the caller thread.
 *
 * Cancelling this wait cancels only the per-call future view returned by `onExit()`. It does not
 * cancel the session's shared exit state or close the session.
 */
suspend fun StreamSession.awaitExit(): StreamExit = onExit().awaitDetached()

/**
 * Performs a line-session request without blocking the caller thread.
 *
 * Cancellation while waiting for the serialized request slot abandons only this call and leaves the
 * session reusable. Once stdin writing is admitted, cancellation closes the direct session because
 * a partially consumed request/response exchange cannot be reused safely.
 */
suspend fun LineSession.requestAwait(line: String): LineResponse = runProcwrightInterruptible {
    request(line)
}

/** Performs a cancellable line-session request with a Kotlin duration timeout. */
suspend fun LineSession.requestAwait(line: String, timeout: Duration): LineResponse =
    runProcwrightInterruptible {
        request(line, timeout.toJavaDuration())
    }

/**
 * Performs a typed protocol request without blocking the caller thread.
 *
 * Cancellation while waiting for the serialized request slot abandons only this call and leaves the
 * session reusable. Once that slot is acquired, cancellation closes the direct session because the
 * runtime can no longer prove that protocol processing did not begin.
 */
suspend fun <I : Any, O : Any> ProtocolSession<I, O>.requestAwait(request: I): O =
    runProcwrightInterruptible {
        request(request)
    }

/** Performs a cancellable typed protocol request with a Kotlin duration timeout. */
suspend fun <I : Any, O : Any> ProtocolSession<I, O>.requestAwait(
    request: I,
    timeout: Duration,
): O = runProcwrightInterruptible { request(request, timeout.toJavaDuration()) }

/**
 * Performs a pooled line request without blocking the caller thread.
 *
 * If cancellation interrupts acquisition, only that wait is abandoned. If it interrupts an active
 * exchange, core retires the leased worker before the pool serves another request with it.
 */
suspend fun PooledLineSession.requestAwait(line: String): LineResponse =
    runProcwrightInterruptible {
        request(line)
    }

/** Performs a cancellable pooled line request with a Kotlin duration timeout. */
suspend fun PooledLineSession.requestAwait(line: String, timeout: Duration): LineResponse =
    runProcwrightInterruptible {
        request(line, timeout.toJavaDuration())
    }

/**
 * Performs a pooled typed protocol request without blocking the caller thread.
 *
 * If cancellation interrupts acquisition, only that wait is abandoned. If it interrupts an active
 * exchange, core retires the leased worker before the pool serves another request with it.
 */
suspend fun <I : Any, O : Any> PooledProtocolSession<I, O>.requestAwait(request: I): O =
    runProcwrightInterruptible {
        request(request)
    }

/** Performs a cancellable pooled typed protocol request with a Kotlin duration timeout. */
suspend fun <I : Any, O : Any> PooledProtocolSession<I, O>.requestAwait(
    request: I,
    timeout: Duration,
): O = runProcwrightInterruptible { request(request, timeout.toJavaDuration()) }

@JvmSynthetic
internal suspend fun <T> runProcwrightInterruptible(block: () -> T): T {
    try {
        return runInterruptible(Dispatchers.IO, block)
    } catch (failure: RuntimeException) {
        currentCoroutineContext().ensureActive()
        throw failure
    }
}

@JvmSynthetic
internal suspend fun <T> CompletableFuture<T>.awaitDetached(): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(false) }
        whenComplete { value, failure ->
            if (failure == null) {
                continuation.resumeWith(Result.success(value))
            } else {
                continuation.resumeWithException(failure.unwrapCompletionFailure())
            }
        }
    }

@JvmSynthetic
internal fun Throwable.unwrapCompletionFailure(): Throwable =
    if (this is CompletionException && cause != null) cause!! else this
