/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.ProtocolReaders
import io.github.ulviar.procwright.session.ProtocolWriter

/**
 * Configuration used to create one fresh protocol adapter.
 *
 * The factory can invoke the same configuration block concurrently for different sessions or pool
 * workers. Handler calls are serialized within the resulting adapter's session, but handlers on
 * different adapters can run concurrently. Mutable state captured outside the handler remains
 * shared and must be thread-safe.
 *
 * A [RuntimeException] from a `writeRequest` handler is reported as `ProtocolSessionException`
 * reason `FAILURE`; one from a `readResponse` handler is reported with reason
 * `PROTOCOL_DECODER_FAILED`. A handler-thrown `ProtocolSessionException` keeps its reason. These
 * mappings apply when the handler failure wins request arbitration; an already-selected terminal or
 * fatal session outcome remains canonical.
 *
 * A handler-thrown [Error] is rethrown as the same object. If a fatal [Error] was already selected,
 * that earlier object wins and the handler failure is attached as a suppressed exception. Fatal
 * errors preserve object identity.
 */
interface ProtocolAdapterFactoryDsl<I : Any, O : Any> {

    /**
     * Sets the handler retained by this fresh adapter to write one request.
     *
     * An untyped handler [RuntimeException] maps to `ProtocolSessionException` reason `FAILURE`.
     */
    fun writeRequest(handler: (request: I, writer: ProtocolWriter) -> Unit)

    /**
     * Sets the handler retained by this fresh adapter to read one response.
     *
     * An untyped handler [RuntimeException] maps to `ProtocolSessionException` reason
     * `PROTOCOL_DECODER_FAILED`.
     */
    fun readResponse(handler: (readers: ProtocolReaders) -> O)
}
