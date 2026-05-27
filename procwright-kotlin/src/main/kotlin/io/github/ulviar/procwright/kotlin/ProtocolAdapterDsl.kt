package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.ProtocolReaders
import io.github.ulviar.procwright.session.ProtocolWriter

/** Protocol adapter builder scope used by [protocolAdapter]. */
@ProcwrightDsl
interface ProtocolAdapterDsl<I, O> {

    /** Sets the request writer handler. */
    fun writeRequest(
        handler: (request: I, writer: ProtocolWriter) -> Unit
    ): ProtocolAdapterDsl<I, O>

    /** Sets the response reader handler. */
    fun readResponse(handler: (readers: ProtocolReaders) -> O): ProtocolAdapterDsl<I, O>
}
