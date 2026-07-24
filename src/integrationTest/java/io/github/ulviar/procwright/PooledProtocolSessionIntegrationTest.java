/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.EXTERNAL_WATCHDOG_SECONDS;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.captureFailure;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.parseLength;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionIntegrationTest {

    @Test
    void pooledProtocolSessionReusesTypedWorkersWithoutExposingLease() {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(FramedStringAdapter::new)
                .withArgs("length-line-frame")
                .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withMinIdle(1)
                .open()) {
            assertEquals("first", pool.request("first"));
            assertEquals("second", pool.request("second"));

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.created());
            assertEquals(2, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.idle());
        }
    }

    @Test
    void pooledProtocolRejectsNullBeforeLeasingOrRetiringWorker() {
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), FramedStringAdapter::new, "length-line-frame")
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            assertThrows(NullPointerException.class, () -> pool.request(null));
            assertThrows(NullPointerException.class, () -> pool.request(null, Duration.ofSeconds(1)));

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(0, metrics.retired());
            assertEquals(0, metrics.failedRequests());
            assertEquals("valid", pool.request("valid"));
        }
    }

    @Test
    void retiredProtocolWorkerCleansObservedDescendantBeforeMetricsPublication() throws Exception {
        AtomicLong observedChildPid = new AtomicLong();
        long childPid = -1;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {}

            @Override
            public String readResponse(ProtocolReaders readers) {
                String child = readers.stdout().readLine(128);
                observedChildPid.set(Long.parseLong(child.substring("child:".length())));
                return readers.stdout().readLine(128);
            }
        };
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(adapterFactory)
                .withArgs("spawn-child", "--child-scenario=never-exit", "--linger-millis=500")
                .withRequestTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1))
                .pooled()
                .withMaxSize(1)
                .withAcquireTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1))
                .withHookTimeout(Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS + 1))
                .open()) {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> pool.request("request")));
            ProtocolSessionException failure = assertInstanceOf(
                    ProtocolSessionException.class, request.get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS));
            childPid = observedChildPid.get();

            assertTrue(
                    failure.reason() == ProtocolSessionException.Reason.EOF
                            || failure.reason() == ProtocolSessionException.Reason.PROCESS_EXITED,
                    "unexpected protocol termination reason: " + failure.reason());
            assertTrue(childPid > 0, "worker output did not publish the child process id");
            assertTrue(PoolTestAccess.awaitProtocolMetrics(
                    pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(EXTERNAL_WATCHDOG_SECONDS)));
            assertFalse(
                    ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "retired protocol worker left an observed descendant alive");
        } finally {
            try {
                long cleanupPid = observedChildPid.get();
                ProcessHandle child =
                        cleanupPid > 0 ? ProcessHandle.of(cleanupPid).orElse(null) : null;
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                    child.onExit().get(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(EXTERNAL_WATCHDOG_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolCloseDistinguishesInterruptionFromDrainTimeout() throws Exception {
        CoordinatedResponseAdapter adapter = new CoordinatedResponseAdapter();
        PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), () -> adapter, "ignore-stdin", "--millis=5000")
                .withMaxSize(1)
                .open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> request = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
            assertTrue(adapter.awaitResponseEntered());
            assertEquals(1, pool.metrics().leased());
            CompletableFuture<Void> eventual = pool.closeAsync();

            PooledProtocolSessionException interrupted;
            try {
                Thread.currentThread().interrupt();
                interrupted = assertThrows(PooledProtocolSessionException.class, pool::close);
                assertEquals(true, Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }

            assertEquals(PooledProtocolSessionException.Reason.INTERRUPTED, interrupted.reason());
            adapter.releaseResponse();
            assertEquals("slept:hold", request.get(2, TimeUnit.SECONDS));
            eventual.get(2, TimeUnit.SECONDS);
        } finally {
            adapter.releaseResponse();
            executor.shutdownNow();
            assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void pooledProtocolSessionRecordsRetireReason() throws Exception {
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), FramedStringAdapter::new, "length-line-frame")
                .withMaxSize(1)
                .withMaxRequestsPerWorker(1)
                .open()) {
            assertEquals("first", pool.request("first"));

            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
        }
    }

    @Test
    void pooledProtocolExitedWorkerUsesOnlyProcessExitedRetirementReason() {
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), TextLineAdapter::new, "exit-after-read", "--stdout=ok")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withReset(worker -> worker.onExit().join())
                .open()) {
            assertEquals("ok", pool.request("first"));
            assertEquals("ok", pool.request("second"));

            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.PROCESS_EXITED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.HEALTH_FAILED));
        }
    }

    @Test
    void poolDraftSettingsAreAppliedAtOpen() throws Exception {
        AtomicInteger responses = new AtomicInteger();
        CountDownLatch replacementCreated = new CountDownLatch(1);
        CountDownLatch secondResponseEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondResponse = new CountDownLatch(1);
        AtomicInteger adapters = new AtomicInteger();
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> {
            if (adapters.incrementAndGet() == 2) {
                replacementCreated.countDown();
            }
            return new FramedStringAdapter() {
                @Override
                public String readResponse(ProtocolReaders readers) {
                    String response = super.readResponse(readers);
                    if (responses.incrementAndGet() == 2) {
                        secondResponseEntered.countDown();
                        awaitIgnoringInterrupts(releaseSecondResponse);
                    }
                    return response;
                }
            };
        };
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), adapterFactory, "length-line-frame")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withMinIdle(1)
                .withMaxRequestsPerWorker(1)
                .open()) {
            assertEquals("first", pool.request("first"));
            assertTrue(replacementCreated.await(2, TimeUnit.SECONDS));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> second = executor.submit(() -> pool.request("second"));
                assertTrue(secondResponseEntered.await(2, TimeUnit.SECONDS));

                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
                assertEquals(1, metrics.size());
                assertEquals(1, metrics.leased());
                assertEquals(2, metrics.created());

                releaseSecondResponse.countDown();
                assertEquals("second", second.get(2, TimeUnit.SECONDS));
            } finally {
                releaseSecondResponse.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolWarmupReadinessFailureIsStartupFailure() {
        PooledProtocolSessionException exception =
                assertThrows(PooledProtocolSessionException.class, () -> fixtureService()
                        .protocolSession(FramedStringAdapter::new)
                        .withArgs("length-line-frame")
                        .withReadiness(ready -> {
                            throw new IllegalStateException("not ready");
                        })
                        .pooled()
                        .withWarmupSize(1)
                        .open());

        assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, exception.reason());
    }

    @Test
    void pooledProtocolAcquireTimeoutIsDistinctFromRequestTimeout() throws Exception {
        CoordinatedResponseAdapter adapter = new CoordinatedResponseAdapter();
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), () -> adapter, "ignore-stdin", "--millis=5000")
                .withMaxSize(1)
                .withAcquireTimeout(Duration.ofMillis(100))
                .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch firstStarted = new CountDownLatch(1);
            try {
                Future<String> first = executor.submit(() -> {
                    firstStarted.countDown();
                    return pool.request("first", Duration.ofSeconds(2));
                });
                assertEquals(true, firstStarted.await(1, TimeUnit.SECONDS));
                assertTrue(adapter.awaitResponseEntered());
                assertEquals(1, pool.metrics().leased());

                PooledProtocolSessionException exception =
                        assertThrows(PooledProtocolSessionException.class, () -> pool.request("second"));

                assertEquals(PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
                adapter.releaseResponse();
                assertEquals("slept:first", first.get(2, TimeUnit.SECONDS));
            } finally {
                adapter.releaseResponse();
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolCreatesOneAdapterPerWorker() throws Exception {
        ConcurrentLinkedQueue<Integer> adapterIds = new ConcurrentLinkedQueue<>();
        AtomicIntegerAdapterFactory factory = new AtomicIntegerAdapterFactory(adapterIds);

        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), factory::newAdapter, "length-line-frame")
                .withMaxSize(2)
                .withWarmupSize(2)
                .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> pool.request("first"));
                Future<String> second = executor.submit(() -> pool.request("second"));

                assertEquals(true, first.get(2, TimeUnit.SECONDS).matches("adapter-[12]:first"));
                assertEquals(true, second.get(2, TimeUnit.SECONDS).matches("adapter-[12]:second"));
                assertEquals(2, adapterIds.size());
                assertTrue(adapterIds.containsAll(List.of(1, 2)));
            } finally {
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolPoolHealthHookTimeoutIsBounded() throws Exception {
        NonCooperativeTask health = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledProtocolSession<String, String> pool = protocolPool(
                            fixtureService(), FramedStringAdapter::new, "length-line-frame")
                    .withMaxSize(1)
                    .withWarmupSize(1)
                    .withHookTimeout(Duration.ofMillis(50))
                    .withHealthCheck(worker -> {
                        health.run();
                        return true;
                    })
                    .open()) {
                Future<PooledProtocolSessionException> request = executor.submit(() -> {
                    try {
                        pool.request("hello");
                        throw new AssertionError("expected health timeout");
                    } catch (PooledProtocolSessionException exception) {
                        return exception;
                    }
                });
                PooledProtocolSessionException exception = request.get(1, TimeUnit.SECONDS);

                assertTrue(health.awaitEntered());
                assertEquals(PooledProtocolSessionException.Reason.HOOK_TIMEOUT, exception.reason());
                health.releaseAndJoin();
                assertEquals("second", pool.request("second"));
                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
                assertFalse(metrics.retireReasons().containsKey(PooledWorkerRetireReason.PROCESS_EXITED));
                assertEquals(2, metrics.created());
            }
        } finally {
            health.release();
            health.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void protocolPoolResetHookTimeoutRetiresWorkerWithoutChangingCompletedRequestOutcome() throws Exception {
        NonCooperativeTask reset = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledProtocolSession<String, String> pool = protocolPool(
                            fixtureService(), FramedStringAdapter::new, "length-line-frame")
                    .withMaxSize(1)
                    .withHookTimeout(Duration.ofMillis(50))
                    .withReset(worker -> reset.run())
                    .open()) {
                Future<String> request = executor.submit(() -> pool.request("hello"));
                String response = request.get(1, TimeUnit.SECONDS);

                assertTrue(reset.awaitEntered());
                assertEquals("hello", response);
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(0, pool.metrics().failedRequests());
                reset.releaseAndJoin();
                assertEquals("second", pool.request("second"));
                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
                assertEquals(2, metrics.created());
            }
        } finally {
            reset.release();
            reset.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void protocolPoolResetErrorIsRethrownAfterRecordingCompletedRequestAndResetRetirement() throws Exception {
        AssertionError resetError = new AssertionError("reset invariant failed");
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), FramedStringAdapter::new, "length-line-frame")
                .withMaxSize(1)
                .withReset(worker -> {
                    throw resetError;
                })
                .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(resetError, thrown);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
        }
    }

    @Test
    void protocolPoolRequestErrorIsRethrownAndRecordedAsFailedRequest() throws Exception {
        AssertionError decoderError = new AssertionError("decoder invariant failed");
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                throw decoderError;
            }
        };
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), adapterFactory, "length-line-frame")
                .withMaxSize(1)
                .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(decoderError, thrown);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
        }
    }

    @Test
    void protocolPoolRetiresWorkerWhenDecoderReturnsNull() throws Exception {
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                return null;
            }
        };
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(adapterFactory)
                .withArgs("length-line-frame")
                .withTranscriptLimit(8)
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1)
                .withBackgroundReplenishment(false)
                .open()) {
            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class, () -> pool.request("0123456789abcdef0123456789abcdef"));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertInstanceOf(NullPointerException.class, exception.getCause());
            assertTrue(exception.transcript().truncated());
            assertTrue(exception.transcript().text().length() <= 8);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.DECODER_FAILED));
        }
    }

    private static <I, O> ProtocolSessionScenario.PoolDraft<I, O> protocolPool(
            CommandService service,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            String... workerArguments) {
        return service.protocolSession(adapterFactory).withArgs(workerArguments).pooled();
    }

    private static final class CoordinatedResponseAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponse = new CountDownLatch(1);
        private String request;

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            responseEntered.countDown();
            awaitIgnoringInterrupts(releaseResponse);
            return "slept:" + request;
        }

        private boolean awaitResponseEntered() throws InterruptedException {
            return responseEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseResponse() {
            releaseResponse.countDown();
        }
    }

    private static final class AtomicIntegerAdapterFactory {

        private final ConcurrentLinkedQueue<Integer> adapterIds;
        private final AtomicInteger nextId = new AtomicInteger();

        private AtomicIntegerAdapterFactory(ConcurrentLinkedQueue<Integer> adapterIds) {
            this.adapterIds = adapterIds;
        }

        private ProtocolAdapter<String, String> newAdapter() {
            int id = nextId.incrementAndGet();
            adapterIds.add(id);
            return new WorkerScopedAdapter(id);
        }
    }

    private static final class WorkerScopedAdapter implements ProtocolAdapter<String, String> {

        private final int id;
        private String request;

        private WorkerScopedAdapter(int id) {
            this.id = id;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return "adapter-" + id + ":" + request;
        }
    }

    private static final class NonCooperativeTask {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread thread;

        private void run() {
            thread = Thread.currentThread();
            entered.countDown();
            awaitIgnoringInterrupts(release);
        }

        private boolean awaitEntered() {
            try {
                return entered.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void release() {
            release.countDown();
        }

        private void releaseAndJoin() throws InterruptedException {
            release();
            join();
        }

        private void join() throws InterruptedException {
            Thread callback = thread;
            if (callback != null) {
                callback.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(callback.isAlive(), "lifecycle callback did not finish");
            }
        }
    }
}
