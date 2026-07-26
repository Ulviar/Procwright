/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PoolTerminationTest {

    @Test
    void successfulConstructionDrainsBufferedReportsAndRoutesLaterFailures() {
        PoolTermination termination = termination();
        FailureReport early = report("early");
        FailureReport late = report("late");

        assertNull(termination.routeLateFailure(early));
        PoolTermination.ConstructionResult result = termination.finishConstruction();

        PoolTermination.ConstructionSucceeded success =
                assertInstanceOf(PoolTermination.ConstructionSucceeded.class, result);
        assertEquals(List.of(early), success.reports());
        assertSame(late, termination.routeLateFailure(late));
        assertThrows(IllegalStateException.class, termination::finishConstruction);
    }

    @Test
    void closingConstructionKeepsTheFirstTerminalFailure() {
        PoolTermination termination = termination();
        FailureReport pending = report("pending");
        IllegalStateException runtimeFailure = new IllegalStateException("runtime failure");
        AssertionError fatalFailure = new AssertionError("fatal failure");
        termination.routeLateFailure(pending);

        assertSame(PoolTermination.FailureDisposition.TERMINAL, termination.beginClosing(runtimeFailure));
        assertSame(PoolTermination.FailureDisposition.REPORT, termination.beginClosing(fatalFailure));
        PoolTermination.ConstructionResult result = termination.finishConstruction();

        assertTrue(termination.closing());
        PoolTermination.ConstructionFailed failed = assertInstanceOf(PoolTermination.ConstructionFailed.class, result);
        assertSame(runtimeFailure, failed.failure());
        assertEquals(List.of(pending), failed.reports());
        assertTrue(termination.failConstruction().isEmpty());
        FailureReport afterDecision = report("after decision");
        assertSame(afterDecision, termination.routeLateFailure(afterDecision));
    }

    @Test
    void closingWithoutACauseStillRejectsConstruction() {
        PoolTermination termination = termination();

        termination.beginClosing(null);
        PoolTermination.ConstructionResult result = termination.finishConstruction();

        PoolTermination.ConstructionFailed failed = assertInstanceOf(PoolTermination.ConstructionFailed.class, result);
        assertNull(failed.failure());
        assertTrue(failed.reports().isEmpty());
    }

    @Test
    void workerCloseFailureRoutingDependsOnlyOnConstructionDecision() {
        PoolTermination failed = termination();
        FailureReport beforeFailure = report("before failure");
        FailureReport afterFailure = report("after failure");

        assertNull(failed.routeWorkerCloseFailure(beforeFailure));
        assertEquals(List.of(beforeFailure), failed.failConstruction());
        assertSame(afterFailure, failed.routeWorkerCloseFailure(afterFailure));

        PoolTermination committed = termination();
        committed.finishConstruction();
        FailureReport terminal = report("committed");
        committed.beginClosing(terminal.failure());
        assertNull(committed.routeWorkerCloseFailure(terminal));
        FailureReport additional = report("additional");
        assertSame(additional, committed.routeWorkerCloseFailure(additional));
    }

    @Test
    void drainCanBeClaimedOnlyAfterClosingAndTheLastWorkerLeaves() throws Exception {
        PoolTermination termination = termination();
        CompletableFuture<Void> cancelled = termination.view();
        CompletableFuture<Void> observed = termination.view();
        IllegalStateException closeFailure = new IllegalStateException("close failed");

        assertNull(termination.claimDrainIfReady(0));
        termination.beginClosing(closeFailure);
        assertNull(termination.claimDrainIfReady(1));
        PoolTermination.Publication publication = termination.claimDrainIfReady(0);
        assertSame(closeFailure, publication.failure());
        assertNull(termination.claimDrainIfReady(0));

        assertTrue(cancelled.cancel(true));
        publication.publish();
        assertThrows(IllegalStateException.class, publication::publish);

        ExecutionException result = assertThrows(ExecutionException.class, () -> observed.get(1, TimeUnit.SECONDS));
        assertSame(closeFailure, result.getCause());
        assertTrue(cancelled.isCancelled());
        assertFalse(observed.isCancelled());
    }

    @Test
    void firstFailureIsTerminalAndLaterFailuresAreReported() {
        PoolTermination termination = termination();
        AssertionError terminal = new AssertionError("terminal");
        AssertionError late = new AssertionError("late");

        assertSame(PoolTermination.FailureDisposition.TERMINAL, termination.beginClosing(terminal));
        assertSame(PoolTermination.FailureDisposition.NONE, termination.beginClosing(terminal));
        assertTrue(termination.claimDrainIfReady(0) != null);
        assertSame(PoolTermination.FailureDisposition.REPORT, termination.beginClosing(late));
    }

    @Test
    void liveWorkerCountCannotBeNegative() {
        PoolTermination termination = termination();

        assertThrows(IllegalArgumentException.class, () -> termination.claimDrainIfReady(-1));
    }

    private static PoolTermination termination() {
        return new PoolTermination();
    }

    private static FailureReport report(String message) {
        return new FailureReport(
                BoundedFailureReporter.captureFailureTarget(Thread.currentThread()),
                new IllegalStateException(message));
    }
}
