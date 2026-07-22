/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PoolCloseSupportTest {

    @Test
    void awaitMapsTimeoutAndInterruptionWithoutMutatingCleanup() {
        CompletableFuture<Void> cleanup = new CompletableFuture<>();

        PoolFailure timeout = assertThrows(
                PoolFailure.class, () -> PoolCloseSupport.await(cleanup, Duration.ofNanos(1), Failures.INSTANCE));
        assertEquals(FailureKind.DRAIN_TIMEOUT, timeout.kind);
        assertFalse(cleanup.isDone());

        PoolFailure interrupted;
        try {
            Thread.currentThread().interrupt();
            interrupted = assertThrows(
                    PoolFailure.class, () -> PoolCloseSupport.await(cleanup, Duration.ofSeconds(1), Failures.INSTANCE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(FailureKind.INTERRUPTED, interrupted.kind);
        assertTrue(interrupted.getCause() instanceof InterruptedException);
        assertFalse(cleanup.isDone());
    }

    @Test
    void workerFailureIsTypedAndKeepsSuppressedFailuresOnItsCause() {
        IllegalStateException first = new IllegalStateException("first close failed");
        IllegalArgumentException second = new IllegalArgumentException("second close failed");
        first.addSuppressed(second);
        CompletableFuture<Void> cleanup = CompletableFuture.failedFuture(first);

        PoolFailure failure = assertThrows(
                PoolFailure.class, () -> PoolCloseSupport.await(cleanup, Duration.ofSeconds(1), Failures.INSTANCE));

        assertEquals(FailureKind.WORKER_FAILED, failure.kind);
        assertSame(first, failure.getCause());
        assertSame(second, failure.getCause().getSuppressed()[0]);
    }

    @Test
    void fatalWorkerFailureKeepsErrorPrecedence() {
        AssertionError fatal = new AssertionError("fatal close failure");
        CompletableFuture<Void> cleanup = CompletableFuture.failedFuture(fatal);

        AssertionError observed = assertThrows(
                AssertionError.class, () -> PoolCloseSupport.await(cleanup, Duration.ofSeconds(1), Failures.INSTANCE));

        assertSame(fatal, observed);
    }

    @Test
    void asyncViewsAreTypedAndCancellationIsolated() throws Exception {
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        CompletableFuture<Void> cancelled = PoolCloseSupport.asyncView(cleanup, Failures.INSTANCE);
        CompletableFuture<Void> externallyCompleted = PoolCloseSupport.asyncView(cleanup, Failures.INSTANCE);
        CompletableFuture<Void> observed = PoolCloseSupport.asyncView(cleanup, Failures.INSTANCE);

        assertTrue(cancelled.cancel(true));
        assertTrue(externallyCompleted.complete(null));
        assertFalse(cleanup.isCancelled());
        assertFalse(cleanup.isDone());
        assertFalse(observed.isCancelled());

        IllegalStateException cause = new IllegalStateException("close failed");
        cleanup.completeExceptionally(cause);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> observed.get(1, TimeUnit.SECONDS));
        PoolFailure typed = (PoolFailure) failure.getCause();
        assertEquals(FailureKind.WORKER_FAILED, typed.kind);
        assertSame(cause, typed.getCause());
    }

    private enum FailureKind {
        DRAIN_TIMEOUT,
        INTERRUPTED,
        WORKER_FAILED
    }

    @SuppressWarnings("serial")
    private static final class PoolFailure extends RuntimeException {

        private final FailureKind kind;

        private PoolFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    private enum Failures implements PoolCloseSupport.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException drainTimeout(Duration timeout) {
            return new PoolFailure(FailureKind.DRAIN_TIMEOUT, "timed out after " + timeout, null);
        }

        @Override
        public RuntimeException interrupted(InterruptedException cause) {
            return new PoolFailure(FailureKind.INTERRUPTED, "interrupted", cause);
        }

        @Override
        public RuntimeException workerFailed(Throwable cause) {
            return new PoolFailure(FailureKind.WORKER_FAILED, "worker failed", cause);
        }
    }
}
