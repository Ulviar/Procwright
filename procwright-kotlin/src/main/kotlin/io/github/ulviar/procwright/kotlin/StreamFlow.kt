/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.StreamScenario
import io.github.ulviar.procwright.session.StreamChunk
import io.github.ulviar.procwright.session.StreamExit
import io.github.ulviar.procwright.session.StreamListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

private typealias CloseRegistrar = (close: () -> Unit) -> Unit

private typealias StreamFlowLauncher =
    (listener: StreamListener, own: CloseRegistrar) -> CompletableFuture<StreamExit>

/**
 * Returns a cold stream of process output for this draft.
 *
 * No process is started until collection. Every collection, including concurrent collections, opens
 * and owns a fresh stream session. Delivery uses a rendezvous channel, so an active slow collector
 * applies backpressure instead of losing chunks. Cancelling a collector closes only its session.
 *
 * This terminal owns the draft's output listener: it replaces any listener previously set with
 * `onOutput`. It emits chunks only and discards [io.github.ulviar.procwright.session.StreamExit]
 * metadata on normal completion. Use `open()` when another listener or exit metadata is required.
 */
fun StreamScenario.Draft.openFlow(): Flow<StreamChunk> = openFlow { listener, own ->
    val session = onOutput(listener).open()
    own(session::close)
    session.onExit()
}

@JvmSynthetic
internal fun StreamScenario.Draft.openFlow(launcher: StreamFlowLauncher): Flow<StreamChunk> =
    callbackFlow {
            val ownedClose = AtomicReference<() -> Unit>()
            val ownedExitView = AtomicReference<CompletableFuture<*>>()
            try {
                val exitView = runProcwrightInterruptible {
                    launcher(
                        { chunk -> trySendBlocking(chunk) },
                        { close ->
                            check(ownedClose.compareAndSet(null, close)) {
                                "Stream Flow launcher registered ownership more than once"
                            }
                        },
                    )
                }
                ownedExitView.set(exitView)
                exitView.whenComplete { _, failure ->
                    if (failure == null) close() else close(failure.unwrapCompletionFailure())
                }
                awaitClose {
                    ownedExitView.getAndSet(null)?.cancel(false)
                    ownedClose.getAndSet(null)?.invoke()
                }
            } catch (failure: Throwable) {
                ownedExitView.getAndSet(null)?.cancel(false)
                ownedClose.getAndSet(null)?.invoke()
                throw failure
            }
        }
        .buffer(capacity = 0)
