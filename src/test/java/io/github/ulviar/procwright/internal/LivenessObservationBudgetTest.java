/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class LivenessObservationBudgetTest {

    @Test
    void lifecycleLimitedPreOperationExhaustionReturnsNoBudgetAndSkipsOperation() {
        AtomicLong nanoTime = new AtomicLong(100);
        LivenessObservationBudget budget = LivenessObservationBudget.fromRemainingLifecycle(
                Duration.ofNanos(20), Duration.ofNanos(50), nanoTime::get);
        AtomicInteger operations = new AtomicInteger();
        nanoTime.set(120);

        Optional<Duration> remaining = budget.remainingOperationBudget("procwright-provider-liveness-");
        remaining.ifPresent(ignored -> operations.incrementAndGet());

        assertTrue(remaining.isEmpty());
        assertEquals(0, operations.get());
    }

    @Test
    void providerLimitedPreOperationExhaustionThrowsTypedDeadlineFailure() {
        AtomicLong nanoTime = new AtomicLong(100);
        LivenessObservationBudget budget = LivenessObservationBudget.fromRemainingLifecycle(
                Duration.ofNanos(50), Duration.ofNanos(20), nanoTime::get);
        nanoTime.set(120);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class, () -> budget.remainingOperationBudget("procwright-provider-exit-"));

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertTrue(ProcessTreeScanner.causedByOperationDeadline(failure));
        assertEquals(
                "Provider process operation exceeded its bounded deadline: procwright-provider-exit-",
                failure.getCause().getMessage());
    }

    @Test
    void positiveRemainingOperationBudgetUsesSelectedDeadline() {
        AtomicLong nanoTime = new AtomicLong(100);
        LivenessObservationBudget budget = LivenessObservationBudget.fromRemainingLifecycle(
                Duration.ofNanos(50), Duration.ofNanos(20), nanoTime::get);
        nanoTime.set(105);

        Optional<Duration> remaining = budget.remainingOperationBudget("procwright-provider-liveness-");

        assertTrue(remaining.isPresent());
        assertEquals(Duration.ofNanos(15), remaining.orElseThrow());
        assertFalse(remaining.orElseThrow().isZero());
    }
}
