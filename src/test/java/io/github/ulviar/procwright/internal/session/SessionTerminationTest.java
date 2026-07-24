/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.OptionalInt;
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

        assertSame(expected, termination.completion().get(1, TimeUnit.SECONDS));
        assertTrue(termination.closedAndPublished());
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
                ExecutionException.class, () -> termination.completion().get(1, TimeUnit.SECONDS));
        assertSame(expected, observed.getCause());
        assertSame(expected, termination.failure());
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
        assertFalse(termination.completion().isDone());
        failureClaim.finishCleanup();

        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> termination.completion().get(1, TimeUnit.SECONDS));
        assertSame(failure, observed.getCause());
        assertSame(failure, termination.failure());
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

        assertFalse(termination.completion().isDone());

        secondCleanup.finishCleanup();

        ExecutionException observed = assertThrows(
                ExecutionException.class, () -> termination.completion().get(1, TimeUnit.SECONDS));
        assertSame(first, observed.getCause());
        assertEquals(1, first.getSuppressed().length);
        assertSame(second, first.getSuppressed()[0]);
    }

    @Test
    void publicationRecordsCanonicalFailureBeforeCleanupContinues() {
        SessionTermination termination = termination();
        SessionTermination.Publication publication = termination.claimNaturalSuccess();
        AssertionError failure = new AssertionError("resource close failed");

        publication.recordFailure(failure);

        assertSame(failure, termination.failure());
        publication.publishFailure(failure);
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
                    assertFalse(termination.completion().isDone());
                }
                failureClaim.finishCleanup();

                ExecutionException observed = assertThrows(
                        ExecutionException.class, () -> termination.completion().get(1, TimeUnit.SECONDS));
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
        assertEquals(0, termination.completion().join().exitCode().stream().count());
    }

    private static SessionTermination termination() {
        return new SessionTermination(
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-termination-test", CommandEcho.empty()));
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

    private static int count(Object value) {
        return value == null ? 0 : 1;
    }
}
