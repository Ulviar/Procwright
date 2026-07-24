/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.SessionExit;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class StreamSessionStateTest {

    @Test
    void failureWinsOnceAndOwnsLaterFailures() {
        StreamSessionState state = new StreamSessionState(2);
        RuntimeException primary = new RuntimeException("primary");
        AssertionError secondary = new AssertionError("secondary");

        StreamSessionState.FailureSelection first = state.selectFailure(primary);
        StreamSessionState.FailureSelection second = state.selectFailure(secondary);

        assertTrue(first.installed());
        assertSame(primary, first.primary());
        assertNull(first.lateFailure());
        assertFalse(second.installed());
        assertSame(primary, second.primary());
        assertNull(second.lateFailure());
        assertSame(secondary, second.suppressedFailure());
        second.attachSuppressedFailure();
        assertSame(secondary, primary.getSuppressed()[0]);
        assertTrue(state.stopping());
    }

    @Test
    void controlOutcomeLeavesLaterFailureForLateReporting() {
        StreamSessionState state = new StreamSessionState(2);
        RuntimeException late = new RuntimeException("late");

        assertTrue(state.selectControl(StreamSessionState.Control.CLOSED));
        StreamSessionState.FailureSelection selection = state.selectFailure(late);

        assertFalse(selection.installed());
        assertNull(selection.primary());
        assertSame(late, selection.lateFailure());
        assertNull(selection.suppressedFailure());
        assertTrue(state.controlledStop());
    }

    @Test
    void hostileSuppressionCannotHoldTheStateMonitor() throws Exception {
        StreamSessionState state = new StreamSessionState(1);
        RuntimeException primary = new RuntimeException("primary");
        BlockingCauseFailure secondary = new BlockingCauseFailure();
        state.selectFailure(primary);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> suppression = null;
        try {
            suppression = executor.submit(() -> {
                StreamSessionState.FailureSelection selection = state.selectFailure(secondary);
                selection.attachSuppressedFailure();
            });
            assertTrue(secondary.causeAccessed.await(1, TimeUnit.SECONDS));

            Future<StreamSessionState.Completion> completion = executor.submit(() -> {
                state.outputPumpCompleted();
                return state.claimCompletion();
            });
            StreamSessionState.FailedCompletion failed =
                    assertInstanceOf(StreamSessionState.FailedCompletion.class, completion.get(1, TimeUnit.SECONDS));
            assertSame(primary, failed.primary());
        } finally {
            secondary.releaseCause.countDown();
            try {
                if (suppression != null) {
                    suppression.get(1, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void failureCompletionWaitsForEveryOutputPumpButNotForNestedExit() {
        StreamSessionState state = new StreamSessionState(2);
        RuntimeException primary = new RuntimeException("primary");
        state.selectFailure(primary);

        state.outputPumpCompleted();
        assertNull(state.claimCompletion());

        state.outputPumpCompleted();
        StreamSessionState.FailedCompletion completion =
                assertInstanceOf(StreamSessionState.FailedCompletion.class, state.claimCompletion());
        assertSame(primary, completion.primary());
        assertNull(state.claimCompletion());
    }

    @Test
    void normalCompletionRequiresNestedSuccessAndEveryOutputPump() {
        StreamSessionState state = new StreamSessionState(2);
        SessionExit nestedExit = new SessionExit(OptionalInt.of(17), true);

        state.nestedSucceeded(nestedExit);
        state.outputPumpCompleted();
        assertNull(state.claimCompletion());

        state.outputPumpCompleted();
        StreamSessionState.SuccessfulCompletion completion =
                assertInstanceOf(StreamSessionState.SuccessfulCompletion.class, state.claimCompletion());
        assertTrue(completion.timedOut());
        assertFalse(completion.closed());
        assertEquals(nestedExit.exitCode(), completion.exitCode());
        assertTrue(state.stopping());
        assertNull(state.claimCompletion());
    }

    @Test
    void controlledCompletionCanPublishAfterNestedFailure() {
        StreamSessionState state = new StreamSessionState(2);
        RuntimeException nestedFailure = new RuntimeException("nested");

        assertTrue(state.selectControl(StreamSessionState.Control.CLOSED));
        state.nestedFailed(nestedFailure);
        state.outputPumpCompleted();
        state.outputPumpCompleted();

        StreamSessionState.SuccessfulCompletion completion =
                assertInstanceOf(StreamSessionState.SuccessfulCompletion.class, state.claimCompletion());
        assertTrue(completion.closed());
        assertFalse(completion.timedOut());
        assertTrue(completion.exitCode().isEmpty());
        assertNull(state.claimCompletion());
    }

    @Test
    void nestedFailureAloneCannotInventAStreamOutcome() {
        StreamSessionState state = new StreamSessionState(1);

        state.nestedFailed(new RuntimeException("nested"));
        state.outputPumpCompleted();

        assertNull(state.claimCompletion());
        assertFalse(state.stopping());
    }

    @SuppressWarnings("serial")
    private static final class BlockingCauseFailure extends RuntimeException {

        private final CountDownLatch causeAccessed = new CountDownLatch(1);
        private final CountDownLatch releaseCause = new CountDownLatch(1);

        private BlockingCauseFailure() {
            super("secondary", null);
        }

        @Override
        public synchronized Throwable getCause() {
            causeAccessed.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseCause.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
