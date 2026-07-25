/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

final class WorkerRetirementTest {

    @Test
    void closeIsInitiatedAndCompletedExactlyOnce() {
        AtomicInteger initiations = new AtomicInteger();
        CompletableFuture<WorkerRetirement.Outcome> closeOutcome = new CompletableFuture<>();
        WorkerRetirement<String> retirement = retirement(worker -> {
            initiations.incrementAndGet();
            return closeOutcome;
        });

        retirement.initiate();
        CompletableFuture<WorkerRetirement.Outcome> first = retirement.outcome();
        CompletableFuture<WorkerRetirement.Outcome> second = retirement.outcome();
        closeOutcome.complete(WorkerRetirement.Outcome.success());

        assertSame(first, second);
        assertNull(first.join().failure());
        assertEquals(1, initiations.get());
    }

    @Test
    void concurrentOutcomeAccessInitiatesCloseOnce() throws Exception {
        int callers = 8;
        AtomicInteger initiations = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch otherCallsReturned = new CountDownLatch(callers - 1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReferenceArray<CompletableFuture<WorkerRetirement.Outcome>> outcomes =
                new AtomicReferenceArray<>(callers);
        WorkerRetirement<String> retirement = retirement(worker -> {
            initiations.incrementAndGet();
            try {
                assertTrue(otherCallsReturned.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting concurrent outcome access", interrupted);
            }
            return CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
        });
        Thread[] threads = new Thread[callers];

        for (int index = 0; index < callers; index++) {
            int outcomeIndex = index;
            threads[index] = new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            assertTrue(start.await(5, TimeUnit.SECONDS));
                            outcomes.set(outcomeIndex, retirement.outcome());
                        } catch (Throwable callFailure) {
                            failure.compareAndSet(null, callFailure);
                        } finally {
                            otherCallsReturned.countDown();
                        }
                    },
                    "worker-retirement-race-" + index);
            threads[index].start();
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(6));
            assertFalse(thread.isAlive());
        }

        assertNull(failure.get());
        CompletableFuture<WorkerRetirement.Outcome> first = outcomes.get(0);
        for (int index = 1; index < callers; index++) {
            assertSame(first, outcomes.get(index));
        }
        assertNull(first.join().failure());
        assertEquals(1, initiations.get());
    }

    @Test
    void initiationFailureBecomesAStableOutcome() {
        AssertionError expected = new AssertionError("close failed");
        WorkerRetirement<String> retirement = retirement(worker -> {
            throw expected;
        });

        assertSame(expected, retirement.outcome().join().failure());
        assertSame(expected, retirement.outcome().join().failure());
    }

    @Test
    void prematureInitiationDoesNotPoisonRetirement() {
        WorkerRetirement<String> retirement =
                new WorkerRetirement<>(worker -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success()));

        assertThrows(NullPointerException.class, retirement::initiate);

        retirement.accept("worker");
        CompletableFuture<WorkerRetirement.Outcome> outcome = retirement.outcome();
        assertTrue(outcome.isDone());
        assertNull(outcome.join().failure());
    }

    @Test
    void reentrantOutcomeAccessCannotInitiateCloseTwice() {
        AtomicInteger initiations = new AtomicInteger();
        AtomicReference<WorkerRetirement<String>> owner = new AtomicReference<>();
        AtomicReference<CompletableFuture<WorkerRetirement.Outcome>> reentrantOutcome = new AtomicReference<>();
        WorkerRetirement<String> retirement = retirement(worker -> {
            initiations.incrementAndGet();
            reentrantOutcome.set(owner.get().outcome());
            return CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
        });
        owner.set(retirement);

        CompletableFuture<WorkerRetirement.Outcome> outcome = retirement.outcome();

        assertSame(outcome, reentrantOutcome.get());
        assertNull(outcome.join().failure());
        assertEquals(1, initiations.get());
    }

    @Test
    void exceptionalFutureIsNormalizedToCloseOutcome() {
        IllegalStateException expected = new IllegalStateException("close failed");
        WorkerRetirement<String> retirement = retirement(worker -> CompletableFuture.failedFuture(expected));

        assertSame(expected, retirement.outcome().join().failure());
    }

    @Test
    void nullFutureIsNormalizedToStableFailure() {
        WorkerRetirement<String> retirement = retirement(worker -> null);

        Throwable first = retirement.outcome().join().failure();
        Throwable second = retirement.outcome().join().failure();

        assertSame(first, second);
        assertEquals("worker close action returned null future", first.getMessage());
    }

    @Test
    void nullOutcomeIsNormalizedToStableFailure() {
        WorkerRetirement<String> retirement = retirement(worker -> CompletableFuture.completedFuture(null));

        Throwable first = retirement.outcome().join().failure();
        Throwable second = retirement.outcome().join().failure();

        assertSame(first, second);
        assertEquals("worker close future returned null", first.getMessage());
    }

    private static WorkerRetirement<String> retirement(WorkerRetirement.Action<String> action) {
        WorkerRetirement<String> retirement = new WorkerRetirement<>(action);
        retirement.accept("worker");
        return retirement;
    }
}
