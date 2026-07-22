/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.CommandService
import io.github.ulviar.procwright.Procwright
import io.github.ulviar.procwright.session.ProtocolAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Supplier
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

internal fun javaService(): CommandService = Procwright.command(javaExecutable())

internal fun lineAdapterFactory(): Supplier<ProtocolAdapter<String, String>> =
    protocolAdapterFactory {
        writeRequest { request, writer ->
            writer.writeLine(request)
            writer.flush()
        }
        readResponse { readers -> readers.stdout().readLine(256) }
    }

internal fun fixtureArgs(command: String, vararg arguments: String): Array<String> =
    arrayOf(
        "-cp",
        System.getProperty("java.class.path"),
        KotlinProcessFixture::class.java.name,
        command,
        *arguments,
    )

internal suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(5.seconds) { while (!condition()) yield() }
}

internal suspend fun awaitPid(pidFile: Path): Long {
    awaitCondition { Files.readString(pidFile).trim().toLongOrNull()?.let { it > 0 } == true }
    return Files.readString(pidFile).trim().toLong()
}

internal fun isAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

internal fun destroyForcibly(pid: Long) {
    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly)
}

private fun javaExecutable(): String {
    val executableName =
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    return Path.of(System.getProperty("java.home"), "bin", executableName).toString()
}

internal object KotlinProcessFixture {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "line-repl" -> lineRepl(null)
            "controlled-line-repl" -> lineRepl(Path.of(args[1]))
            "barrier-line-repl" -> barrierLineRepl(Path.of(args[1]), Path.of(args[2]))
            "many-lines" -> manyLines(args[1].toInt())
            "sleep" -> Thread.sleep(args[1].toLong())
            "pid-sleep" -> pidSleep(args[1].toLong())
            "pid-file-sleep" -> pidFileSleep(Path.of(args[1]), args[2].toLong())
            "record-start" -> recordStart(Path.of(args[1]), args[2])
            "expect-output" -> expectOutput()
            "wait-for-file" -> waitForFile(Path.of(args[1]))
            else -> error("Unknown fixture command: ${args.firstOrNull()}")
        }
    }

    private fun lineRepl(requestMarker: Path?) {
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "slow" && requestMarker != null) {
                Files.writeString(requestMarker, "received")
                Thread.sleep(30_000)
            }
            println("response:$line")
            System.out.flush()
        }
    }

    private fun barrierLineRepl(arrivals: Path, release: Path) {
        val reader = System.`in`.bufferedReader()
        val line = reader.readLine() ?: return
        Files.writeString(arrivals.resolve(ProcessHandle.current().pid().toString()), line)
        while (!Files.exists(release)) Thread.onSpinWait()
        println("response:$line")
        System.out.flush()
        while (true) {
            val next = reader.readLine() ?: return
            println("response:$next")
            System.out.flush()
        }
    }

    private fun manyLines(count: Int) {
        repeat(count) { println("line:$it") }
        System.out.flush()
    }

    private fun pidSleep(millis: Long) {
        println(ProcessHandle.current().pid())
        System.out.flush()
        Thread.sleep(millis)
    }

    private fun pidFileSleep(pidFile: Path, millis: Long) {
        Files.writeString(pidFile, ProcessHandle.current().pid().toString())
        Thread.sleep(millis)
    }

    private fun recordStart(marker: Path, output: String) {
        Files.writeString(marker, "started\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        println(output)
        System.out.flush()
    }

    private fun expectOutput() {
        println("text regex42 match regex99")
        System.out.flush()
        while (System.`in`.read() >= 0) {
            // Keep the process available until the test closes the expect helper.
        }
    }

    private fun waitForFile(release: Path) {
        while (!Files.exists(release)) Thread.onSpinWait()
    }
}
