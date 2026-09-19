/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.ProtocolAdapter
import io.github.ulviar.procwright.session.ProtocolReaders
import io.github.ulviar.procwright.session.ProtocolSessionException
import io.github.ulviar.procwright.session.ProtocolWriter

/**
 * Handlers for one adapter created by [protocolAdapterFactory].
 *
 * The factory can invoke the same configuration block concurrently for different sessions or pool
 * workers. Handler calls are serialized within the resulting adapter's session, but handlers on
 * different adapters can run concurrently. Mutable state captured outside the configuration block
 * remains shared and must be thread-safe.
 *
 * Both handlers are required. Setting the same handler again during configuration replaces its
 * previous value. A session runs [writeRequest] to completion before [readResponse]. The writer and
 * readers may be used only on the thread running the handler and only until that handler returns.
 * Do not retain them, move their I/O to another thread, or reuse them for a later request: such
 * access fails with [IllegalStateException] before process I/O. Starting asynchronous work inside a
 * handler does not extend this scope.
 *
 * A [RuntimeException] from a [writeRequest] handler is reported as [ProtocolSessionException]
 * reason [ProtocolSessionException.Reason.FAILURE]; one from a [readResponse] handler uses
 * [ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED]. A handler-thrown
 * [ProtocolSessionException] keeps its reason. These mappings apply when the handler failure wins
 * request arbitration; an already-selected terminal or fatal session outcome remains canonical.
 *
 * A handler-thrown [Error] remains fatal when it wins arbitration; otherwise it does not replace an
 * already-selected terminal or fatal session outcome. See [ProtocolAdapter] for the core contract.
 *
 * @param I non-null request type
 * @param O non-null response type
 */
interface ProtocolAdapterFactoryDsl<I : Any, O : Any> {

    /**
     * Sets the handler that frames and writes one request to process stdin.
     *
     * Write a complete request and call [ProtocolWriter.flush] before returning. Byte writes
     * preserve their bytes; text writes use the session charset. The writer enforces the request
     * deadline and byte limit. Its thread and lifetime restrictions are described by
     * [ProtocolAdapterFactoryDsl].
     *
     * @param handler synchronous callback receiving the request and its borrowed stdin writer
     */
    fun writeRequest(handler: (request: I, writer: ProtocolWriter) -> Unit)

    /**
     * Sets the handler that reads and decodes exactly one response after the request is written.
     *
     * Read from [ProtocolReaders.stdout] and, if the protocol requires it,
     * [ProtocolReaders.stderr]. Reader operations enforce the request deadline and response limits.
     * Their thread and lifetime restrictions are described by [ProtocolAdapterFactoryDsl]. Do not
     * consume a later response as part of the current one.
     *
     * @param handler synchronous callback receiving borrowed output readers and returning a
     *   non-null response
     */
    fun readResponse(handler: (readers: ProtocolReaders) -> O)
}
