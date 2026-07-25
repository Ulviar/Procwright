/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class PooledWorkerOutputFailureAggregationTest extends DefaultSessionOutputCleanupTestSupport {

    @TestFactory
    Stream<DynamicTest> poolRetirementPreservesEitherOutputCloseFailure() {
        return Stream.of(OutputSource.values())
                .map(source -> DynamicTest.dynamicTest(
                        source + " pool retirement failure", () -> verifyPoolRetirementFailure(source)));
    }

    @Test
    void poolRetirementSuppressesDuplicatePhysicalFailureIdentity() throws Exception {
        AssertionError sharedFailure = new AssertionError("shared output close failed");
        verifyPoolFailureAggregation(sharedFailure, sharedFailure, sharedFailure);

        assertEquals(0, countIdentity(sharedFailure.getSuppressed(), sharedFailure));
    }

    @Test
    void poolRetirementDoesNotExpandCyclicPhysicalFailureGraph() throws Exception {
        IllegalStateException stdoutFailure = new IllegalStateException("stdout close failed");
        IllegalArgumentException stderrFailure = new IllegalArgumentException("stderr close failed");
        stdoutFailure.addSuppressed(stderrFailure);
        stderrFailure.addSuppressed(stdoutFailure);

        verifyPoolFailureAggregation(stdoutFailure, stderrFailure, stdoutFailure);

        assertEquals(1, countIdentity(stdoutFailure.getSuppressed(), stderrFailure));
        assertEquals(1, countIdentity(stderrFailure.getSuppressed(), stdoutFailure));
    }

    private static void verifyPoolRetirementFailure(OutputSource source) throws Exception {
        AssertionError closeFailure = new AssertionError(source + " close failed");
        InputStream stdout = source == OutputSource.STDOUT
                ? new ImmediateFailingCloseInputStream(closeFailure)
                : new TrackingInputStream();
        InputStream stderr = source == OutputSource.STDERR
                ? new ImmediateFailingCloseInputStream(closeFailure)
                : new TrackingInputStream();

        verifyPoolFailureAggregation(stdout, stderr, closeFailure);
    }

    private static void verifyPoolFailureAggregation(
            Throwable stdoutFailure, Throwable stderrFailure, Throwable expectedPrimary) throws Exception {
        verifyPoolFailureAggregation(
                new ImmediateFailingCloseInputStream(stdoutFailure),
                new ImmediateFailingCloseInputStream(stderrFailure),
                expectedPrimary);
    }

    private static void verifyPoolFailureAggregation(InputStream stdout, InputStream stderr, Throwable expectedPrimary)
            throws Exception {
        MatrixProcess process = new MatrixProcess(new TrackingOutputStream(), stdout, stderr);
        DefaultSession session = openSession(process, new BoundedCloseDispatcher(1, 2));
        WorkerPoolController<DefaultSession> pool = WorkerPoolController.fromSettings(
                () -> session,
                worker -> WorkerCloseSupport.closeOutcome(worker, worker.onExit(), worker.physicalOutputCleanup()),
                WorkerPoolSettings.defaults().withWarmupSize(1).withBackgroundReplenishment(false),
                PoolTestFailures.INSTANCE,
                "default session",
                "output-cleanup-pool-",
                System::nanoTime);
        try {
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> pool.closeAsync().get(1, TimeUnit.SECONDS));

            Throwable poolFailure = observed.getCause();
            if (poolFailure != expectedPrimary) {
                assertSame(expectedPrimary, poolFailure.getCause());
            }
            assertEquals(1, pool.metrics().failedWorkerCloses());
            assertEquals(1, pool.metrics().retired());
            assertEquals(0, pool.metrics().retiring());
            assertEquals(0, pool.metrics().size());
        } finally {
            process.complete(143);
            session.close();
            pool.closeAsync();
        }
    }

    private static int countIdentity(Throwable[] failures, Throwable expected) {
        return Math.toIntExact(java.util.Arrays.stream(failures)
                .filter(failure -> failure == expected)
                .count());
    }

    private enum OutputSource {
        STDOUT,
        STDERR
    }

    private enum PoolTestFailures implements WorkerPoolController.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException closed(String message) {
            return new IllegalStateException(message);
        }

        @Override
        public RuntimeException acquireTimeout(String message) {
            return new IllegalStateException(message);
        }

        @Override
        public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
            return new IllegalStateException(message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new IllegalStateException(message, cause);
        }

        @Override
        public RuntimeException retirementFailed(String message, Throwable cause) {
            return new IllegalStateException(message, cause);
        }
    }

    private static final class ImmediateFailingCloseInputStream extends InputStream {

        private final Throwable failure;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private ImmediateFailingCloseInputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            throwUnchecked(failure);
        }
    }

    private static void throwUnchecked(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }
}
