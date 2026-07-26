/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.BlockingReadFailingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.CLOSE_TIMEOUT;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TestProcess;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TrackingInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TrackingOutputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.assertNoDispatcherLeak;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.openLineWorker;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.openProtocolWorker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledWorkerWarmupFailureCleanupTest {

    @Test
    void failedLineWarmupReturnsStartupFailureWhilePhysicalCleanupRemainsOwned() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        LineSession firstWorker = openLineWorker(process, dispatcher);
        assertTrue(stdout.awaitReadStarted(), "line stdout pump did not enter the blocked read");
        AtomicInteger starts = new AtomicInteger();
        IllegalStateException startupFailure = new IllegalStateException("second worker startup failed");

        try {
            PooledSessionException failure = assertThrows(
                    PooledSessionException.class,
                    () -> new DefaultPooledLineSession(
                            () -> {
                                if (starts.incrementAndGet() == 1) {
                                    return firstWorker;
                                }
                                throw startupFailure;
                            },
                            LineSessionSettings.defaults(),
                            WorkerPoolSettings.<LineSession>defaults()
                                    .withMaxSize(2)
                                    .withWarmupSize(2)
                                    .withCloseTimeout(CLOSE_TIMEOUT)));

            assertEquals(PooledSessionException.Reason.STARTUP_FAILED, failure.reason());
            assertEquals("Could not start pooled line-session worker", failure.getMessage());
            assertSame(startupFailure, failure.getCause());
            assertTrue(stdout.awaitCloseInvoked(), "failed construction did not start worker cleanup");
            assertFalse(stdout.closeFinished(), "startup failure must not abandon physical cleanup");

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            firstWorker.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
        }
    }

    @Test
    void failedProtocolWarmupReturnsStartupFailureWhilePhysicalCleanupRemainsOwned() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        ProtocolSession<String, String> firstWorker = openProtocolWorker(process, dispatcher);
        assertTrue(stdout.awaitReadStarted(), "protocol stdout pump did not enter the blocked read");
        AtomicInteger starts = new AtomicInteger();
        IllegalStateException startupFailure = new IllegalStateException("second worker startup failed");

        try {
            PooledSessionException failure = assertThrows(
                    PooledSessionException.class,
                    () -> new DefaultPooledProtocolSession<>(
                            () -> {
                                if (starts.incrementAndGet() == 1) {
                                    return firstWorker;
                                }
                                throw startupFailure;
                            },
                            WorkerPoolSettings.<ProtocolSession<String, String>>defaults()
                                    .withMaxSize(2)
                                    .withWarmupSize(2)
                                    .withCloseTimeout(CLOSE_TIMEOUT)));

            assertEquals(PooledSessionException.Reason.STARTUP_FAILED, failure.reason());
            assertEquals("Could not start pooled protocol-session worker", failure.getMessage());
            assertSame(startupFailure, failure.getCause());
            assertTrue(stdout.awaitCloseInvoked(), "failed construction did not start worker cleanup");
            assertFalse(stdout.closeFinished(), "startup failure must not abandon physical cleanup");

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            assertTrue(stdout.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stdin.awaitCloseFinished(Duration.ofSeconds(1)));
            assertTrue(stderr.awaitCloseFinished(Duration.ofSeconds(1)));
            firstWorker.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
        }
    }
}
