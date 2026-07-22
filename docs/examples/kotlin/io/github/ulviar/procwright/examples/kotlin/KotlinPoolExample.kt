/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.kotlin

import io.github.ulviar.procwright.Procwright
import io.github.ulviar.procwright.command.CommandSpec
import io.github.ulviar.procwright.kotlin.requestAwait
import io.github.ulviar.procwright.kotlin.withRequestTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
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
        Procwright.command(lineWorkerCommand())
            .lineSession()
            .withRequestTimeout(5.seconds)
            .pooled()
            .withMaxSize(2)
            .withWarmupSize(1)
            .open()
            .use { pool ->
                check(pool.requestAwait("Привет", 5.seconds).text() == "response:Привет")
            }
    }
}

private fun lineWorkerCommand(): CommandSpec =
    CommandSpec.of(javaExecutable())
        .withArgs(
            "-cp",
            System.getProperty("java.class.path"),
            "io.github.ulviar.procwright.examples.kotlin.KotlinPoolExampleKt",
            "--line-worker",
        )

private fun javaExecutable(): String {
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    return Path.of(System.getProperty("java.home"), "bin", name).toString()
}
