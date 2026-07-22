/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullness

import io.github.ulviar.procwright.session.ProtocolAdapter
import io.github.ulviar.procwright.session.ProtocolReaders
import io.github.ulviar.procwright.session.ProtocolWriter

class NullableProtocolResponse : ProtocolAdapter<String, String> {
    override fun writeRequest(request: String, writer: ProtocolWriter) {
        writer.writeLine(request)
    }

    override fun readResponse(readers: ProtocolReaders): String? = null
}
