/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SessionExitBarrierTest {

    @Test
    void waitsForProcessOutputAndEveryRegisteredHelper() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        SessionExitBarrier.Registration first = barrier.registerHelper();
        SessionExitBarrier.Registration second = barrier.registerHelper();
        barrier.observe(process, output);
        CompletableFuture<SessionExit> view = barrier.view();
        SessionExit expected = new SessionExit(OptionalInt.of(3), false);

        process.complete(success(expected));
        output.complete(outcome());
        first.complete();
        assertFalse(view.isDone());

        second.rollback();
        assertSame(expected, view.get(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> publisher.ownerCount() == 0));
    }

    @Test
    void lifecyclePhysicalFailureRequiresAFailureAndReportingTargetState() {
        BoundedFailureReporter.FailureTarget target = BoundedFailureReporter.captureFailureTarget();

        assertThrows(
                NullPointerException.class,
                () -> new SessionOutputCleanup.PhysicalClose.LifecycleFailure(null, Optional.of(target)));
        assertThrows(
                NullPointerException.class,
                () -> new SessionOutputCleanup.PhysicalClose.LifecycleFailure(
                        new IllegalStateException("failure"), null));
    }

    @Test
    void combinesProcessAndInlineOutputFailureBeforePublication() {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        AssertionError processFailure = new AssertionError("process");
        IllegalStateException outputFailure = new IllegalStateException("output");
        barrier.observe(process, output);

        process.complete(failure(processFailure));
        output.complete(inlineFailure(outputFailure));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));
        assertInstanceOf(Error.class, observed.getCause());
        assertSame(processFailure, observed.getCause().getCause());
        assertSame(outputFailure, observed.getCause().getSuppressed()[0]);
        assertEquals(0, processFailure.getSuppressed().length);
        assertTrue(eventually(() -> publisher.ownerCount() == 0));
    }

    @Test
    void failureAggregationDoesNotDependOnEitherFailureMonitor() throws Exception {
        verifyFailureAggregationWhileHolding(true);
        verifyFailureAggregationWhileHolding(false);
    }

    @Test
    void processFailureRemainsPrimaryWhenTheLaterOutputFailureIsAnError() {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        IllegalStateException processFailure = new IllegalStateException("process");
        AssertionError outputFailure = new AssertionError("output");
        barrier.observe(process, output);
        process.complete(failure(processFailure));
        output.complete(physicalFailure(outputFailure));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));

        assertTrue(observed.getCause() instanceof RuntimeException);
        assertSame(processFailure, observed.getCause().getCause());
        assertSame(outputFailure, observed.getCause().getSuppressed()[0]);
        assertEquals(0, processFailure.getSuppressed().length);
        assertEquals(0, outputFailure.getSuppressed().length);
    }

    @Test
    void finalAggregationUsesSourceIdentitiesOnceEvenWhenCleanupObservedTheSameFailure() {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        AssertionError processFailure = new AssertionError("process");
        IllegalStateException outputFailure = new IllegalStateException("output");
        barrier.observe(process, output);

        process.complete(new SessionTermination.Outcome(null, List.of(processFailure, outputFailure)));
        output.complete(new SessionOutputCleanup.Outcome(
                List.of(outputFailure),
                new SessionOutputCleanup.PhysicalClose.LifecycleFailure(
                        outputFailure, Optional.of(BoundedFailureReporter.captureFailureTarget()))));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));
        assertSame(processFailure, observed.getCause().getCause());
        assertEquals(List.of(outputFailure), List.of(observed.getCause().getSuppressed()));
        assertEquals(0, processFailure.getSuppressed().length);
        assertEquals(0, outputFailure.getSuppressed().length);
    }

    @Test
    void unavailableBestEffortReporterDoesNotReplaceProcessSuccess() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);
        barrier.observe(process, output);

        process.complete(success(expected));
        IllegalStateException physicalFailure = new IllegalStateException("physical close");
        output.complete(new SessionOutputCleanup.Outcome(
                List.of(), new SessionOutputCleanup.PhysicalClose.LifecycleFailure(physicalFailure, Optional.empty())));

        assertSame(expected, barrier.view().get(1, TimeUnit.SECONDS));
    }

    @Test
    void physicalAggregateContributesItsSourceIdentitiesToTerminalAggregation() {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        IllegalStateException processFailure = new IllegalStateException("process");
        AssertionError stdoutFailure = new AssertionError("stdout");
        IllegalArgumentException stderrFailure = new IllegalArgumentException("stderr");
        Throwable physicalFailure =
                FailureAggregation.combine(stdoutFailure, stderrFailure, "both output streams failed");
        barrier.observe(process, output);

        process.complete(failure(processFailure));
        output.complete(new SessionOutputCleanup.Outcome(
                List.of(), new SessionOutputCleanup.PhysicalClose.LifecycleFailure(physicalFailure, Optional.empty())));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));
        assertSame(processFailure, observed.getCause().getCause());
        assertEquals(
                List.of(stdoutFailure, stderrFailure),
                List.of(observed.getCause().getSuppressed()));
    }

    private static void verifyFailureAggregationWhileHolding(boolean holdProcessFailure) throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        AssertionError processFailure = new AssertionError("process");
        IllegalStateException outputFailure = new IllegalStateException("output");
        barrier.observe(process, output);
        process.complete(failure(processFailure));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var monitor = hold(holdProcessFailure ? processFailure : outputFailure)) {
            monitor.verifyHeld();
            executor.submit(() -> output.complete(physicalFailure(outputFailure)))
                    .get(1, TimeUnit.SECONDS);
            ExecutionException observed =
                    assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));
            assertSame(processFailure, observed.getCause().getCause());
            assertSame(outputFailure, observed.getCause().getSuppressed()[0]);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertEquals(0, processFailure.getSuppressed().length);
        assertEquals(0, outputFailure.getSuppressed().length);
    }

    private static SessionExitBarrier barrier(BoundedLifecyclePublisher publisher) {
        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(1);
        BoundedLifecyclePublisher.Permit permit = reservation.takePermit();
        reservation.release();
        return new SessionExitBarrier(permit);
    }

    private static SessionOutputCleanup.Outcome outcome() {
        return new SessionOutputCleanup.Outcome(List.of(), SessionOutputCleanup.PhysicalClose.Success.INSTANCE);
    }

    private static SessionOutputCleanup.Outcome inlineFailure(Throwable failure) {
        return new SessionOutputCleanup.Outcome(List.of(failure), SessionOutputCleanup.PhysicalClose.Success.INSTANCE);
    }

    private static SessionOutputCleanup.Outcome physicalFailure(Throwable failure) {
        return new SessionOutputCleanup.Outcome(
                List.of(),
                new SessionOutputCleanup.PhysicalClose.LifecycleFailure(
                        failure, Optional.of(BoundedFailureReporter.captureFailureTarget())));
    }

    private static SessionTermination.Outcome success(SessionExit result) {
        return new SessionTermination.Outcome(result, List.of());
    }

    private static SessionTermination.Outcome failure(Throwable failure) {
        return new SessionTermination.Outcome(null, List.of(failure));
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() - deadline < 0) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }
}
