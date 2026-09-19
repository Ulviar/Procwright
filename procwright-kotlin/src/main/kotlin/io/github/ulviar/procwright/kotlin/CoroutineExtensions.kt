/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.RunScenario
import io.github.ulviar.procwright.command.CommandResult
import io.github.ulviar.procwright.session.Expect
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
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible

/**
 * Executes this immutable run draft on [Dispatchers.IO] without blocking the caller thread.
 *
 * Each call starts its own execution. The draft's timeout and capture policies still apply. A
 * nonzero process exit or an execution timeout is represented by the returned [CommandResult], not
 * automatically thrown; inspect [CommandResult.succeeded] before using the output.
 *
 * Cancellation interrupts the core execution call, which applies the draft's shutdown policy to its
 * process. Cancellation does not promise instantaneous cleanup. See [RunScenario.Draft.execute] for
 * the core result and failure contract.
 *
 * @return completed command result, including exit status, captured output, and timeout information
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws io.github.ulviar.procwright.command.CommandExecutionException if launch, supervision,
 *   capture, or decoding fails
 */
suspend fun RunScenario.Draft.executeAwait(): CommandResult = runProcwrightInterruptible {
    execute()
}

/**
 * Waits for this interactive session's logical exit without blocking the caller thread.
 *
 * The completion boundary and exceptional outcomes are those of [Session.onExit]. A returned result
 * may describe a nonzero exit or timeout; successful waiting does not imply command success. The
 * wait does not add a timeout or take ownership of the session; the caller must still close it.
 *
 * Cancelling this wait cancels only the per-call future view returned by [Session.onExit]. It does
 * not cancel the shared exit state or close the process. Failures from that future are propagated.
 *
 * @return the [SessionExit] selected by the underlying handle
 * @throws CancellationException if the calling coroutine is cancelled
 */
suspend fun Session.awaitExit(): SessionExit = onExit().awaitDetached()

/**
 * Waits for this Expect process's logical exit without blocking the caller thread.
 *
 * The completion boundary and exceptional outcomes are those of [Expect.onExit]. A returned result
 * may describe a nonzero exit or timeout; successful waiting does not imply command success. The
 * wait does not add a timeout or take ownership of the handle; the caller must still close it.
 *
 * Cancelling this wait cancels only the per-call future view returned by [Expect.onExit]. It does
 * not cancel the shared exit state or close the process. Failures from that future are propagated.
 *
 * @return the [SessionExit] selected by the underlying handle
 * @throws CancellationException if the calling coroutine is cancelled
 */
suspend fun Expect.awaitExit(): SessionExit = onExit().awaitDetached()

/**
 * Waits for this line session's logical exit without blocking the caller thread.
 *
 * The completion boundary and exceptional outcomes are those of [LineSession.onExit]. A returned
 * result may describe a nonzero exit or timeout; successful waiting does not imply command success.
 * The wait does not add a timeout or take ownership of the session; the caller must still close it.
 *
 * Cancelling this wait cancels only the per-call future view returned by [LineSession.onExit]. It
 * does not cancel the shared exit state or close the process. Failures from that future are
 * propagated.
 *
 * @return the [SessionExit] selected by the underlying handle
 * @throws CancellationException if the calling coroutine is cancelled
 */
suspend fun LineSession.awaitExit(): SessionExit = onExit().awaitDetached()

/**
 * Waits for this typed protocol session's logical exit without blocking the caller thread.
 *
 * The completion boundary and exceptional outcomes are those of [ProtocolSession.onExit]. A
 * returned result may describe a nonzero exit or timeout; successful waiting does not imply command
 * success. The wait does not add a timeout or take ownership of the session; the caller must still
 * close it.
 *
 * Cancelling this wait cancels only the per-call future view returned by [ProtocolSession.onExit].
 * It does not cancel the shared exit state or close the process. Failures from that future are
 * propagated.
 *
 * @return the [SessionExit] selected by the underlying handle
 * @throws CancellationException if the calling coroutine is cancelled
 */
suspend fun <I : Any, O : Any> ProtocolSession<I, O>.awaitExit(): SessionExit =
    onExit().awaitDetached()

/**
 * Waits for this streaming session's logical exit without blocking the caller thread.
 *
 * The completion boundary and exceptional outcomes are those of [StreamSession.onExit]. A returned
 * result may describe a nonzero exit or timeout; successful waiting does not imply command success.
 * The wait does not add a timeout or take ownership of the session; the caller must still close it.
 *
 * Cancelling this wait cancels only the per-call future view returned by [StreamSession.onExit]. It
 * does not cancel the shared exit state or close the process. Failures from that future are
 * propagated.
 *
 * @return the [StreamExit] selected by the underlying handle
 * @throws CancellationException if the calling coroutine is cancelled
 */
suspend fun StreamSession.awaitExit(): StreamExit = onExit().awaitDetached()

/**
 * Performs a line-session request on [Dispatchers.IO] with the configured default request timeout.
 *
 * The request budget includes waiting for an earlier request and the request/response exchange.
 * Cancellation before stdin writing is admitted leaves the session reusable. Once writing is
 * admitted, cancellation closes the session, even if receipt of request bytes cannot be confirmed.
 *
 * Input validation, response semantics, and other failures follow [LineSession]. The caller still
 * owns the session and must close it.
 *
 * @param line request text without CR or LF; the session appends its line terminator
 * @return the response produced by the configured line decoder
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws IllegalArgumentException if line contains CR or LF
 * @throws io.github.ulviar.procwright.session.LineSessionException if the exchange cannot complete
 */
suspend fun LineSession.requestAwait(line: String): LineResponse = runProcwrightInterruptible {
    request(line)
}

/**
 * Performs a line-session request on [Dispatchers.IO] with the supplied request timeout.
 *
 * The request budget includes waiting for an earlier request and the request/response exchange.
 * Cancellation before stdin writing is admitted leaves the session reusable. Once writing is
 * admitted, cancellation closes the session, even if receipt of request bytes cannot be confirmed.
 *
 * Input validation, response semantics, and other failures follow [LineSession]. The caller still
 * owns the session and must close it.
 *
 * @param line request text without CR or LF; the session appends its line terminator
 * @param timeout positive request budget; zero and negative durations are rejected
 * @return the response produced by the configured line decoder
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws IllegalArgumentException if timeout is zero or negative, or line contains CR or LF
 * @throws io.github.ulviar.procwright.session.LineSessionException if the exchange cannot complete
 */
suspend fun LineSession.requestAwait(line: String, timeout: Duration): LineResponse =
    runProcwrightInterruptible {
        request(line, timeout.toJavaDuration())
    }

/**
 * Performs a typed protocol request on [Dispatchers.IO] with the configured default request
 * timeout.
 *
 * The request budget includes waiting for the serialized request slot and both adapter callbacks.
 * Cancellation while waiting for the slot leaves the session reusable. Once the slot is acquired,
 * cancellation closes the session even if no request bytes have yet been written.
 *
 * Input validation, response semantics, and other failures follow [ProtocolSession.request]. The
 * caller still owns the session and must close it.
 *
 * @param request non-null value passed to the worker adapter; do not mutate it during the call
 * @return the non-null response produced by the configured protocol adapter
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws io.github.ulviar.procwright.session.ProtocolSessionException if the exchange cannot
 *   complete
 */
suspend fun <I : Any, O : Any> ProtocolSession<I, O>.requestAwait(request: I): O =
    runProcwrightInterruptible {
        request(request)
    }

/**
 * Performs a typed protocol request on [Dispatchers.IO] with the supplied request timeout.
 *
 * The request budget includes waiting for the serialized request slot and both adapter callbacks.
 * Cancellation while waiting for the slot leaves the session reusable. Once the slot is acquired,
 * cancellation closes the session even if no request bytes have yet been written.
 *
 * Input validation, response semantics, and other failures follow [ProtocolSession.request]. The
 * caller still owns the session and must close it.
 *
 * @param request non-null value passed to the worker adapter; do not mutate it during the call
 * @param timeout positive request budget; zero and negative durations are rejected
 * @return the non-null response produced by the configured protocol adapter
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.ProtocolSessionException if the exchange cannot
 *   complete
 */
suspend fun <I : Any, O : Any> ProtocolSession<I, O>.requestAwait(
    request: I,
    timeout: Duration,
): O = runProcwrightInterruptible { request(request, timeout.toJavaDuration()) }

/**
 * Performs a pooled line request on [Dispatchers.IO] with the configured default request timeout.
 *
 * Worker acquisition has its own configured deadline; this request timeout does not replace it. The
 * pool may perform its reset hook after the response, so this timeout is not a deadline for
 * returning from the whole pooled call. Cancellation while acquiring a worker abandons only that
 * wait. Cancellation during an active exchange retires its worker before it can be reused.
 *
 * Input validation, response semantics, and other failures follow [PooledLineSession]. The caller
 * still owns the session pool and must close it.
 *
 * @param line request text without CR or LF; the session appends its line terminator
 * @return the response produced by the configured line decoder
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws IllegalArgumentException if line contains CR or LF
 * @throws io.github.ulviar.procwright.session.PooledSessionException if worker acquisition,
 *   startup, or health checking fails
 * @throws io.github.ulviar.procwright.session.LineSessionException if the worker exchange fails
 */
suspend fun PooledLineSession.requestAwait(line: String): LineResponse =
    runProcwrightInterruptible {
        request(line)
    }

/**
 * Performs a pooled line request on [Dispatchers.IO] with the supplied request timeout.
 *
 * Worker acquisition has its own configured deadline; this request timeout does not replace it. The
 * pool may perform its reset hook after the response, so this timeout is not a deadline for
 * returning from the whole pooled call. Cancellation while acquiring a worker abandons only that
 * wait. Cancellation during an active exchange retires its worker before it can be reused.
 *
 * Input validation, response semantics, and other failures follow [PooledLineSession]. The caller
 * still owns the session pool and must close it.
 *
 * @param line request text without CR or LF; the session appends its line terminator
 * @param timeout positive request budget; zero and negative durations are rejected
 * @return the response produced by the configured line decoder
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws IllegalArgumentException if timeout is zero or negative, or line contains CR or LF
 * @throws io.github.ulviar.procwright.session.PooledSessionException if worker acquisition,
 *   startup, or health checking fails
 * @throws io.github.ulviar.procwright.session.LineSessionException if the worker exchange fails
 */
suspend fun PooledLineSession.requestAwait(line: String, timeout: Duration): LineResponse =
    runProcwrightInterruptible {
        request(line, timeout.toJavaDuration())
    }

/**
 * Performs a pooled typed protocol request on [Dispatchers.IO] with the configured default request
 * timeout.
 *
 * Worker acquisition has its own configured deadline; this request timeout does not replace it. The
 * pool may perform its reset hook after the response, so this timeout is not a deadline for
 * returning from the whole pooled call. Cancellation while acquiring a worker abandons only that
 * wait. Cancellation during an active exchange retires its worker before it can be reused.
 *
 * Input validation, response semantics, and other failures follow [PooledProtocolSession.request].
 * The caller still owns the session pool and must close it.
 *
 * @param request non-null value passed to the worker adapter; do not mutate it during the call
 * @return the non-null response produced by the configured protocol adapter
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws io.github.ulviar.procwright.session.PooledSessionException if worker acquisition,
 *   startup, or health checking fails
 * @throws io.github.ulviar.procwright.session.ProtocolSessionException if the worker exchange fails
 */
suspend fun <I : Any, O : Any> PooledProtocolSession<I, O>.requestAwait(request: I): O =
    runProcwrightInterruptible {
        request(request)
    }

/**
 * Performs a pooled typed protocol request on [Dispatchers.IO] with the supplied request timeout.
 *
 * Worker acquisition has its own configured deadline; this request timeout does not replace it. The
 * pool may perform its reset hook after the response, so this timeout is not a deadline for
 * returning from the whole pooled call. Cancellation while acquiring a worker abandons only that
 * wait. Cancellation during an active exchange retires its worker before it can be reused.
 *
 * Input validation, response semantics, and other failures follow [PooledProtocolSession.request].
 * The caller still owns the session pool and must close it.
 *
 * @param request non-null value passed to the worker adapter; do not mutate it during the call
 * @param timeout positive request budget; zero and negative durations are rejected
 * @return the non-null response produced by the configured protocol adapter
 * @throws CancellationException if the calling coroutine is cancelled
 * @throws IllegalArgumentException if timeout is zero or negative
 * @throws io.github.ulviar.procwright.session.PooledSessionException if worker acquisition,
 *   startup, or health checking fails
 * @throws io.github.ulviar.procwright.session.ProtocolSessionException if the worker exchange fails
 */
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
internal suspend fun <T> CompletableFuture<T>.awaitDetached(): T {
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancelled: CancellationException) {
        cancel(false)
        throw cancelled
    }
    return await()
}
