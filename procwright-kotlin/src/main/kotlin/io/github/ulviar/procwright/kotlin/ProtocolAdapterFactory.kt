/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.ProtocolAdapter
import io.github.ulviar.procwright.session.ProtocolReaders
import io.github.ulviar.procwright.session.ProtocolWriter
import java.util.function.Supplier

/**
 * Creates a factory for state-isolated protocol adapters.
 *
 * The configuration block runs on every [Supplier.get], not when this function is called. Pass the
 * supplier to [io.github.ulviar.procwright.CommandService.protocolSession] so each opened session
 * and pool worker receives a distinct adapter wrapper. Define both
 * [ProtocolAdapterFactoryDsl.writeRequest] and [ProtocolAdapterFactoryDsl.readResponse]. A missing
 * handler makes that supplier invocation throw [IllegalStateException] before process startup. An
 * exception from configuration propagates from that invocation; a later invocation evaluates
 * configuration again.
 *
 * Concurrent opens and pool worker startup may invoke the same configuration block concurrently, so
 * the block must be thread-safe. One session serializes the handlers on its adapter, but handlers
 * on different adapters can run concurrently. Keep mutable per-adapter state inside the block;
 * mutable state captured from outside remains shared and must be thread-safe.
 *
 * Handler I/O is synchronous and callback-scoped, as described by [ProtocolAdapterFactoryDsl]. The
 * factory supplies no framing: handlers must write and flush one complete request, then read
 * exactly one response using the child program's protocol.
 *
 * @param I non-null request type
 * @param O non-null response type
 * @param configure thread-safe block that installs both handlers and creates any per-adapter state
 * @return reusable factory; this call invokes no handlers and starts no process
 */
fun <I : Any, O : Any> protocolAdapterFactory(
    configure: ProtocolAdapterFactoryDsl<I, O>.() -> Unit
): Supplier<ProtocolAdapter<I, O>> = Supplier {
    DefaultProtocolAdapterFactoryDsl<I, O>().apply(configure).build()
}

private class DefaultProtocolAdapterFactoryDsl<I : Any, O : Any> : ProtocolAdapterFactoryDsl<I, O> {
    private var writer: ((I, ProtocolWriter) -> Unit)? = null
    private var reader: ((ProtocolReaders) -> O)? = null

    override fun writeRequest(handler: (request: I, writer: ProtocolWriter) -> Unit) {
        writer = handler
    }

    override fun readResponse(handler: (readers: ProtocolReaders) -> O) {
        reader = handler
    }

    fun build(): ProtocolAdapter<I, O> =
        KotlinProtocolAdapter(
            writer ?: error("Protocol adapter factory requires writeRequest"),
            reader ?: error("Protocol adapter factory requires readResponse"),
        )
}

private class KotlinProtocolAdapter<I : Any, O : Any>(
    private val writer: (I, ProtocolWriter) -> Unit,
    private val reader: (ProtocolReaders) -> O,
) : ProtocolAdapter<I, O> {
    override fun writeRequest(request: I, writerTarget: ProtocolWriter) {
        writer(request, writerTarget)
    }

    override fun readResponse(readers: ProtocolReaders): O = reader(readers)
}
