/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.StreamScenario
import io.github.ulviar.procwright.session.StreamChunk
import io.github.ulviar.procwright.session.StreamExit
import io.github.ulviar.procwright.session.StreamListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

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
 * Cleanup failures cannot replace an existing collection failure or cancellation. When collection
 * otherwise succeeds, a cleanup failure fails collection.
 */
fun StreamScenario.Draft.openFlow(): Flow<StreamChunk> = openFlow { listener, own ->
    val session = onOutput(listener).open()
    own(session::close)
    session.onExit()
}

@JvmSynthetic
internal fun StreamScenario.Draft.openFlow(launcher: StreamFlowLauncher): Flow<StreamChunk> =
    channelFlow {
        val ownedClose = AtomicReference<() -> Unit>()
        var primaryFailure: Throwable? = null
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
            exitView.awaitDetached()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                ownedClose.getAndSet(null)?.invoke()
            } catch (cleanupFailure: Throwable) {
                // Preserve the selected outcome without mutating a caller-owned Throwable.
                if (primaryFailure == null) {
                    currentCoroutineContext().ensureActive()
                    throw cleanupFailure
                }
            }
        }
    }
    .buffer(capacity = 0)
