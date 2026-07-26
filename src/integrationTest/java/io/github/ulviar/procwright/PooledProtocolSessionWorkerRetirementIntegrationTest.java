/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.PooledProtocolSessionIntegrationFixtures.poolDraft;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.EXTERNAL_WATCHDOG_SECONDS;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.captureFailure;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionWorkerRetirementIntegrationTest {

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
    void pooledProtocolSessionRecordsRetireReason() throws Exception {
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), FramedStringAdapter::new, "length-line-frame")
                        .withMaxSize(1)
                        .withMaxRequestsPerWorker(1)
                        .open()) {
            assertEquals("first", pool.request("first"));

            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
        }
    }

    @Test
    void pooledProtocolExitedWorkerUsesOnlyProcessExitedRetirementReason() {
        try (PooledProtocolSession<String, String> pool =
                poolDraft(fixtureService(), TextLineAdapter::new, "exit-after-read", "--stdout=ok")
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
            PooledSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.DECODER_FAILED));
        }
    }
}
