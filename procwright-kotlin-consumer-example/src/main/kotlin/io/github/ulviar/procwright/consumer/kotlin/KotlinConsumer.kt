/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.kotlin

import io.github.ulviar.procwright.Procwright
import io.github.ulviar.procwright.command.CommandSpec
import io.github.ulviar.procwright.kotlin.executeAwait
import io.github.ulviar.procwright.kotlin.openFlow
import io.github.ulviar.procwright.kotlin.withTimeout
import io.github.ulviar.procwright.session.StreamChunk
import io.github.ulviar.procwright.session.StreamSource
import io.github.ulviar.procwright.terminal.TerminalSize
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

internal object KotlinConsumer {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("--stream-worker"))) {
            println("collection:${UUID.randomUUID()}")
            return
        }

        val generatedEquals: (Any?) -> Boolean = TerminalSize.defaults()::equals
        check(!generatedEquals(null)) { "Generated record equals(null) must return false" }

        runBlocking {
            val version =
                Procwright.command(javaExecutable())
                    .run()
                    .withArgs("--version")
                    .withTimeout(5.seconds)
                    .executeAwait()
            check(version.succeeded()) { "The coroutine run scenario failed: $version" }

            val output = streamOutput()
            val firstCollection = output.toList().stdoutText()
            val secondCollection = output.toList().stdoutText()

            check(firstCollection.startsWith("collection:")) {
                "The first stream collection did not receive the worker output: $firstCollection"
            }
            check(secondCollection.startsWith("collection:")) {
                "The second stream collection did not receive the worker output: $secondCollection"
            }
            check(firstCollection != secondCollection) {
                "Collecting the cold Flow twice did not start independent processes"
            }
        }
    }

    private fun streamOutput(): Flow<StreamChunk> =
        Procwright.command(streamWorkerCommand())
            .listen()
            .withTimeout(5.seconds)
            .openFlow()

    private fun streamWorkerCommand(): CommandSpec =
        CommandSpec.of(javaExecutable())
            .withArgs(
                "-cp",
                System.getProperty("java.class.path"),
                KotlinConsumer::class.java.name,
                "--stream-worker",
            )

    private fun List<StreamChunk>.stdoutText(): String =
        asSequence()
            .filter { it.source() == StreamSource.STDOUT }
            .joinToString(separator = "") { it.text() }
            .trim()

    private fun javaExecutable(): String {
        val name =
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", name).toString()
    }
}
