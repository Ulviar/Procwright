/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ulviar.procwright.internal.Threading;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerCloseSupportTest {

    @Test
    void closeFailureKeepsItsIdentity() throws Exception {
        Exception expected = new Exception("worker close failed");

        CompletableFuture<WorkerRetirement.Outcome> retirement = WorkerCloseSupport.closeOutcome(
                () -> {
                    throw expected;
                },
                CompletableFuture.completedFuture(null),
                (prefix, owner) -> {
                    owner.run();
                    return Thread.currentThread();
                });

        assertSame(expected, retirement.get(1, TimeUnit.SECONDS).failure());
    }

    @Test
    void directlyThrownCompletionExceptionKeepsItsIdentity() throws Exception {
        CompletionException expected = new CompletionException(new IllegalStateException("worker close failed"));

        CompletableFuture<WorkerRetirement.Outcome> retirement = WorkerCloseSupport.closeOutcome(
                () -> {
                    throw expected;
                },
                CompletableFuture.completedFuture(null),
                (prefix, owner) -> {
                    owner.run();
                    return Thread.currentThread();
                });

        assertSame(expected, retirement.get(1, TimeUnit.SECONDS).failure());
    }

    @Test
    void terminalFailureIsSettlementRatherThanRetirementFailure() throws Exception {
        CompletionException expected = new CompletionException(new IllegalStateException("terminal failed"));

        CompletableFuture<WorkerRetirement.Outcome> retirement =
                WorkerCloseSupport.closeOutcome(() -> {}, CompletableFuture.failedFuture(expected), (prefix, owner) -> {
                    owner.run();
                    return Thread.currentThread();
                });

        assertNull(retirement.get(1, TimeUnit.SECONDS).failure());
    }

    @Test
    void runtimeStarterFailureRunsFallbackAndPreservesExactOutcome() throws Exception {
        assertStarterFailure(new IllegalStateException("worker close owner launch failed"), false);
    }

    @Test
    void errorStarterFailureRunsFallbackAndPreservesExactOutcome() throws Exception {
        assertStarterFailure(new AssertionError("worker close owner launch failed fatally"), false);
    }

    @Test
    void starterFailureAfterInlineExecutionDoesNotRunCloseTwice() throws Exception {
        assertStarterFailure(new IllegalStateException("starter failed after running close"), true);
    }

    @Test
    void starterFailureAfterLaunchingThreadStillRunsCloseOnce() throws Exception {
        IllegalStateException expected = new IllegalStateException("starter failed after launching close");
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicInteger closeRuns = new AtomicInteger();
        AtomicReference<Thread> ownerThread = new AtomicReference<>();

        CompletableFuture<WorkerRetirement.Outcome> retirement = WorkerCloseSupport.closeOutcome(
                closeRuns::incrementAndGet, CompletableFuture.completedFuture(null), (prefix, owner) -> {
                    ownerThread.set(Threading.start(prefix, () -> {
                        awaitIgnoringInterrupt(releaseOwner);
                        owner.run();
                    }));
                    throw expected;
                });

        WorkerRetirement.Outcome outcome = retirement.get(1, TimeUnit.SECONDS);
        assertSame(expected, outcome.failure());
        assertEquals(1, closeRuns.get());

        releaseOwner.countDown();
        ownerThread.get().join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(ownerThread.get().isAlive());
        assertEquals(1, closeRuns.get());
    }

    private static void assertStarterFailure(Throwable expected, boolean runBeforeFailure) throws Exception {
        AtomicInteger closeRuns = new AtomicInteger();

        CompletableFuture<WorkerRetirement.Outcome> retirement = WorkerCloseSupport.closeOutcome(
                closeRuns::incrementAndGet, CompletableFuture.completedFuture(null), (prefix, owner) -> {
                    if (runBeforeFailure) {
                        owner.run();
                    }
                    return throwUnchecked(expected);
                });

        WorkerRetirement.Outcome outcome = retirement.get(1, TimeUnit.SECONDS);
        assertSame(expected, outcome.failure());
        assertEquals(1, closeRuns.get());
    }

    private static <T> T throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
    }
}
