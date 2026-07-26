/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledLineSessionRequestIntegrationTest extends PooledLineSessionIntegrationSupport {

    @Test
    void callerValidationHappensBeforeWorkerAcquire() {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            assertThrows(IllegalArgumentException.class, () -> pool.request("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> pool.request("a", Duration.ZERO));
            assertThrows(NullPointerException.class, () -> pool.request(null));
            assertThrows(NullPointerException.class, () -> pool.request("a", null));

            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.size());
            assertEquals(1, metrics.idle());
            assertEquals(1, metrics.created());
            assertEquals(0, metrics.retired());
            assertEquals(0, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
        }
    }

    @Test
    void requestSizeValidationHappensBeforeWorkerAcquire() {
        LineSessionScenario.Draft scenario = fixtureScenario().withMaxRequestChars(4);

        try (PooledLineSession pool =
                scenario.withArgs("controlled-line-repl").pooled().open()) {
            LineSessionException exception = assertThrows(LineSessionException.class, () -> pool.request("hello"));

            assertEquals(LineSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
            assertEquals(0, pool.metrics().created());
            assertEquals(1, pool.metrics().failedRequests());
        }
    }

    @Test
    void validatedPooledRequestIsEncodedOnlyOnce() {
        CountingUtf8Charset charset = new CountingUtf8Charset();
        LineSessionScenario.Draft scenario = fixtureScenario().withCharset(charset);

        try (PooledLineSession pool =
                scenario.withArgs("controlled-line-repl").pooled().open()) {
            assertThrows(IllegalArgumentException.class, () -> pool.request("hello", Duration.ZERO));
            assertEquals(0, charset.encoderCreations());
            assertEquals("response:hello", pool.request("hello").text());
        }

        assertEquals(2, charset.encoderCreations());
    }

    @Test
    void pooledRequestEncodingIsBoundedBeforeWorkerAcquire() throws Exception {
        BlockingUtf8Charset charset = new BlockingUtf8Charset();
        LineSessionScenario.Draft scenario = fixtureScenario().withCharset(charset);

        try (PooledLineSession pool = scenario.withArgs("controlled-line-repl")
                .pooled()
                .withMaxSize(1)
                .open()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request =
                        executor.submit(() -> captureFailure(() -> pool.request("first", Duration.ofMillis(50))));
                assertTrue(charset.awaitEncoderStarted());

                Throwable failure = request.get(500, TimeUnit.MILLISECONDS);

                assertTrue(failure instanceof LineSessionException);
                assertEquals(LineSessionException.Reason.TIMEOUT, ((LineSessionException) failure).reason());
                assertEquals(0, pool.metrics().created());
                assertEquals(1, pool.metrics().failedRequests());
            } finally {
                charset.releaseEncoder();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }

            assertEquals(
                    "response:second",
                    pool.request("second", Duration.ofSeconds(1)).text());
            assertEquals(1, pool.metrics().created());
        }
    }

    @Test
    void acquireTimeoutIsDistinctWhenAllWorkersAreBusy() throws Exception {
        try (PooledLineSession pool = pool(fixtureScenario(), "controlled-line-repl")
                .withMaxSize(1)
                .withAcquireTimeout(Duration.ofMillis(100))
                .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch firstStarted = new CountDownLatch(1);
            try {
                Future<LineResponse> first = executor.submit(() -> {
                    firstStarted.countDown();
                    return pool.request("hold", Duration.ofSeconds(2));
                });
                assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
                assertTrue(awaitLeased(pool, 1));

                PooledSessionException exception =
                        assertThrows(PooledSessionException.class, () -> pool.request("hello"));

                PooledSessionMetrics waiting = pool.metrics();
                assertEquals(PooledSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
                assertTrue(waiting.totalAcquireWaitNanos() > 0);
                assertTrue(waiting.totalRequestDurationNanos() > 0);
                assertEquals("response:hold", first.get().text());
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(1, pool.metrics().failedRequests());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void requestErrorIsRethrownAndRecordedAsFailedRequest() {
        AssertionError decoderError = new AssertionError("decoder invariant failed");
        LineSessionScenario.Draft scenario = fixtureScenario().withResponseDecoder(reader -> {
            reader.readLine();
            throw decoderError;
        });
        try (PooledLineSession pool =
                pool(scenario, "controlled-line-repl").withMaxSize(1).open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(decoderError, thrown);
            assertEquals(0, pool.metrics().completedRequests());
            assertEquals(1, pool.metrics().failedRequests());
            assertTrue(awaitRetired(pool, 1));
            assertEquals(1, pool.metrics().retired());
            assertEquals(1, pool.metrics().retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
        }
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class CountingUtf8Charset extends Charset {

        private final AtomicInteger encoderCreations = new AtomicInteger();

        private CountingUtf8Charset() {
            super("X-Procwright-Counting-UTF-8", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations.incrementAndGet();
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int encoderCreations() {
            return encoderCreations.get();
        }
    }

    private static final class BlockingUtf8Charset extends Charset {

        private final AtomicBoolean blockNextEncoder = new AtomicBoolean(true);
        private final CountDownLatch encoderStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEncoder = new CountDownLatch(1);

        private BlockingUtf8Charset() {
            super("X-Procwright-Pooled-Line-Blocking-UTF-8", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            if (blockNextEncoder.compareAndSet(true, false)) {
                encoderStarted.countDown();
                awaitIgnoringInterrupt(releaseEncoder);
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitEncoderStarted() throws InterruptedException {
            return encoderStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEncoder() {
            releaseEncoder.countDown();
        }
    }
}
