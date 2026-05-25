package io.github.ulviar.icli.kotlin

import io.github.ulviar.icli.CommandService
import io.github.ulviar.icli.session.StreamInvocation
import io.github.ulviar.icli.session.StreamSource
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class IcliKotlinTest {

    @Test
    fun `receiver style run command works`() {
        val result = javaService().runCommand { args("-version") }

        assertTrue(result.succeeded())
    }

    @Test
    fun `suspending run command works`() = runBlocking {
        val result = javaService().runCommandAwait { args("-version") }

        assertTrue(result.succeeded())
    }

    @Test
    fun `stream chunks can be collected as flow`() = runBlocking {
        val chunks = javaService().listenFlow { args("-version") }.toList()

        assertTrue(
            chunks.any { it.source() == StreamSource.STDERR || it.source() == StreamSource.STDOUT }
        )
    }

    @Test
    fun `listen flow exposes a scenario builder without listener override`() {
        assertTrue(ListenFlowInvocation::class.java.methods.none { it.name == "onOutput" })
        assertTrue(
            ListenFlowInvocation::class.java.constructors.none { constructor ->
                Modifier.isPublic(constructor.modifiers) &&
                    constructor.parameterTypes.contains(StreamInvocation.Builder::class.java)
            }
        )
    }

    @Test
    fun `slow flow collector receives complete output without silent drops`() = runBlocking {
        val output =
            javaService()
                .listenFlow { args(*fixtureArgs("many-lines", "200")) }
                .onEach { delay(1) }
                .toList()
                .joinToString(separator = "") { it.text() }

        val lines = output.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals((0 until 200).map { "line:$it" }, lines)
    }

    @Test
    fun `session exit can be awaited`() = runBlocking {
        javaService()
            .openSession { args("-version") }
            .use { session ->
                val exit = session.awaitExit()

                assertEquals(0, exit.exitCode().orElseThrow())
            }
    }

    @Test
    fun `line session request can be awaited`() = runBlocking {
        javaService()
            .lineSession { invocation -> invocation.args(*fixtureArgs("line-repl")) }
            .use { session ->
                val response = session.requestAwait("hello", Duration.ofSeconds(1))

                assertEquals(listOf("response:hello"), response.lines())
            }
    }

    @Test
    fun `stream session exit can be awaited`() = runBlocking {
        javaService()
            .listen { invocation -> invocation.args(*fixtureArgs("paired-streams")) }
            .use { session ->
                val exit = session.awaitExit()

                assertEquals(0, exit.exitCode().orElseThrow())
            }
    }

    @Test
    fun `cancelling session awaiter does not cancel shared exit future`() {
        runBlocking {
            javaService()
                .openSession { args(*fixtureArgs("sleep", "5000")) }
                .use { session ->
                    val job = async { session.awaitExit() }

                    delay(50)
                    job.cancelAndJoin()

                    assertFalse(session.onExit().isCancelled)
                    assertFalse(session.onExit().isDone)

                    session.close()
                    session.onExit().join()
                }
        }
    }

    @Test
    fun `cancelling stream awaiter does not cancel shared exit future`() {
        runBlocking {
            javaService()
                .listen { invocation -> invocation.args(*fixtureArgs("sleep", "5000")) }
                .use { session ->
                    val job = async { session.awaitExit() }

                    delay(50)
                    job.cancelAndJoin()

                    assertFalse(session.onExit().isCancelled)
                    assertFalse(session.onExit().isDone)

                    session.close()
                    session.onExit().join()
                }
        }
    }

    @Test
    fun `test methods keep junit compatible void return type`() {
        val testMethods =
            IcliKotlinTest::class.java.declaredMethods.filter { method ->
                method.isAnnotationPresent(org.junit.jupiter.api.Test::class.java)
            }

        assertEquals(11, testMethods.size)
        assertTrue(testMethods.all { it.returnType == Void.TYPE })
    }

    private fun javaService(): CommandService = CommandService.forCommand(javaExecutable())

    private fun javaExecutable(): String {
        val executableName =
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString()
    }

    private fun fixtureArgs(command: String, vararg arguments: String): Array<String> =
        arrayOf(
            "-cp",
            System.getProperty("java.class.path"),
            KotlinProcessFixture::class.java.name,
            command,
            *arguments,
        )
}

object KotlinProcessFixture {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "line-repl" -> lineRepl()
            "paired-streams" -> pairedStreams()
            "many-lines" -> manyLines(args.getOrNull(1)?.toInt() ?: 0)
            "sleep" -> Thread.sleep(args.getOrNull(1)?.toLong() ?: 0L)
            else -> error("Unknown fixture command: ${args.firstOrNull()}")
        }
    }

    private fun lineRepl() {
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            println("response:$line")
            System.out.flush()
        }
    }

    private fun pairedStreams() {
        println("out-start")
        System.err.println("err-start")
        System.out.flush()
        System.err.flush()
    }

    private fun manyLines(count: Int) {
        repeat(count) { index -> println("line:$index") }
        System.out.flush()
    }
}
