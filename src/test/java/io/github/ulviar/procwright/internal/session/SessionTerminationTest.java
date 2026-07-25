/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport.failOnceOn;
import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SessionTerminationTest {

    @Test
    void closePathOwnsTerminalPublication() throws Exception {
        SessionTermination termination = termination();

        assertTrue(termination.beginClosing());
        assertTrue(!termination.beginClosing());

        SessionTermination.Publication publication = termination.claimCloseSuccess();
        assertTrue(publication != null);
        SessionExit expected = new SessionExit(OptionalInt.of(17), false);
        publication.publishSuccess(expected);

        assertSame(expected, observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertTrue(termination.published());
        assertNull(termination.claimNaturalSuccess());
        assertNull(termination.claimFailure(new AssertionError("late")));
    }

    @Test
    void firstFailureWinsAndPreservesIdentity() {
        SessionTermination termination = termination();
        AssertionError expected = new AssertionError("terminal");

        SessionTermination.FailureClaim failureClaim = termination.claimFailure(expected);
        assertTrue(failureClaim != null);
        assertTrue(failureClaim.ownsPublication());
        failureClaim.finishCleanup();

        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(expected, observed.getCause());
        assertNull(termination.claimFailure(new AssertionError("late")));
    }

    @Test
    void failureBeforePublicationBecomesCanonicalForAnEarlierSuccessClaim() {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        AssertionError failure = new AssertionError("output close failed");

        SessionTermination.FailureClaim failureClaim = termination.claimFailure(failure);
        assertTrue(failureClaim != null);
        assertFalse(failureClaim.ownsPublication());
        publication.publishSuccess(new SessionExit(OptionalInt.of(0), false));
        assertFalse(observeTerminal(termination).isDone());
        failureClaim.finishCleanup();

        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause());
    }

    @Test
    void successPublicationWaitsForEveryAcceptedFailureCleanup() {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        AssertionError first = new AssertionError("first");
        IllegalStateException second = new IllegalStateException("second");
        SessionTermination.FailureClaim firstCleanup = termination.claimFailure(first);
        SessionTermination.FailureClaim secondCleanup = termination.claimFailure(second);

        publication.publishSuccess(new SessionExit(OptionalInt.of(0), false));
        firstCleanup.finishCleanup();

        assertFalse(observeTerminal(termination).isDone());

        secondCleanup.finishCleanup();

        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(first, observed.getCause().getCause());
        assertEquals(
                java.util.List.of(second), java.util.List.of(observed.getCause().getSuppressed()));
        assertEquals(0, first.getSuppressed().length);
        assertEquals(0, second.getSuppressed().length);
    }

    @Test
    void secondaryFailureClaimIsNotBlockedByThePrimaryFailureMonitor() throws Exception {
        SessionTermination termination = termination();
        AssertionError primary = new AssertionError("primary");
        IllegalStateException secondary = new IllegalStateException("secondary");
        SessionTermination.FailureClaim first = termination.claimFailure(primary);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        SessionTermination.FailureClaim second;
        try (var monitor = hold(primary)) {
            monitor.verifyHeld();
            Future<SessionTermination.FailureClaim> claim = executor.submit(() -> termination.claimFailure(secondary));
            second = claim.get(1, TimeUnit.SECONDS);

            assertTrue(second != null);
            assertFalse(second.ownsPublication());
            first.finishCleanup();
            second.finishCleanup();
            ExecutionException observed = assertThrows(
                    ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
            assertSame(primary, observed.getCause().getCause());
            assertEquals(
                    java.util.List.of(secondary),
                    java.util.List.of(observed.getCause().getSuppressed()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, primary.getSuppressed().length);
        assertEquals(0, secondary.getSuppressed().length);
    }

    @Test
    void publicationRecordsCanonicalFailureBeforeCleanupContinues() {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        AssertionError failure = new AssertionError("resource close failed");

        publication.recordFailure(failure);

        publication.publishFailure();
        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause());
        assertEquals(List.of(failure), termination.outcome().join().failures());
    }

    @Test
    void failureClaimRetainsDistinctCleanupFailuresExactlyOnce() {
        SessionTermination termination = termination();
        AssertionError terminalFailure = new AssertionError("terminal");
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup");
        SessionTermination.FailureClaim claim = termination.claimFailure(terminalFailure);

        claim.recordFailure(terminalFailure);
        claim.recordFailure(cleanupFailure);
        claim.recordFailure(cleanupFailure);
        claim.finishCleanup();

        assertEquals(
                List.of(terminalFailure, cleanupFailure),
                termination.outcome().join().failures());
    }

    @Test
    void duplicateFailureClaimsStillOwnIndependentCleanupButPublishOneIdentity() {
        SessionTermination termination = termination();
        AssertionError failure = new AssertionError("terminal");
        SessionTermination.FailureClaim first = termination.claimFailure(failure);
        SessionTermination.FailureClaim second = termination.claimFailure(failure);

        first.finishCleanup();
        assertFalse(termination.outcome().isDone());
        second.finishCleanup();

        assertEquals(List.of(failure), termination.outcome().join().failures());
        assertSame(failure, termination.outcome().join().failure());
    }

    @Test
    void outcomeViewCannotCancelTerminalPublication() {
        SessionTermination termination = termination();
        CompletableFuture<SessionTermination.Outcome> cancelledView = termination.outcome();
        assertTrue(cancelledView.cancel(false));
        assertFalse(termination.published());
        SessionExit result = new SessionExit(OptionalInt.of(0), false);

        termination.claimNaturalSuccess().publishSuccess(result);

        assertTrue(termination.published());
        assertSame(result, termination.outcome().join().result());
    }

    @Test
    void normalizationPreservesThePrimaryOfAnAcceptedAggregate() throws Exception {
        SessionTermination termination = termination();
        IllegalStateException runtime = new IllegalStateException("runtime");
        AssertionError fatal = new AssertionError("fatal");
        Throwable accepted =
                FailureAggregation.combineWithPrimary(fatal, List.of(runtime, fatal), "accepted aggregate");

        termination.claimFailure(accepted).finishCleanup();

        SessionTermination.Outcome outcome = termination.outcome().join();
        assertEquals(List.of(fatal, runtime), outcome.failures());
        assertSame(fatal, FailureAggregation.primary(outcome.failure()));
        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(fatal, FailureAggregation.primary(observed.getCause()));
    }

    @Test
    void processExitedDiagnosticFailureCompletesTheCanonicalOutcomeBeforeRethrow() throws Exception {
        AssertionError diagnosticFailure = new AssertionError("process-exited diagnostic");
        SessionTermination termination =
                terminationFailingOnceOn(DiagnosticEventType.PROCESS_EXITED, diagnosticFailure);
        SessionTermination.Publication publication = termination.claimNaturalSuccess();

        assertSame(
                diagnosticFailure,
                assertThrows(
                        AssertionError.class,
                        () -> publication.publishSuccess(new SessionExit(OptionalInt.of(0), false))));

        assertTrue(termination.published());
        assertEquals(List.of(diagnosticFailure), termination.outcome().join().failures());
        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(diagnosticFailure, observed.getCause());
    }

    @Test
    void failureDiagnosticFailuresRemainSecondaryAndIdentityDistinct() throws Exception {
        verifyFailureDiagnostic(DiagnosticEventType.SHUTDOWN_REQUESTED);
        verifyFailureDiagnostic(DiagnosticEventType.PROCESS_FAILED);
    }

    @Test
    void repeatedDiagnosticFailureIdentityIsPublishedOnce() {
        AssertionError failure = new AssertionError("same terminal and diagnostic failure");
        SessionTermination termination = terminationFailingOnceOn(DiagnosticEventType.SHUTDOWN_REQUESTED, failure);

        termination.claimFailure(failure).finishCleanup();

        assertEquals(List.of(failure), termination.outcome().join().failures());
        assertSame(failure, termination.outcome().join().failure());
    }

    @Test
    void naturalCloseAndFailureRaceSelectsOnePublicationAndOneCanonicalFailure() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int iteration = 0; iteration < 500; iteration++) {
                SessionTermination termination = termination();
                AssertionError failure = new AssertionError("terminal " + iteration);
                CountDownLatch start = new CountDownLatch(1);
                Future<SessionTermination.Publication> natural =
                        executor.submit(() -> after(start, termination::claimNaturalSuccess));
                Future<SessionTermination.Publication> close = executor.submit(() -> after(start, () -> {
                    termination.beginClosing();
                    return termination.claimCloseSuccess();
                }));
                Future<SessionTermination.FailureClaim> failed =
                        executor.submit(() -> after(start, () -> termination.claimFailure(failure)));

                start.countDown();
                SessionTermination.Publication naturalPublication = natural.get(1, TimeUnit.SECONDS);
                SessionTermination.Publication closePublication = close.get(1, TimeUnit.SECONDS);
                SessionTermination.FailureClaim failureClaim = failed.get(1, TimeUnit.SECONDS);
                assertTrue(failureClaim != null);
                int publications =
                        count(naturalPublication) + count(closePublication) + (failureClaim.ownsPublication() ? 1 : 0);
                assertEquals(1, publications);

                SessionTermination.Publication publication =
                        naturalPublication != null ? naturalPublication : closePublication;
                if (publication != null) {
                    publication.publishSuccess(new SessionExit(OptionalInt.of(0), false));
                    assertFalse(observeTerminal(termination).isDone());
                }
                failureClaim.finishCleanup();

                ExecutionException observed = assertThrows(ExecutionException.class, () -> observeTerminal(termination)
                        .get(1, TimeUnit.SECONDS));
                assertSame(failure, observed.getCause());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneClaimCannotPublishTwice() {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        SessionExit result = new SessionExit(OptionalInt.empty(), false);

        publication.publishSuccess(result);

        assertThrows(IllegalStateException.class, () -> publication.publishSuccess(result));
        assertEquals(0, observeTerminal(termination).join().exitCode().stream().count());
    }

    @Test
    void concurrentPublicationRequestsHaveOneWinner() throws Exception {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        SessionExit result = new SessionExit(OptionalInt.of(0), false);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> publishAfter(start, publication, result));
            Future<Throwable> second = executor.submit(() -> publishAfter(start, publication, result));
            start.countDown();

            assertEquals(
                    1, countFailure(first.get(1, TimeUnit.SECONDS)) + countFailure(second.get(1, TimeUnit.SECONDS)));
            assertSame(result, termination.outcome().join().result());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalObserverRunsAfterTheTerminationMonitorIsReleased() throws Exception {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Boolean> observerResult = new CompletableFuture<>();
        termination.observe((result, failure) -> {
            try {
                observerResult.complete(executor.submit(() ->
                                termination.published() && termination.outcome().isDone())
                        .get(1, TimeUnit.SECONDS));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                observerResult.completeExceptionally(interruption);
            } catch (Exception observerFailure) {
                observerResult.completeExceptionally(observerFailure);
            }
        });

        try {
            publication.publishSuccess(new SessionExit(OptionalInt.of(0), false));

            assertTrue(observerResult.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static SessionTermination termination() {
        return new SessionTermination(
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-termination-test", CommandEcho.empty()));
    }

    private static SessionTermination terminationFailingOnceOn(
            DiagnosticEventType eventType, AssertionError diagnosticFailure) {
        return new SessionTermination(failOnceOn(
                DiagnosticsSettings.disabled().withListener(event -> {}),
                "session-termination-test",
                eventType,
                diagnosticFailure));
    }

    private static void verifyFailureDiagnostic(DiagnosticEventType eventType) throws Exception {
        IllegalStateException terminalFailure = new IllegalStateException("terminal");
        AssertionError diagnosticFailure = new AssertionError(eventType.name());
        SessionTermination termination = terminationFailingOnceOn(eventType, diagnosticFailure);

        termination.claimFailure(terminalFailure).finishCleanup();

        SessionTermination.Outcome outcome = termination.outcome().join();
        assertEquals(List.of(terminalFailure, diagnosticFailure), outcome.failures());
        assertSame(terminalFailure, FailureAggregation.primary(outcome.failure()));
        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> observeTerminal(termination).get(1, TimeUnit.SECONDS));
        assertSame(terminalFailure, FailureAggregation.primary(observed.getCause()));
    }

    private static CompletableFuture<SessionExit> observeTerminal(SessionTermination termination) {
        CompletableFuture<SessionExit> observed = new CompletableFuture<>();
        termination.observe((result, failure) -> {
            if (failure == null) {
                observed.complete(result);
            } else {
                observed.completeExceptionally(failure);
            }
        });
        return observed;
    }

    private static <T> T after(CountDownLatch start, java.util.function.Supplier<T> action) {
        try {
            start.await();
            return action.get();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }

    private static Throwable publishAfter(
            CountDownLatch start, SessionTermination.Publication publication, SessionExit result) {
        try {
            start.await();
            publication.publishSuccess(result);
            return null;
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return interruption;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static int countFailure(Throwable failure) {
        if (failure == null) {
            return 0;
        }
        assertTrue(failure instanceof IllegalStateException);
        return 1;
    }

    private static int count(Object value) {
        return value == null ? 0 : 1;
    }
}
