/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin.nullness

import io.github.ulviar.procwright.CommandService
import io.github.ulviar.procwright.Procwright
import io.github.ulviar.procwright.ProtocolSessionScenario
import io.github.ulviar.procwright.kotlin.protocolAdapterFactory
import io.github.ulviar.procwright.terminal.TerminalSize

fun validNullnessContracts(service: CommandService): ProtocolSessionScenario.Draft<String, String> {
    Procwright.command("echo").run().withArgs(*arrayOf("hello", "world"))
    val factory =
        protocolAdapterFactory<String, String> {
            writeRequest { request, writer -> writer.writeLine(request) }
            readResponse { readers -> readers.stdout().readLine(256) }
        }
    return service.protocolSession(factory)
}

fun generatedRecordEqualsAcceptsNull(): Boolean {
    val generatedEquals: (Any?) -> Boolean = TerminalSize.defaults()::equals
    return generatedEquals(null)
}
