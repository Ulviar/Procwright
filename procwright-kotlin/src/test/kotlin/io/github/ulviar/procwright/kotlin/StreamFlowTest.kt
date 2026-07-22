/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.session.StreamChunk
import io.github.ulviar.procwright.session.StreamExit
import io.github.ulviar.procwright.session.StreamListener
import io.github.ulviar.procwright.session.StreamSource
import io.github.ulviar.procwright.session.StreamTranscript
import java.nio.file.Files
import java.time.Duration as JavaDuration
import java.util.OptionalInt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

class StreamFlowTest {

    @Test
    fun `flow is cold and every sequential or concurrent collection gets independent ownership`() =
        runBlocking {
            val harness = FlowHarness()
            val flow = javaService().listen().openFlow(harness::launch)
            assertEquals(0, harness.opens.size)

            val first = async { flow.toList() }
            awaitCondition { harness.opens.size == 1 }
            harness.opens[0].listener.onChunk(chunk("first"))
            harness.opens[0].exit.complete(exit(code = 11))
            assertEquals(listOf(chunk("first")), first.await())

            val concurrent = List(2) { async { flow.toList() } }
            awaitCondition { harness.opens.size == 3 }
            harness.opens[1].listener.onChunk(chunk("second"))
            harness.opens[2].listener.onChunk(chunk("third"))
            harness.opens[1].exit.complete(exit(code = 12))
            harness.opens[2].exit.complete(exit(code = 13))

            assertEquals(
                setOf(listOf(chunk("second")), listOf(chunk("third"))),
                concurrent.awaitAll().toSet(),
            )
            assertEquals(listOf(1, 1, 1), harness.opens.map { it.closeCalls.get() })
        }

    @Test
    fun `rendezvous delivery blocks the producer until collector requests next chunk`() =
        runBlocking {
            val harness = FlowHarness()
            val firstReceived = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val secondAttempted = CompletableDeferred<Unit>()
            val secondReturned = CompletableDeferred<Unit>()
            val producerThread = AtomicReference<Thread>()
            val collection = async {
                javaService()
                    .listen()
                    .openFlow(harness::launch)
                    .onEach { item ->
                        if (item.text() == "first") {
                            firstReceived.complete(Unit)
                            releaseCollector.await()
                        }
                    }
                    .toList()
            }
            awaitCondition { harness.opens.size == 1 }
            val open = harness.opens.single()
            val producer =
                async(Dispatchers.IO) {
                    producerThread.set(Thread.currentThread())
                    open.listener.onChunk(chunk("first"))
                    secondAttempted.complete(Unit)
                    open.listener.onChunk(chunk("second"))
                    secondReturned.complete(Unit)
                }

            firstReceived.await()
            secondAttempted.await()
            awaitCondition {
                secondReturned.isCompleted ||
                    producerThread.get().state == Thread.State.WAITING ||
                    producerThread.get().state == Thread.State.TIMED_WAITING
            }
            assertFalse(secondReturned.isCompleted)

            releaseCollector.complete(Unit)
            producer.await()
            open.exit.complete(exit())

            assertEquals(listOf(chunk("first"), chunk("second")), collection.await())
            assertEquals(1, open.closeCalls.get())
        }

    @Test
    fun `normal completion emits chunks and deliberately discards StreamExit metadata`() =
        runBlocking {
            val harness = FlowHarness()
            val collection = async { javaService().listen().openFlow(harness::launch).toList() }
            awaitCondition { harness.opens.size == 1 }
            val open = harness.opens.single()
            open.listener.onChunk(chunk("value"))

            open.exit.complete(exit(code = 73, timedOut = true))

            assertEquals(listOf(chunk("value")), collection.await())
            assertEquals(1, open.closeCalls.get())
        }

    @Test
    fun `exceptional launch and exceptional exit fail collection`() = runBlocking {
        val launchFailure = IllegalStateException("launch failed")
        val launchResult = runCatching {
            javaService().listen().openFlow { _, _ -> throw launchFailure }.toList()
        }
        assertEquals(launchFailure.message, launchResult.exceptionOrNull()?.message)
        assertTrue(launchResult.exceptionOrNull() is IllegalStateException)

        supervisorScope {
            val harness = FlowHarness()
            val collection = async { javaService().listen().openFlow(harness::launch).toList() }
            awaitCondition { harness.opens.size == 1 }
            val exitFailure = IllegalArgumentException("exit failed")

            harness.opens.single().exit.completeExceptionally(exitFailure)

            val observed = assertFailsWith<IllegalArgumentException> { collection.await() }
            assertEquals(exitFailure.message, observed.message)
            assertEquals(1, harness.opens.single().closeCalls.get())
        }
    }

    @Test
    fun `cancellation during startup closes registered ownership exactly once`() = runBlocking {
        val launchStarted = CountDownLatch(1)
        val neverRelease = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val collection =
            async(start = CoroutineStart.UNDISPATCHED) {
                javaService()
                    .listen()
                    .openFlow { _, own ->
                        own { closeCalls.incrementAndGet() }
                        launchStarted.countDown()
                        neverRelease.await()
                        CompletableFuture()
                    }
                    .toList()
            }
        awaitCondition { launchStarted.count == 0L }

        collection.cancelAndJoin()
        collection.cancelAndJoin()

        assertTrue(collection.isCancelled)
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `cancellation after open cancels exit view and closes ownership exactly once`() =
        runBlocking {
            val harness = FlowHarness()
            val collection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    javaService().listen().openFlow(harness::launch).toList()
                }
            awaitCondition { harness.opens.size == 1 }
            val open = harness.opens.single()
            awaitCondition { open.exitView.numberOfDependents == 1 }

            collection.cancelAndJoin()
            collection.cancelAndJoin()

            assertTrue(collection.isCancelled)
            assertTrue(open.exitView.isCancelled)
            assertFalse(open.exit.isCancelled)
            assertFalse(open.exit.isDone)
            assertEquals(1, open.closeCalls.get())
        }

    @Test
    fun `public openFlow replaces an existing output listener`() = runBlocking {
        val priorListenerCalls = AtomicInteger()
        val chunks =
            javaService()
                .listen()
                .withArgs(*fixtureArgs("many-lines", "20"))
                .onOutput { priorListenerCalls.incrementAndGet() }
                .openFlow()
                .toList()

        assertEquals(0, priorListenerCalls.get())
        assertTrue(chunks.joinToString("") { it.text() }.contains("line:19"))
    }

    @Test
    fun `real flow cancellation closes its launched process`() = runBlocking {
        val pidFile = Files.createTempFile("procwright-kotlin-flow-", ".pid")
        Files.writeString(pidFile, "")
        val collection = async {
            javaService()
                .listen()
                .withArgs(*fixtureArgs("pid-file-sleep", pidFile.toString(), "30000"))
                .openFlow()
                .toList()
        }
        var pid: Long? = null
        try {
            pid = awaitPid(pidFile)

            collection.cancelAndJoin()

            awaitCondition { !isAlive(pid) }
        } finally {
            collection.cancelAndJoin()
            pid?.let(::destroyForcibly)
            Files.deleteIfExists(pidFile)
        }
    }

    private fun chunk(text: String) = StreamChunk(StreamSource.STDOUT, text)

    private fun exit(code: Int = 0, timedOut: Boolean = false) =
        StreamExit(
            OptionalInt.of(code),
            timedOut,
            false,
            StreamTranscript("diagnostic", false),
            JavaDuration.ofMillis(1),
        )

    private class FlowHarness {
        val opens = CopyOnWriteArrayList<Open>()

        fun launch(
            listener: StreamListener,
            own: ((() -> Unit) -> Unit),
        ): CompletableFuture<StreamExit> {
            val open = Open(listener)
            own { open.closeCalls.incrementAndGet() }
            opens += open
            return open.exit.copy().also { open.exitView = it }
        }
    }

    private class Open(val listener: StreamListener) {
        val exit = CompletableFuture<StreamExit>()
        lateinit var exitView: CompletableFuture<StreamExit>
        val closeCalls = AtomicInteger()
    }
}
