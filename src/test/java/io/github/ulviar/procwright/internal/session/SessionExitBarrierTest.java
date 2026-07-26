/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SessionExitBarrierTest {

    @Test
    void waitsForProcessOutputAndRegisteredHelpers() throws Exception {
        SessionExitBarrier barrier = new SessionExitBarrier();
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        SessionExitBarrier.Registration helper = barrier.registerHelper();
        barrier.observe(process, output);
        CompletableFuture<SessionExit> view = barrier.view();
        SessionExit expected = new SessionExit(OptionalInt.of(3), false);

        process.complete(success(expected));
        output.complete(outputSuccess());
        assertFalse(view.isDone());

        helper.complete();
        assertSame(expected, view.get(1, TimeUnit.SECONDS));
    }

    @Test
    void settlementOrderDoesNotChangeCompletionOrReentrantObservation() {
        for (boolean processFirst : new boolean[] {true, false}) {
            SessionExitBarrier barrier = new SessionExitBarrier();
            CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
            CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
            SessionExit expected = new SessionExit(OptionalInt.of(3), false);
            barrier.observe(process, output);
            CompletableFuture<SessionExit> reentrantView = barrier.view().thenApply(result -> {
                assertSame(result, barrier.view().join());
                return result;
            });

            if (processFirst) {
                process.complete(success(expected));
                output.complete(outputSuccess());
            } else {
                output.complete(outputSuccess());
                process.complete(success(expected));
            }

            assertSame(expected, reentrantView.join());
        }
    }

    @Test
    void combinesProcessAndLogicalOutputFailures() {
        SessionExitBarrier barrier = new SessionExitBarrier();
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        IllegalStateException processFailure = new IllegalStateException("process");
        AssertionError outputFailure = new AssertionError("output");
        barrier.observe(process, output);

        process.complete(failure(processFailure));
        output.complete(new SessionOutputCleanup.Outcome(
                List.of(outputFailure), SessionOutputCleanup.PhysicalClose.Success.INSTANCE));

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));
        assertEquals(List.of(processFailure, outputFailure), FailureAggregation.sources(observed.getCause()));
    }

    @Test
    void cancellingOneViewDoesNotCancelOtherObservers() throws Exception {
        SessionExitBarrier barrier = new SessionExitBarrier();
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        CompletableFuture<SessionExit> cancelled = barrier.view();
        CompletableFuture<SessionExit> observed = barrier.view();
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);
        barrier.observe(process, output);

        assertTrue(cancelled.cancel(false));
        process.complete(success(expected));
        output.complete(outputSuccess());

        assertTrue(cancelled.isCancelled());
        assertSame(expected, observed.get(1, TimeUnit.SECONDS));
    }

    @Test
    void latePhysicalLifecycleFailureDoesNotReplaceProcessSuccess() throws Exception {
        SessionExitBarrier barrier = new SessionExitBarrier();
        CompletableFuture<SessionTermination.Outcome> process = new CompletableFuture<>();
        CompletableFuture<SessionOutputCleanup.Outcome> output = new CompletableFuture<>();
        SessionExit expected = new SessionExit(OptionalInt.of(0), false);
        barrier.observe(process, output);

        process.complete(success(expected));
        output.complete(new SessionOutputCleanup.Outcome(
                List.of(),
                new SessionOutputCleanup.PhysicalClose.LifecycleFailure(
                        new IllegalStateException("physical close"),
                        Optional.of(BoundedFailureReporter.captureFailureTarget()))));

        assertSame(expected, barrier.view().get(1, TimeUnit.SECONDS));
    }

    private static SessionOutputCleanup.Outcome outputSuccess() {
        return new SessionOutputCleanup.Outcome(List.of(), SessionOutputCleanup.PhysicalClose.Success.INSTANCE);
    }

    private static SessionTermination.Outcome success(SessionExit result) {
        return new SessionTermination.Outcome(result, List.of());
    }

    private static SessionTermination.Outcome failure(Throwable failure) {
        return new SessionTermination.Outcome(null, List.of(failure));
    }
}
