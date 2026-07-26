/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.BlockingReadFailingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.CLOSE_TIMEOUT;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.ImmediateFailingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TestProcess;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TrackingInputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.TrackingOutputStream;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.assertNoDispatcherLeak;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.noOpAdapter;
import static io.github.ulviar.procwright.internal.session.PooledWorkerPhysicalCleanupTestSupport.openSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledWorkerWarmupFailureCleanupTest {

    @Test
    void lineWarmupCleanupErrorRemainsFatalOverTheEarlierTypedStartupFailure() throws Exception {
        AssertionError cleanupFailure = new AssertionError("line worker cleanup failed");
        IllegalStateException startupFailure = new IllegalStateException("second worker startup failed");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(cleanupFailure),
                new TrackingInputStream());
        LineSession firstWorker =
                new DefaultLineSession(openSession(process, dispatcher), LineSessionSettings.defaults());
        AtomicInteger starts = new AtomicInteger();

        try {
            Error observed = assertThrows(
                    Error.class,
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
                                    .withCloseTimeout(Duration.ofSeconds(1))));

            assertSame(cleanupFailure, FailureAggregation.primary(observed));
            List<Throwable> sources = FailureAggregation.sources(observed);
            PooledSessionException startup = assertInstanceOf(PooledSessionException.class, sources.get(0));
            assertEquals(PooledSessionException.Reason.STARTUP_FAILED, startup.reason());
            assertSame(startupFailure, startup.getCause());
            assertSame(cleanupFailure, sources.get(1));
            assertEquals(0, startup.getSuppressed().length);
            assertEquals(0, startupFailure.getSuppressed().length);
            assertEquals(0, cleanupFailure.getSuppressed().length);
            assertNoDispatcherLeak(dispatcher);
        } finally {
            process.complete(143);
        }
    }

    @Test
    void protocolWarmupCleanupErrorRemainsFatalOverTheEarlierTypedStartupFailure() throws Exception {
        AssertionError cleanupFailure = new AssertionError("protocol worker cleanup failed");
        IllegalStateException startupFailure = new IllegalStateException("second worker startup failed");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(cleanupFailure),
                new TrackingInputStream());
        ProtocolSession<String, String> firstWorker = new DefaultProtocolSession<>(
                openSession(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        AtomicInteger starts = new AtomicInteger();

        try {
            Error observed = assertThrows(
                    Error.class,
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
                                    .withCloseTimeout(Duration.ofSeconds(1))));

            assertSame(cleanupFailure, FailureAggregation.primary(observed));
            List<Throwable> sources = FailureAggregation.sources(observed);
            PooledSessionException startup = assertInstanceOf(PooledSessionException.class, sources.get(0));
            assertEquals(PooledSessionException.Reason.STARTUP_FAILED, startup.reason());
            assertSame(startupFailure, startup.getCause());
            assertSame(cleanupFailure, sources.get(1));
            assertEquals(0, startup.getSuppressed().length);
            assertEquals(0, startupFailure.getSuppressed().length);
            assertEquals(0, cleanupFailure.getSuppressed().length);
            assertNoDispatcherLeak(dispatcher);
        } finally {
            process.complete(143);
        }
    }

    @Test
    void failedLineWarmupExposesTypedDetachedSourcesWhilePhysicalCleanupRemainsOwned() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        LineSession firstWorker =
                new DefaultLineSession(openSession(process, dispatcher), LineSessionSettings.defaults());
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
            Throwable aggregate = failure.getCause();
            PooledSessionException primary = (PooledSessionException) FailureAggregation.primary(aggregate);
            List<Throwable> sources = FailureAggregation.sources(aggregate);
            assertSame(startupFailure, primary.getCause());
            assertSame(primary, sources.get(0));
            assertEquals(2, sources.size());
            PooledSessionException cleanup = (PooledSessionException) sources.get(1);
            assertEquals(PooledSessionException.Reason.WORKER_FAILED, cleanup.reason());
            assertTrue(cleanup.getCause() instanceof TimeoutException);
            assertEquals(0, primary.getSuppressed().length);
            assertEquals(0, cleanup.getSuppressed().length);
            assertEquals(0, startupFailure.getSuppressed().length);
            assertTrue(stdout.awaitCloseInvoked(), "failed construction did not start worker cleanup");
            assertFalse(stdout.closeFinished(), "constructor timeout must not abandon physical cleanup");

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
    void failedProtocolWarmupReturnsAfterCloseDeadlineWhilePhysicalCleanupRemainsOwned() throws Exception {
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(null);
        TrackingOutputStream stdin = new TrackingOutputStream();
        TrackingInputStream stderr = new TrackingInputStream();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(stdin, stdout, stderr);
        ProtocolSession<String, String> firstWorker = new DefaultProtocolSession<>(
                openSession(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
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
            Throwable aggregate = failure.getCause();
            PooledSessionException primary = (PooledSessionException) FailureAggregation.primary(aggregate);
            assertSame(startupFailure, primary.getCause());
            List<Throwable> sources = FailureAggregation.sources(aggregate);
            assertSame(primary, sources.get(0));
            assertEquals(2, sources.size());
            PooledSessionException cleanup = (PooledSessionException) sources.get(1);
            assertEquals(PooledSessionException.Reason.WORKER_FAILED, cleanup.reason());
            assertTrue(cleanup.getCause() instanceof TimeoutException);
            assertEquals(0, primary.getSuppressed().length);
            assertEquals(0, cleanup.getSuppressed().length);
            assertEquals(0, startupFailure.getSuppressed().length);
            assertTrue(stdout.awaitCloseInvoked(), "failed construction did not start worker cleanup");
            assertFalse(stdout.closeFinished(), "constructor timeout must not abandon physical cleanup");

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
