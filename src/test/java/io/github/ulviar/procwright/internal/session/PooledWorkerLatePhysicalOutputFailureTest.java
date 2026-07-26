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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PooledWorkerLatePhysicalOutputFailureTest {

    @Test
    void linePoolWaitsForPhysicalOutputCleanupAndMapsLateRuntimeFailure() throws Exception {
        IllegalStateException closeFailure = new IllegalStateException("line stdout close failed");
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(closeFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(new TrackingOutputStream(), stdout, new TrackingInputStream());
        DefaultLineSession worker =
                new DefaultLineSession(openSession(process, dispatcher), LineSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted(), "line stdout pump did not enter the blocked read");
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults().withWarmupSize(1).withCloseTimeout(CLOSE_TIMEOUT));
        try {
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(stdout.awaitCloseInvoked(), "line stdout physical close was not dispatched");

            PooledSessionException timeout = assertThrows(PooledSessionException.class, pool::close);

            assertEquals(PooledSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            assertFalse(eventual.isDone());
            assertTrue(dispatcher.outstandingCount() > 0);

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> eventual.get(1, TimeUnit.SECONDS));
            PooledSessionException workerFailure = (PooledSessionException) observed.getCause();
            assertEquals(PooledSessionException.Reason.WORKER_FAILED, workerFailure.reason());
            assertSame(closeFailure, workerFailure.getCause());
            PooledSessionException repeated = assertThrows(PooledSessionException.class, pool::close);
            assertEquals(PooledSessionException.Reason.WORKER_FAILED, repeated.reason());
            assertSame(closeFailure, repeated.getCause());
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolWaitsForPhysicalOutputCleanupAndPreservesLateError() throws Exception {
        AssertionError closeFailure = new AssertionError("protocol stdout close failed");
        BlockingReadFailingCloseInputStream stdout = new BlockingReadFailingCloseInputStream(closeFailure);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TestProcess process = new TestProcess(new TrackingOutputStream(), stdout, new TrackingInputStream());
        DefaultProtocolSession<String, String> worker = new DefaultProtocolSession<>(
                openSession(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        assertTrue(stdout.awaitReadStarted(), "protocol stdout pump did not enter the blocked read");
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> worker,
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults()
                        .withWarmupSize(1)
                        .withCloseTimeout(CLOSE_TIMEOUT));
        try {
            CompletableFuture<Void> eventual = pool.closeAsync();
            assertTrue(stdout.awaitCloseInvoked(), "protocol stdout physical close was not dispatched");

            PooledSessionException timeout = assertThrows(PooledSessionException.class, pool::close);

            assertEquals(PooledSessionException.Reason.DRAIN_TIMEOUT, timeout.reason());
            assertFalse(eventual.isDone());
            assertTrue(dispatcher.outstandingCount() > 0);

            stdout.releaseRead();
            assertTrue(stdout.awaitReadFinished());
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> eventual.get(1, TimeUnit.SECONDS));
            assertSame(closeFailure, observed.getCause());
            assertSame(closeFailure, assertThrows(AssertionError.class, pool::close));
            assertNoDispatcherLeak(dispatcher);
        } finally {
            stdout.releaseRead();
            process.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void linePoolPromotesLaterPhysicalErrorAndSuppressesPriorRuntimeExactlyOnce() throws Exception {
        IllegalStateException runtimeFailure = new IllegalStateException("first line worker close failed");
        AssertionError fatalFailure = new AssertionError("second line worker close failed fatally");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 4);
        TestProcess firstProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(runtimeFailure),
                new TrackingInputStream());
        TestProcess secondProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(fatalFailure),
                new TrackingInputStream());
        List<LineSession> workers = List.of(
                new DefaultLineSession(openSession(firstProcess, dispatcher), LineSessionSettings.defaults()),
                new DefaultLineSession(openSession(secondProcess, dispatcher), LineSessionSettings.defaults()));
        AtomicInteger workerIndex = new AtomicInteger();
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> workers.get(workerIndex.getAndIncrement()),
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults().withMaxSize(2).withWarmupSize(2));
        try {
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

            Throwable aggregate = observed.getCause();
            assertSame(fatalFailure, aggregate.getCause());
            assertSuppressedExactlyOnce(aggregate, runtimeFailure);
            assertEquals(0, fatalFailure.getSuppressed().length);
            assertSame(aggregate, assertThrows(Error.class, pool::close));
            assertNoDispatcherLeak(dispatcher, 6);
        } finally {
            firstProcess.complete(143);
            secondProcess.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolKeepsFirstPhysicalErrorAndSuppressesLaterRuntimeExactlyOnce() throws Exception {
        AssertionError fatalFailure = new AssertionError("first protocol worker close failed fatally");
        IllegalStateException runtimeFailure = new IllegalStateException("second protocol worker close failed");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 4);
        TestProcess firstProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(fatalFailure),
                new TrackingInputStream());
        TestProcess secondProcess = new TestProcess(
                new TrackingOutputStream(),
                new ImmediateFailingCloseInputStream(runtimeFailure),
                new TrackingInputStream());
        List<ProtocolSession<String, String>> workers = List.of(
                new DefaultProtocolSession<>(
                        openSession(firstProcess, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults()),
                new DefaultProtocolSession<>(
                        openSession(secondProcess, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults()));
        AtomicInteger workerIndex = new AtomicInteger();
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> workers.get(workerIndex.getAndIncrement()),
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults()
                        .withMaxSize(2)
                        .withWarmupSize(2));
        try {
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

            Throwable aggregate = observed.getCause();
            assertSame(fatalFailure, aggregate.getCause());
            assertSuppressedExactlyOnce(aggregate, runtimeFailure);
            assertEquals(0, fatalFailure.getSuppressed().length);
            assertSame(aggregate, assertThrows(Error.class, pool::close));
            assertNoDispatcherLeak(dispatcher, 6);
        } finally {
            firstProcess.complete(143);
            secondProcess.complete(143);
            pool.closeAsync().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
        }
    }

    private static void assertSuppressedExactlyOnce(Throwable primary, Throwable expected) {
        int matches = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                matches++;
            }
        }
        assertEquals(1, matches);
    }
}
