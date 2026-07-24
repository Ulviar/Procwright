/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkerRetirementTest {

    @Test
    void closeIsInitiatedAndObservedExactlyOnce() {
        AtomicInteger initiations = new AtomicInteger();
        CompletableFuture<WorkerRetirement.Outcome> observed = new CompletableFuture<>();
        WorkerRetirement<String> retirement = new WorkerRetirement<>("worker", admission(), (worker, admission) -> {
            initiations.incrementAndGet();
            return () -> observed;
        });

        retirement.initiate();
        CompletableFuture<WorkerRetirement.Outcome> first = retirement.outcome();
        CompletableFuture<WorkerRetirement.Outcome> second = retirement.outcome();
        observed.complete(WorkerRetirement.Outcome.success());

        assertSame(first, second);
        assertNull(first.join().failure());
        assertEquals(1, initiations.get());
    }

    @Test
    void initiationFailureBecomesAStableOutcome() {
        AssertionError expected = new AssertionError("close failed");
        WorkerRetirement<String> retirement = new WorkerRetirement<>("worker", admission(), (worker, admission) -> {
            throw expected;
        });

        assertSame(expected, retirement.outcome().join().failure());
        assertSame(expected, retirement.outcome().join().failure());
    }

    @Test
    void exceptionalObservationIsNormalizedToCloseOutcome() {
        IllegalStateException expected = new IllegalStateException("observation failed");
        WorkerRetirement<String> retirement = new WorkerRetirement<>(
                "worker", admission(), (worker, admission) -> () -> CompletableFuture.failedFuture(expected));

        assertSame(expected, retirement.outcome().join().failure());
    }

    @Test
    void nullOutcomeIsNormalizedToStableFailure() {
        WorkerRetirement<String> retirement = new WorkerRetirement<>(
                "worker", admission(), (worker, admission) -> () -> CompletableFuture.completedFuture(null));

        Throwable first = retirement.outcome().join().failure();
        Throwable second = retirement.outcome().join().failure();

        assertSame(first, second);
        assertEquals("worker close observation returned null", first.getMessage());
    }

    private static PoolLifecycleDispatcher.Admission admission() {
        PoolLifecycleDispatcher.Admission admission = new PoolLifecycleDispatcher.AdmissionPool(1).tryAcquire();
        if (admission == null) {
            throw new AssertionError("test admission unavailable");
        }
        return admission;
    }
}
