/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.kotlin

import io.github.ulviar.procwright.Procwright
import io.github.ulviar.procwright.command.CommandSpec
import io.github.ulviar.procwright.kotlin.executeAwait
import io.github.ulviar.procwright.kotlin.openFlow
import io.github.ulviar.procwright.kotlin.protocolAdapterFactory
import io.github.ulviar.procwright.kotlin.requestAwait
import io.github.ulviar.procwright.kotlin.withTimeout
import io.github.ulviar.procwright.session.StreamSource
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--line-worker") {
        val input = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        val output = BufferedWriter(OutputStreamWriter(System.out, StandardCharsets.UTF_8))
        input.lineSequence().forEach {
            output.write("response:$it")
            output.write("\n")
            output.flush()
        }
        return
    }

    runBlocking {
        val version =
            Procwright.command(javaExecutable())
                .run()
                .withArgs("--version")
                .withTimeout(5.seconds)
                .executeAwait()
        if (!version.succeeded()) {
            throw version.toException()
        }

        val service = Procwright.command(lineWorkerCommand())
        val adapters =
            protocolAdapterFactory<String, String> {
                writeRequest { request, writer ->
                    writer.writeLine(request)
                    writer.flush()
                }
                readResponse { readers -> readers.stdout().readLine(4096) }
            }
        service.protocolSession(adapters).open().use { session ->
            check(session.requestAwait("hello", 5.seconds) == "response:hello")
            check(session.requestAwait("世界", 5.seconds) == "response:世界")
        }

        Procwright.command(javaExecutable())
            .listen()
            .withArgs("--version")
            .withTimeout(5.seconds)
            .openFlow()
            .collect { chunk ->
                when (chunk.source()) {
                    StreamSource.STDOUT -> print(chunk.text())
                    StreamSource.STDERR -> System.err.print(chunk.text())
                }
            }
    }
}

private fun lineWorkerCommand(): CommandSpec =
    CommandSpec.of(javaExecutable())
        .withArgs(
            "-cp",
            System.getProperty("java.class.path"),
            "io.github.ulviar.procwright.examples.kotlin.KotlinExampleKt",
            "--line-worker",
        )

private fun javaExecutable(): String {
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    return Path.of(System.getProperty("java.home"), "bin", name).toString()
}
