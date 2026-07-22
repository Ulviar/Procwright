/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.kotlin

import io.github.ulviar.procwright.command.ShutdownPolicy
import io.github.ulviar.procwright.session.PooledWorkerRetireReason
import java.nio.file.Files
import java.time.Duration as JavaDuration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CoroutineExtensionsTest {

    @Test
    fun `cancelled detached wait cancels its view and releases observable callbacks`() =
        runBlocking {
            repeat(100) {
                val future = RecordingFuture<String>()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { future.awaitDetached() }
                assertEquals(1, future.numberOfDependents)

                waiter.cancelAndJoin()

                assertTrue(waiter.isCancelled)
                assertTrue(future.isCancelled)
                assertEquals(1, future.cancelCalls.get())
                assertEquals(0, future.numberOfDependents)
            }
        }

    @Test
    fun `cancelling a per-call future view leaves its shared source untouched`() = runBlocking {
        val shared = CompletableFuture<String>()
        val view = shared.copy()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { view.awaitDetached() }

        waiter.cancelAndJoin()

        assertTrue(view.isCancelled)
        assertFalse(shared.isCancelled)
        assertFalse(shared.isDone)
        assertTrue(shared.complete("finished"))
        assertEquals("finished", shared.join())
    }

    @Test
    fun `cancelling execute await stops the process after an explicit startup handshake`() =
        runBlocking {
            val pidFile = Files.createTempFile("procwright-kotlin-execute-", ".pid")
            Files.writeString(pidFile, "")
            val execution = async {
                javaService()
                    .run()
                    .withArgs(*fixtureArgs("pid-file-sleep", pidFile.toString(), "30000"))
                    .withShutdown(
                        ShutdownPolicy.interruptThenKill(
                            JavaDuration.ofMillis(50),
                            JavaDuration.ofSeconds(1),
                        )
                    )
                    .executeAwait()
            }
            var pid: Long? = null
            try {
                pid = awaitPid(pidFile)
                assertTrue(isAlive(pid))

                execution.cancelAndJoin()

                assertTrue(execution.isCancelled)
                awaitCondition { !isAlive(pid) }
            } finally {
                execution.cancelAndJoin()
                pid?.let(::destroyForcibly)
                Files.deleteIfExists(pidFile)
            }
        }

    @Test
    fun `cancelling direct request await closes line and protocol sessions`() = runBlocking {
        val lineMarker = Files.createTempFile("procwright-kotlin-line-request-", ".marker")
        val protocolMarker = Files.createTempFile("procwright-kotlin-protocol-request-", ".marker")
        Files.writeString(lineMarker, "")
        Files.writeString(protocolMarker, "")
        try {
            javaService()
                .lineSession()
                .withArgs(*fixtureArgs("controlled-line-repl", lineMarker.toString()))
                .open()
                .use { session ->
                    val request = async { session.requestAwait("slow", 30.seconds) }
                    awaitCondition { Files.readString(lineMarker) == "received" }

                    request.cancelAndJoin()

                    assertTrue(request.isCancelled)
                    session.onExit().get(2, TimeUnit.SECONDS)
                }

            javaService()
                .protocolSession(lineAdapterFactory())
                .withArgs(*fixtureArgs("controlled-line-repl", protocolMarker.toString()))
                .open()
                .use { session ->
                    val request = async { session.requestAwait("slow", 30.seconds) }
                    awaitCondition { Files.readString(protocolMarker) == "received" }

                    request.cancelAndJoin()

                    assertTrue(request.isCancelled)
                    session.onExit().get(2, TimeUnit.SECONDS)
                }
        } finally {
            Files.deleteIfExists(lineMarker)
            Files.deleteIfExists(protocolMarker)
        }
    }

    @Test
    fun `cancelling acquisition does not retire worker but active pooled line request does`() =
        runBlocking {
            val marker = Files.createTempFile("procwright-kotlin-pooled-line-", ".marker")
            Files.writeString(marker, "")
            val pool =
                javaService()
                    .lineSession()
                    .withArgs(*fixtureArgs("controlled-line-repl", marker.toString()))
                    .withRequestTimeout(30.seconds)
                    .pooled()
                    .withMaxSize(1)
                    .withMinIdle(0)
                    .open()
            var active: Deferred<*>? = null
            var acquiring: Deferred<*>? = null
            try {
                val activeRequest = async { pool.requestAwait("slow") }
                active = activeRequest
                awaitCondition {
                    pool.metrics().leased() == 1 && Files.readString(marker) == "received"
                }
                val baseline = pool.metrics()
                val waitingRequest =
                    async(start = CoroutineStart.UNDISPATCHED) { pool.requestAwait("queued") }
                acquiring = waitingRequest
                assertFalse(waitingRequest.isCompleted)
                awaitCondition(::hasBlockedWorkerAcquisition)

                waitingRequest.cancelAndJoin()
                awaitCondition {
                    pool.metrics().failedRequests() == baseline.failedRequests() + 1 &&
                        pool.metrics().totalAcquireWaitNanos() > baseline.totalAcquireWaitNanos()
                }

                assertTrue(waitingRequest.isCancelled)
                assertEquals(1, pool.metrics().leased())
                assertEquals(baseline.retired(), pool.metrics().retired())

                activeRequest.cancelAndJoin()
                awaitCondition { pool.metrics().retired() == baseline.retired() + 1 }
                assertEquals(
                    1L,
                    pool.metrics().retireReasons()[PooledWorkerRetireReason.WORKER_FAILED],
                )
                assertEquals("response:after", pool.requestAwait("after", 2.seconds).text())
            } finally {
                try {
                    withContext(NonCancellable) {
                        try {
                            acquiring?.cancelAndJoin()
                            active?.cancelAndJoin()
                        } finally {
                            pool.close()
                        }
                    }
                } finally {
                    Files.deleteIfExists(marker)
                }
            }
        }

    @Test
    fun `cancelling active pooled protocol request retires leased worker with reason`() =
        runBlocking {
            val marker = Files.createTempFile("procwright-kotlin-pooled-protocol-", ".marker")
            Files.writeString(marker, "")
            val pool =
                javaService()
                    .protocolSession(lineAdapterFactory())
                    .withArgs(*fixtureArgs("controlled-line-repl", marker.toString()))
                    .pooled()
                    .withMaxSize(1)
                    .withMinIdle(0)
                    .open()
            var request: Deferred<*>? = null
            try {
                val activeRequest = async { pool.requestAwait("slow", 30.seconds) }
                request = activeRequest
                awaitCondition {
                    pool.metrics().leased() == 1 && Files.readString(marker) == "received"
                }

                activeRequest.cancelAndJoin()

                assertTrue(activeRequest.isCancelled)
                awaitCondition {
                    pool.metrics().retireReasons()[PooledWorkerRetireReason.WORKER_FAILED] == 1L
                }
                assertEquals(1L, pool.metrics().retired())
                assertEquals("response:after", pool.requestAwait("after", 2.seconds))
            } finally {
                try {
                    withContext(NonCancellable) {
                        try {
                            request?.cancelAndJoin()
                        } finally {
                            pool.close()
                        }
                    }
                } finally {
                    Files.deleteIfExists(marker)
                }
            }
        }

    @Test
    fun `cancelling exit waits leaves every real session usable or alive`() = runBlocking {
        javaService().lineSession().withArgs(*fixtureArgs("line-repl")).open().use { session ->
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitExit() }
            waiter.cancelAndJoin()

            assertFalse(session.onExit().isDone)
            assertEquals("response:line", session.request("line").text())
        }

        javaService()
            .protocolSession(lineAdapterFactory())
            .withArgs(*fixtureArgs("line-repl"))
            .open()
            .use { session ->
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitExit() }
                waiter.cancelAndJoin()

                assertFalse(session.onExit().isDone)
                assertEquals("response:protocol", session.request("protocol"))
            }

        assertLiveProcessSurvivesExitWaitCancellation(stream = false)
        assertLiveProcessSurvivesExitWaitCancellation(stream = true)
    }

    private suspend fun assertLiveProcessSurvivesExitWaitCancellation(stream: Boolean) {
        val pidFile = Files.createTempFile("procwright-kotlin-exit-wait-", ".pid")
        Files.writeString(pidFile, "")
        var pid: Long? = null
        try {
            if (stream) {
                javaService()
                    .listen()
                    .withArgs(*fixtureArgs("pid-file-sleep", pidFile.toString(), "30000"))
                    .open()
                    .use { session ->
                        pid = awaitPid(pidFile)
                        val waiter =
                            kotlinx.coroutines
                                .CoroutineScope(kotlin.coroutines.coroutineContext)
                                .async(start = CoroutineStart.UNDISPATCHED) { session.awaitExit() }
                        waiter.cancelAndJoin()
                        assertTrue(isAlive(pid))
                        assertFalse(session.onExit().isDone)
                    }
            } else {
                javaService()
                    .interactive()
                    .withArgs(*fixtureArgs("pid-file-sleep", pidFile.toString(), "30000"))
                    .open()
                    .use { session ->
                        pid = awaitPid(pidFile)
                        val waiter =
                            kotlinx.coroutines
                                .CoroutineScope(kotlin.coroutines.coroutineContext)
                                .async(start = CoroutineStart.UNDISPATCHED) { session.awaitExit() }
                        waiter.cancelAndJoin()
                        assertTrue(isAlive(pid))
                        assertFalse(session.onExit().isDone)
                    }
            }
        } finally {
            pid?.let(::destroyForcibly)
            Files.deleteIfExists(pidFile)
        }
    }

    private fun hasBlockedWorkerAcquisition(): Boolean =
        Thread.getAllStackTraces().any { (thread, stack) ->
            thread.state == Thread.State.TIMED_WAITING &&
                stack.any { frame ->
                    frame.className ==
                        "io.github.ulviar.procwright.internal.session.WorkerPoolController" &&
                        frame.methodName == "takeOrReserveWorker"
                }
        }

    private class RecordingFuture<T> : CompletableFuture<T>() {
        val cancelCalls = AtomicInteger()

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelCalls.incrementAndGet()
            return super.cancel(mayInterruptIfRunning)
        }
    }
}
