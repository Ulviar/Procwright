/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessLivenessTest {

    @Test
    void ordinaryObservationFallsBackToExitValueWhenLivenessIsUnavailable() {
        assertTrue(ProcessLiveness.hasExited(new ObservationProcess(false, new SecurityException("denied"))));
        assertFalse(ProcessLiveness.hasExited(new ObservationProcess(true, new SecurityException("denied"))));
    }

    @Test
    void unavailableOrdinaryLivenessAndExitNeverProveCompletion() {
        assertFalse(ProcessLiveness.hasExited(new ObservationProcess(
                true,
                new SecurityException("liveness denied"),
                new UnsupportedOperationException("exit denied"),
                () -> {})));
    }

    @Test
    void guardedExitObservationUsesTheSameFallback() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(2, 4, Duration.ofMillis(50));
        GuardedProcess exited =
                (GuardedProcess) scanner.guard(new ObservationProcess(false, new SecurityException("denied")));
        GuardedProcess live =
                (GuardedProcess) scanner.guard(new ObservationProcess(true, new SecurityException("denied")));
        LivenessObservationBudget budget = LivenessObservationBudget.providerLimited(Duration.ofSeconds(1));

        assertSame(ProcessLiveness.Observation.EXITED, ProcessLiveness.observeExit(exited, budget));
        assertSame(
                ProcessLiveness.Observation.LIVE,
                ProcessLiveness.observeExit(live, LivenessObservationBudget.providerLimited(Duration.ofSeconds(1))));
    }

    @Test
    void exhaustedLifecycleBudgetMeansUnknownWithoutInvokingTheProvider() throws Exception {
        ObservationProcess delegate = new ObservationProcess(true, new AssertionError("must not be called"));
        GuardedProcess guarded = (GuardedProcess) new ProcessTreeScanner(1, 4, Duration.ofMillis(50)).guard(delegate);

        ProcessLiveness.Observation observation = ProcessLiveness.observe(
                guarded, LivenessObservationBudget.fromRemainingLifecycle(Duration.ZERO, Duration.ofSeconds(1)));

        assertSame(ProcessLiveness.Observation.UNKNOWN, observation);
    }

    @Test
    void lifecycleExhaustionBetweenLivenessAndExitFallbackReturnsUnknown() throws Exception {
        AtomicLong nanoTime = new AtomicLong(100);
        ObservationProcess delegate = new ObservationProcess(
                false,
                new SecurityException("liveness denied"),
                null,
                () -> nanoTime.set(100 + Duration.ofSeconds(1).toNanos()));
        GuardedProcess guarded = (GuardedProcess) new ProcessTreeScanner(1, 4, Duration.ofMillis(50)).guard(delegate);
        LivenessObservationBudget budget = LivenessObservationBudget.fromRemainingLifecycle(
                Duration.ofSeconds(1), Duration.ofSeconds(2), nanoTime::get);

        ProcessLiveness.Observation observation = ProcessLiveness.observeExit(guarded, budget);

        assertSame(ProcessLiveness.Observation.UNKNOWN, observation);
        assertEquals(1, delegate.livenessCalls.get());
        assertEquals(0, delegate.exitValueCalls.get());
    }

    @Test
    void unavailableGuardedExitFallbackIsUnobservable() throws Exception {
        for (RuntimeException exitUnavailable : java.util.List.of(
                new SecurityException("exit denied"), new UnsupportedOperationException("exit unsupported"))) {
            GuardedProcess guarded = (GuardedProcess) new ProcessTreeScanner(1, 4, Duration.ofMillis(50))
                    .guard(new ObservationProcess(
                            true, new SecurityException("liveness denied"), exitUnavailable, () -> {}));

            ProcessLiveness.Observation observation = ProcessLiveness.observeExit(
                    guarded, LivenessObservationBudget.providerLimited(Duration.ofSeconds(1)));

            assertSame(ProcessLiveness.Observation.UNOBSERVABLE, observation);
        }
    }

    @Test
    void guardedExitFallbackFailureRetainsIdentity() {
        IllegalStateException expected = new IllegalStateException("exit failed");
        GuardedProcess guarded = (GuardedProcess) new ProcessTreeScanner(1, 4, Duration.ofMillis(50))
                .guard(new ObservationProcess(true, new SecurityException("liveness denied"), expected, () -> {}));

        Throwable actual = captureFailure(() ->
                ProcessLiveness.observeExit(guarded, LivenessObservationBudget.providerLimited(Duration.ofSeconds(1))));

        assertSame(expected, actual);
    }

    @Test
    void unifiedProcessObservationClassifiesOrdinaryLiveness() throws Exception {
        assertSame(
                ProcessLiveness.Observation.LIVE,
                ProcessLiveness.observe(
                        new ObservationProcess(true, null), DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));
        assertSame(
                ProcessLiveness.Observation.EXITED,
                ProcessLiveness.observe(
                        new ObservationProcess(false, null), DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));
        assertSame(
                ProcessLiveness.Observation.UNOBSERVABLE,
                ProcessLiveness.observe(
                        new ObservationProcess(true, new SecurityException("denied")),
                        DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));
    }

    @Test
    void unifiedHandleObservationClassifiesOrdinaryLiveness() throws Exception {
        MutableProcessHandle live = new MutableProcessHandle(201);
        MutableProcessHandle exited = new MutableProcessHandle(202);
        exited.destroyForcibly();
        MutableProcessHandle unobservable = new MutableProcessHandle(203) {
            @Override
            public boolean isAlive() {
                throw new SecurityException("denied");
            }
        };

        assertSame(
                ProcessLiveness.Observation.LIVE,
                ProcessLiveness.observe(live, DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));
        assertSame(
                ProcessLiveness.Observation.EXITED,
                ProcessLiveness.observe(exited, DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));
        assertSame(
                ProcessLiveness.Observation.UNOBSERVABLE,
                ProcessLiveness.observe(unobservable, DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));
    }

    @Test
    void providerRuntimeAndErrorFailuresRetainIdentity() {
        for (Throwable expected :
                java.util.List.of(new IllegalStateException("runtime"), new AssertionError("error"))) {
            GuardedProcess guarded =
                    (GuardedProcess) new ProcessTreeScanner(1, 4, Duration.ofMillis(50), Duration.ofSeconds(1))
                            .guard(new ObservationProcess(true, expected));

            Throwable actual = captureFailure(() ->
                    ProcessLiveness.observe(guarded, LivenessObservationBudget.providerLimited(Duration.ofSeconds(1))));

            assertSame(expected, actual);
        }
    }

    @Test
    void cleanupObservationReturnsFailuresSeparatelyInObservationOrder() {
        IllegalStateException livenessFailure = new IllegalStateException("liveness failed");
        AssertionError exitFailure = new AssertionError("exit failed");
        ObservationProcess process = new ObservationProcess(true, livenessFailure, exitFailure, () -> {});

        ProcessLiveness.ExitObservation observation =
                ProcessLiveness.observeExitForCleanup(process, DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));

        assertSame(ProcessLiveness.Observation.UNOBSERVABLE, observation.state());
        assertEquals(List.of(livenessFailure, exitFailure), observation.events());
        assertEquals(0, livenessFailure.getSuppressed().length);
    }

    @Test
    void cleanupGuardedObservationClassifiesCompletionWithoutSyntheticEvents() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(2, 4, Duration.ofMillis(100));
        List<ObservationExpectation> expectations = List.of(
                new ObservationExpectation(new ObservationProcess(false, null), ProcessLiveness.Observation.EXITED),
                new ObservationExpectation(new ObservationProcess(true, null), ProcessLiveness.Observation.LIVE),
                new ObservationExpectation(
                        new ObservationProcess(false, new SecurityException("liveness denied"), null, () -> {}),
                        ProcessLiveness.Observation.EXITED),
                new ObservationExpectation(
                        new ObservationProcess(true, new SecurityException("liveness denied"), null, () -> {}),
                        ProcessLiveness.Observation.LIVE),
                new ObservationExpectation(
                        new ObservationProcess(
                                true,
                                new SecurityException("liveness denied"),
                                new UnsupportedOperationException("exit denied"),
                                () -> {}),
                        ProcessLiveness.Observation.UNOBSERVABLE));

        for (ObservationExpectation expectation : expectations) {
            ProcessLiveness.ExitObservation observation = ProcessLiveness.observeExitForCleanup(
                    scanner.guard(expectation.process()), DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));

            assertSame(expectation.expected(), observation.state());
            assertEquals(List.of(), observation.events());
        }

        ObservationProcess uncalled = new ObservationProcess(true, new AssertionError("must not be called"));
        ProcessLiveness.ExitObservation exhausted =
                ProcessLiveness.observeExitForCleanup(scanner.guard(uncalled), System.nanoTime() - 1);
        assertSame(ProcessLiveness.Observation.UNKNOWN, exhausted.state());
        assertEquals(List.of(), exhausted.events());
        assertEquals(0, uncalled.livenessCalls.get());
        assertEquals(0, uncalled.exitValueCalls.get());
    }

    @Test
    void cleanupObservationRetainsFailureBeforeInterruptedFallbackWithoutRetry() throws Exception {
        IllegalStateException livenessFailure = new IllegalStateException("liveness failed");
        BlockingExitProcess delegate = new BlockingExitProcess(livenessFailure);
        ProcessTreeScanner scanner = new ProcessTreeScanner(2, 4, Duration.ofMillis(100));
        Process guarded = scanner.guard(delegate);
        AtomicReference<ProcessLiveness.ExitObservation> observed = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread caller = new Thread(
                () -> {
                    try {
                        observed.set(ProcessLiveness.observeExitForCleanup(
                                guarded, DurationSupport.deadlineFromNow(Duration.ofSeconds(5))));
                    } catch (Throwable failure) {
                        unexpected.set(failure);
                    }
                },
                "cleanup-liveness-interruption-test");
        caller.start();

        assertTrue(delegate.exitEntered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(2));
        delegate.exitRelease.countDown();

        assertFalse(caller.isAlive());
        assertSame(null, unexpected.get());
        ProcessLiveness.ExitObservation observation = observed.get();
        assertSame(ProcessLiveness.Observation.UNKNOWN, observation.state());
        assertEquals(2, observation.events().size());
        assertSame(livenessFailure, observation.events().get(0));
        assertTrue(observation.events().get(1) instanceof InterruptedException);
        assertEquals(1, delegate.exitValueCalls.get());
        assertEquals(0, livenessFailure.getSuppressed().length);
        ShutdownFailureLedger ledger = new ShutdownFailureLedger();
        ledger.recordObserved(observation.events());
        try {
            RuntimeException failure =
                    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, ledger::rethrowIfPresent);
            assertTrue(failure.getCause() instanceof CommandExecutionException);
            assertSame(observation.events().get(1), failure.getCause().getCause());
            assertTrue(java.util.Arrays.asList(failure.getSuppressed()).contains(livenessFailure));
        } finally {
            ledger.restoreInterrupt();
            Thread.interrupted();
        }
        assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
    }

    private static Throwable captureFailure(ThrowingOperation operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private record ObservationExpectation(ObservationProcess process, ProcessLiveness.Observation expected) {}

    private static class ObservationProcess extends Process {

        private final boolean alive;
        private final Throwable livenessFailure;
        private final Throwable exitFailure;
        private final Runnable beforeLiveness;
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueCalls = new AtomicInteger();

        private ObservationProcess(boolean alive, Throwable livenessFailure) {
            this(alive, livenessFailure, null, () -> {});
        }

        private ObservationProcess(
                boolean alive, Throwable livenessFailure, Throwable exitFailure, Runnable beforeLiveness) {
            this.alive = alive;
            this.livenessFailure = livenessFailure;
            this.exitFailure = exitFailure;
            this.beforeLiveness = beforeLiveness;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            exitValueCalls.incrementAndGet();
            throwIfPresent(exitFailure);
            if (alive) {
                throw new IllegalThreadStateException("alive");
            }
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public boolean isAlive() {
            livenessCalls.incrementAndGet();
            beforeLiveness.run();
            throwIfPresent(livenessFailure);
            return alive;
        }

        private static void throwIfPresent(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class BlockingExitProcess extends ObservationProcess {

        private final CountDownLatch exitEntered = new CountDownLatch(1);
        private final CountDownLatch exitRelease = new CountDownLatch(1);
        private final AtomicInteger exitValueCalls = new AtomicInteger();

        private BlockingExitProcess(Throwable livenessFailure) {
            super(true, livenessFailure, null, () -> {});
        }

        @Override
        public int exitValue() {
            exitValueCalls.incrementAndGet();
            exitEntered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    exitRelease.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }
}
