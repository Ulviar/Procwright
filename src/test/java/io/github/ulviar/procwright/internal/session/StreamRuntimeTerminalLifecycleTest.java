/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class StreamRuntimeTerminalLifecycleTest extends StreamRuntimeTerminalLifecycleTestSupport {

    @Test
    void outputDecoderErrorIsTerminalByIdentityAndPreventsLaterListenerCallsForEitherPump() throws Exception {
        for (String fatalSource : List.of("stdout", "stderr")) {
            AssertionError fatalError = new AssertionError("fatal " + fatalSource + " decoder failure");
            Charset charset = new ThreadSelectedFatalDecoderCharset(fatalSource, fatalError);
            GatedByteInputStream gatedOther = new GatedByteInputStream((byte) 'x');
            CloseTrackingInputStream fatalInput = new CloseTrackingInputStream(new byte[] {1});
            InputStream stdout = fatalSource.equals("stdout") ? fatalInput : gatedOther;
            InputStream stderr = fatalSource.equals("stderr") ? fatalInput : gatedOther;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            AtomicInteger listenerCalls = new AtomicInteger();
            DefaultSession rawSession = session(process);
            StreamSession stream = new DefaultStreamSession(
                    rawSession, plan(charset, 16, chunk -> listenerCalls.incrementAndGet()), diagnostics());
            try {
                assertTrue(process.awaitDestroyed());
                gatedOther.release();

                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                assertSame(fatalError, failure.getCause());
                assertEquals(0, listenerCalls.get(), "no listener invocation may begin after terminal selection");
                assertFalse(process.isAlive());
            } finally {
                gatedOther.release();
                stream.close();
            }
        }
    }

    @Test
    void typedOutputFailureWinsWhenItOccursBeforeFatalError() throws Exception {
        assertTypedAndFatalOutputFailuresAreArbitrated(true);
    }

    @Test
    void fatalErrorWinsWhenItOccursBeforeTypedOutputFailure() throws Exception {
        assertTypedAndFatalOutputFailuresAreArbitrated(false);
    }

    @Test
    void closeStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
        ControllableProcess process =
                new ControllableProcess(stdout, stderr, null, OutputStream.nullOutputStream(), processAlive);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> close = null;
        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            close = executor.submit(stream::close);

            assertTrue(process.awaitDestroyed(), "process cleanup must precede helper output closure");
            close.get(1, TimeUnit.SECONDS);
            assertTrue(rawSession.terminationPublished());
            assertFalse(rawSession.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());
            assertTrue(stdout.destroyedBeforeClose());
            assertTrue(stderr.destroyedBeforeClose());
            assertFalse(stdout.closeCompleted());
            assertFalse(stderr.closeCompleted());
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            if (close != null) {
                close.get(1, TimeUnit.SECONDS);
            }
            stream.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(stdout.awaitCloseCompleted());
        assertTrue(stderr.awaitCloseCompleted());
        rawSession.onExit().get(1, TimeUnit.SECONDS);
        stream.onExit().get(1, TimeUnit.SECONDS);
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

    @Test
    void ordinaryCloseWinsAndReportsLaterFatalAndPhysicalCloseFailuresByIdentity() throws Exception {
        AssertionError fatalFailure = new AssertionError("fatal pump failure after close");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedFailureInputStream fatalReads = new GatedFailureInputStream(fatalFailure);
        CloseFailingInputStream stdout = new CloseFailingInputStream(fatalReads, stdoutCloseFailure);
        CloseFailingInputStream stderr = new CloseFailingInputStream(InputStream.nullInputStream(), stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr, null);
        DefaultSession rawSession = session(process);
        CopyOnWriteArrayList<Throwable> reported = new CopyOnWriteArrayList<>();
        CountDownLatch reports = new CountDownLatch(3);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            reported.add(failure);
            reports.countDown();
        });
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(fatalReads.awaitReadEntered());

            stream.close();
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());

            fatalReads.release();
            assertTrue(fatalReads.awaitThrow());
            var exit = stream.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.closed());
            assertFalse(exit.timedOut());
            assertTrue(reports.await(1, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    reported.stream().filter(failure -> failure == fatalFailure).count());
            assertEquals(
                    1,
                    reported.stream()
                            .filter(failure -> failure == stdoutCloseFailure)
                            .count());
            assertEquals(
                    1,
                    reported.stream()
                            .filter(failure -> failure == stderrCloseFailure)
                            .count());
            assertEquals(0, fatalFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            fatalReads.release();
            stream.close();
            rawSession.close();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void processFailureHasStableReason() throws Exception {
        IllegalStateException processFailure = new IllegalStateException("wait failed");
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(processFailure, streamFailure.getCause());
        } finally {
            process.releaseWaitFailure();
            stream.close();
        }
    }

    @Test
    void processWaitErrorIsTerminalByIdentity() throws Exception {
        AssertionError processFailure = new AssertionError("fatal wait failure");
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        CountDownLatch uncaughtReported = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((ignored, failure) -> {
            reportedFailure.compareAndSet(null, failure);
            uncaughtReported.countDown();
        });
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            assertSame(processFailure, failure.getCause());
            assertTrue(uncaughtReported.await(1, TimeUnit.SECONDS));
            assertSame(processFailure, reportedFailure.get());
            assertFalse(process.isAlive());
        } finally {
            process.releaseWaitFailure();
            stream.close();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void hostileCompletionCauseAccessorStillFailStopsAndCompletesStreamExit() throws Exception {
        AssertionError causeAccessFailure = new AssertionError("hostile getCause");
        HostileCompletionException processFailure = new HostileCompletionException(causeAccessFailure);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());
            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(processFailure, streamFailure.getCause());
            assertFalse(process.isAlive());
        } finally {
            process.releaseWaitFailure();
            stream.close();
        }
    }
}
