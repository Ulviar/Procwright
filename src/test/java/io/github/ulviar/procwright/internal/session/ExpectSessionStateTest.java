/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ExpectSessionStateTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalOutputRemainsMatchableAfterStdoutEof(boolean regex) {
        ExpectSessionState state = state((thread, error) -> {});
        state.publishDecoded("stdout", true, "before:done|done");
        state.recordStdoutEof();
        ExpectRegexMatcher matcher = new ExpectRegexMatcher(state, ExpectRegexMatcher::evaluate, failure -> {});

        ExpectMatch first = match(state, matcher, regex, "done");
        assertEquals("done", first.matched());
        assertEquals("before:", first.before());
        ExpectMatch second = match(state, matcher, regex, "done");
        assertEquals("|", second.before());
        ExpectException missing = assertThrows(ExpectException.class, () -> match(state, matcher, regex, "done"));
        assertEquals(ExpectException.Reason.EOF, missing.reason());
    }

    private static ExpectMatch match(
            ExpectSessionState state, ExpectRegexMatcher matcher, boolean regex, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        return regex
                ? matcher.match(Pattern.compile(expected), deadline, "not found", "regex")
                : state.awaitLiteral(expected, deadline, "not found", "literal");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eofDoesNotHideCloseOrOutputFailureBeforeMatching(boolean failOutput) {
        ExpectSessionState state = state((thread, error) -> {});
        state.publishDecoded("stdout", true, "done");
        state.recordStdoutEof();
        if (failOutput) {
            state.recordOutputFailure(new IllegalStateException("stderr failed"));
        } else {
            state.close();
        }
        ExpectRegexMatcher matcher = new ExpectRegexMatcher(state, ExpectRegexMatcher::evaluate, failure -> {});
        ExpectException.Reason expected = failOutput ? ExpectException.Reason.FAILURE : ExpectException.Reason.CLOSED;
        for (boolean regex : new boolean[] {false, true}) {
            assertEquals(
                    expected,
                    assertThrows(ExpectException.class, () -> match(state, matcher, regex, "done"))
                            .reason());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeOrOutputFailureCancelsRegexAfterEof(boolean failOutput) throws Exception {
        ExpectSessionState state = state((thread, error) -> {});
        state.publishDecoded("stdout", true, "done");
        state.recordStdoutEof();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger evaluations = new AtomicInteger();
        ExpectRegexMatcher matcher = new ExpectRegexMatcher(
                state,
                (pattern, text, start) -> {
                    evaluations.incrementAndGet();
                    started.countDown();
                    try {
                        ExpectTestFixtures.awaitUninterruptibly(release);
                        return ExpectRegexMatcher.evaluate(pattern, text, start);
                    } finally {
                        stopped.countDown();
                    }
                },
                failure -> {});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> pending = executor.submit(() -> matcher.match(
                    Pattern.compile("done"),
                    System.nanoTime() + Duration.ofHours(1).toNanos(),
                    "not found",
                    "regex"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            if (failOutput) {
                state.recordOutputFailure(new IllegalStateException("stderr failed"));
            } else {
                state.close();
            }
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
            ExpectException.Reason expected =
                    failOutput ? ExpectException.Reason.FAILURE : ExpectException.Reason.CLOSED;
            assertEquals(expected, ((ExpectException) thrown.getCause()).reason());
            assertEquals(
                    expected,
                    assertThrows(ExpectException.class, () -> match(state, matcher, true, "done"))
                            .reason());
            assertEquals(1, evaluations.get(), "no new evaluator may run after terminal abandonment");
        } finally {
            release.countDown();
            state.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void unchangedRegexSnapshotWaitsUntilRecoverableTimeout() {
        ExpectSessionState state = state((thread, error) -> {});
        ExpectSessionState.RegexSnapshot snapshot = state.regexSnapshot();

        ExpectException timeout = assertThrows(
                ExpectException.class,
                () -> state.acceptRegexEvaluation(
                        snapshot,
                        null,
                        System.nanoTime() + Duration.ofMillis(100).toNanos(),
                        "not found"));

        assertEquals(ExpectException.Reason.TIMEOUT, timeout.reason());
        assertFalse(state.isStopping());
        assertFalse(state.isClosed());
        state.publishDecoded("stdout", true, "ready");
        assertEquals(
                "ready",
                state.awaitLiteral(
                                "ready",
                                System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                                "not found",
                                "literal")
                        .matched());
    }

    @Test
    void cursorChangeWakesRegexWaiterWithoutNewOutput() throws Exception {
        ExpectSessionState state = state((thread, error) -> {});
        state.publishDecoded("stdout", true, "prefixfoo");
        ExpectSessionState.RegexSnapshot snapshot = state.regexSnapshot();
        Pattern pattern = Pattern.compile("^foo");
        assertNull(ExpectRegexMatcher.evaluate(pattern, snapshot.output(), snapshot.searchStart()));
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ExpectMatch> waiter = executor.submit(() -> {
                waitingThread.set(Thread.currentThread());
                return state.acceptRegexEvaluation(
                        snapshot,
                        null,
                        System.nanoTime() + Duration.ofSeconds(10).toNanos(),
                        "not found");
            });
            assertTrue(eventually(
                    () -> waitingThread.get() != null && waitingThread.get().getState() == Thread.State.TIMED_WAITING));

            state.awaitLiteral(
                    "prefix", System.nanoTime() + Duration.ofSeconds(1).toNanos(), "not found", "literal");

            assertNull(waiter.get(1, TimeUnit.SECONDS));
            ExpectSessionState.RegexSnapshot advanced = state.regexSnapshot();
            ExpectMatch match = state.acceptRegexEvaluation(
                    advanced,
                    ExpectRegexMatcher.evaluate(pattern, advanced.output(), advanced.searchStart()),
                    System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                    "not found");
            assertEquals("foo", match.matched());
        } finally {
            state.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeOwnsTerminalBeforeLosingRegexErrorAndReportsErrorOutsideStateLock() {
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> reportedThread = new AtomicReference<>();
        AtomicReference<Error> reportedError = new AtomicReference<>();
        AtomicBoolean reporterHeldStateLock = new AtomicBoolean();
        AtomicReference<ExpectSessionState> stateReference = new AtomicReference<>();
        ExpectSessionState state = new ExpectSessionState(256, 256, (thread, error) -> {
            reports.incrementAndGet();
            reportedThread.set(thread);
            reportedError.set(error);
            reporterHeldStateLock.set(Thread.holdsLock(stateReference.get()));
        });
        stateReference.set(state);
        Thread evaluatorThread = new Thread();
        AssertionError evaluatorError = new AssertionError("late evaluator failure");

        assertTrue(state.close());
        RuntimeException selected = state.arbitrateRegexFailure(evaluatorError, evaluatorThread);

        ExpectException closed = (ExpectException) selected;
        assertEquals(ExpectException.Reason.CLOSED, closed.reason());
        assertEquals(1, reports.get());
        assertSame(evaluatorThread, reportedThread.get());
        assertSame(evaluatorError, reportedError.get());
        assertFalse(reporterHeldStateLock.get());
    }

    @Test
    void outputFailureOwnsTerminalWithoutMutatingItsCause() {
        AtomicInteger reports = new AtomicInteger();
        ExpectSessionState state = state((thread, error) -> reports.incrementAndGet());
        IllegalStateException outputFailure = new IllegalStateException("output failed first");
        AssertionError evaluatorError = new AssertionError("late evaluator failure");

        ExpectSessionState.OutputFailureDecision decision = state.recordOutputFailure(outputFailure);
        RuntimeException selected = state.arbitrateRegexFailure(evaluatorError, new Thread());

        ExpectException failure = (ExpectException) selected;
        assertEquals(ExpectException.Reason.FAILURE, decision.selectedFailure().reason());
        assertSame(outputFailure, decision.selectedFailure().getCause());
        assertEquals(ExpectException.Reason.FAILURE, failure.reason());
        assertSame(outputFailure, failure.getCause());
        assertEquals(0, outputFailure.getSuppressed().length);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(evaluatorError, failure.getSuppressed()[0]);
        assertEquals(0, reports.get());
    }

    @Test
    void laterCloseDoesNotReplaceObservedEofTerminal() {
        ExpectSessionState state = state((thread, error) -> {});

        state.recordStdoutEof();
        ExpectException observed = assertThrows(
                ExpectException.class,
                () -> state.awaitLiteral(
                        "missing", System.nanoTime() + Duration.ofSeconds(1).toNanos(), "not found", "literal"));
        assertTrue(state.close());
        assertSame(observed, state.recordObservedEof(observed));
        ExpectException terminal =
                org.junit.jupiter.api.Assertions.assertThrows(ExpectException.class, () -> state.throwIfTerminal());
        assertEquals(ExpectException.Reason.EOF, terminal.reason());
    }

    @Test
    void observedEofDeliveryPreservesAnAlreadySelectedClose() {
        ExpectSessionState state = state((thread, error) -> {});
        state.recordStdoutEof();
        assertTrue(state.close());
        ExpectException eof = new ExpectException(ExpectException.Reason.EOF, state.transcript(), "not found");

        assertEquals(ExpectException.Reason.CLOSED, state.recordObservedEof(eof).reason());
    }

    @Test
    void laterFatalOutputFailureIsReportedInsteadOfDecoratingThePrimary() {
        ExpectSessionState state = state((thread, error) -> {});
        IllegalStateException primary = new IllegalStateException("primary");
        AssertionError secondary = new AssertionError("secondary");
        state.recordOutputFailure(primary);

        ExpectSessionState.OutputFailureDecision decision = state.recordOutputFailure(secondary);

        assertNull(decision.selectedFailure());
        assertSame(secondary, decision.fatalToPublish());
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void repeatedCanonicalErrorIsNotPublishedAsALateFailure() {
        ExpectSessionState state = state((thread, error) -> {});
        AssertionError canonical = new AssertionError("canonical");

        state.recordOutputFailure(canonical);
        ExpectSessionState.OutputFailureDecision repeated = state.recordOutputFailure(canonical);

        assertNull(repeated.selectedFailure());
        assertNull(repeated.fatalToPublish());
    }

    private static ExpectSessionState state(java.util.function.BiConsumer<Thread, Error> reporter) {
        return new ExpectSessionState(256, 256, reporter);
    }
}
