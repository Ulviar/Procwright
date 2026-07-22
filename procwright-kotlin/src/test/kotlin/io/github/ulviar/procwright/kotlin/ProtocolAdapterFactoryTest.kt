/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.ProtocolAdapter
import io.github.ulviar.procwright.session.ProtocolWriter
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ProtocolAdapterFactoryTest {

    @Test
    fun `factory validates both required handlers on each get`() {
        val missingWriter = protocolAdapterFactory<String, String> { readResponse { "unused" } }
        val missingReader = protocolAdapterFactory<String, String> { writeRequest { _, _ -> Unit } }

        assertFailsWith<IllegalStateException> { missingWriter.get() }
        assertFailsWith<IllegalStateException> { missingReader.get() }
    }

    @Test
    fun `configuration failure is propagated from get and retried on next get`() {
        val failure = IllegalArgumentException("configuration failed")
        val calls = AtomicInteger()
        val factory =
            protocolAdapterFactory<String, String> {
                calls.incrementAndGet()
                throw failure
            }

        repeat(2) {
            val observed = assertFailsWith<IllegalArgumentException> { factory.get() }
            assertTrue(observed === failure)
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun `concurrent get returns distinct wrappers with isolated captured state`() = runBlocking {
        val configurations = AtomicInteger()
        val factory =
            protocolAdapterFactory<String, String> {
                configurations.incrementAndGet()
                var requestNumber = 0
                writeRequest { request, writer ->
                    requestNumber++
                    writer.write("$request:$requestNumber")
                }
                readResponse { "unused" }
            }

        val adapters = List(100) { async(Dispatchers.Default) { factory.get() } }.awaitAll()
        val identities =
            Collections.newSetFromMap(IdentityHashMap<ProtocolAdapter<String, String>, Boolean>())
        identities.addAll(adapters)

        assertEquals(100, identities.size)
        assertEquals(100, configurations.get())
        adapters.forEach { adapter ->
            val writer = RecordingWriter()
            adapter.writeRequest("request", writer)
            assertEquals("request:1", writer.text())
        }
        val firstWriter = RecordingWriter()
        adapters.first().writeRequest("again", firstWriter)
        assertEquals("again:2", firstWriter.text())
        assertNotSame(adapters[0], adapters[1])
    }

    @Test
    fun `each pooled worker receives fresh adapter state`() = runBlocking {
        val arrivals = Files.createTempDirectory("procwright-kotlin-pool-arrivals-")
        val release = arrivals.resolve("release")
        val configurations = AtomicInteger()
        val factory =
            protocolAdapterFactory<String, String> {
                configurations.incrementAndGet()
                var responseNumber = 0
                writeRequest { request, writer ->
                    writer.writeLine(request)
                    writer.flush()
                }
                readResponse { readers ->
                    responseNumber++
                    "${readers.stdout().readLine(256)}#$responseNumber"
                }
            }
        val pool =
            javaService()
                .protocolSession(factory)
                .withArgs(
                    *fixtureArgs("barrier-line-repl", arrivals.toString(), release.toString())
                )
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(2)
                .withMinIdle(0)
                .withBackgroundReplenishment(false)
                .open()
        var requests: List<Deferred<String>> = emptyList()
        try {
            requests =
                listOf(async { pool.requestAwait("one") }, async { pool.requestAwait("two") })
            awaitCondition { pool.metrics().leased() == 2 && arrivalCount(arrivals, release) == 2L }

            Files.createFile(release)

            assertEquals(setOf("response:one#1", "response:two#1"), requests.awaitAll().toSet())
            assertEquals(2, configurations.get())
            assertEquals(2L, pool.metrics().created())
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        if (Files.notExists(release)) Files.createFile(release)
                        requests.forEach { it.cancel() }
                        requests.forEach { it.join() }
                    } finally {
                        pool.close()
                    }
                }
            } finally {
                Files.walk(arrivals).use { files ->
                    files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun arrivalCount(directory: java.nio.file.Path, release: java.nio.file.Path): Long =
        Files.list(directory).use { files -> files.filter { it != release }.count() }

    private class RecordingWriter : ProtocolWriter {
        private val text = StringBuilder()

        override fun write(bytes: ByteArray) {
            text.append(bytes.toString(Charsets.UTF_8))
        }

        override fun write(value: String) {
            text.append(value)
        }

        override fun writeLine(line: String) {
            text.append(line).append('\n')
        }

        override fun flush() = Unit

        fun text(): String = text.toString()
    }
}
