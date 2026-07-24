/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleObservationAndDeadlineTest extends ProcessLifecycleObservationAndDeadlineSupport {
    @Test
    void providerOperationCannotOutliveForceStopDeadlineAndRetainsCapacityUntilReturn() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofSeconds(5));
        BlockingLivenessProcess delegate = new BlockingLivenessProcess();
        Process guarded = scanner.guard(delegate);
        FutureTask<Throwable> cleanup = new FutureTask<>(() -> {
            try {
                ProcessLifecycle.forceStop(guarded, Duration.ofMillis(25));
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        Thread caller = new Thread(cleanup, "provider-deadline-cleanup-test");
        caller.setDaemon(true);

        caller.start();
        try {
            assertTrue(delegate.livenessEntered.await(1, TimeUnit.SECONDS));
            Throwable failure = cleanup.get(1, TimeUnit.SECONDS);

            assertTrue(failure instanceof CommandExecutionException, () -> "unexpected failure: " + failure);
            assertEquals(0, scanner.availableOperationPermits());
        } finally {
            delegate.releaseLiveness.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertFalse(caller.isAlive());
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void guardedLivenessOperationTimeoutAtTheOuterDeadlineReturnsFalse() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofSeconds(5));
        BlockingLivenessProcess delegate = new BlockingLivenessProcess();
        try {
            assertFalse(ProcessLifecycle.waitFor(
                    scanner.guard(delegate), Duration.ofMillis(25), new LiveDescendantSnapshot()));
            assertTrue(delegate.livenessEntered.await(1, TimeUnit.SECONDS));
        } finally {
            delegate.releaseLiveness.countDown();
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void unboundedGuardedWaitPreservesProviderLivenessBudgetExhaustion() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(100));
        BlockingLivenessProcess delegate = new BlockingLivenessProcess();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> wait = executor.submit(() ->
                    ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ZERO, new LiveDescendantSnapshot()));
            assertTrue(delegate.livenessEntered.await(1, TimeUnit.SECONDS));

            ExecutionException wrapper = assertThrows(ExecutionException.class, () -> wait.get(5, TimeUnit.SECONDS));
            assertTrue(wrapper.getCause() instanceof CommandExecutionException);
            CommandExecutionException failure = (CommandExecutionException) wrapper.getCause();
            assertTrue(ProcessTreeScanner.causedByOperationDeadline(failure));
            assertTrue(failure.getMessage().contains("procwright-provider-liveness-"));
        } finally {
            delegate.releaseLiveness.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void providerLimitedExitFallbackExhaustionRemainsTypedDuringUnboundedWait() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(500));
        SecurityLivenessBlockingExitProcess delegate = new SecurityLivenessBlockingExitProcess();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> wait = executor.submit(() ->
                    ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ZERO, new LiveDescendantSnapshot()));
            assertTrue(delegate.exitValueEntered.await(1, TimeUnit.SECONDS));

            ExecutionException wrapper = assertThrows(ExecutionException.class, () -> wait.get(5, TimeUnit.SECONDS));
            assertTrue(wrapper.getCause() instanceof CommandExecutionException);
            CommandExecutionException failure = (CommandExecutionException) wrapper.getCause();
            assertTrue(ProcessTreeScanner.causedByOperationDeadline(failure));
            assertTrue(failure.getMessage().contains("procwright-provider-exit-"));
        } finally {
            delegate.releaseExitValue.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void guardedShutdownTreatsLivenessTimeoutAtLifecycleDeadlineAsUnknownAndEscalates() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofSeconds(5));
        DeadlineScriptedProcess delegate = new DeadlineScriptedProcess(true, false, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OptionalInt> shutdown = executor.submit(() -> ProcessLifecycle.stop(
                    scanner.guard(delegate),
                    KnownDescendants.empty(),
                    ShutdownPolicy.interruptThenKill(Duration.ofMillis(40), Duration.ofMillis(250))));
            assertTrue(delegate.gracefulWaitLivenessEntered.await(1, TimeUnit.SECONDS));
            OptionalInt exitCode = shutdown.get(5, TimeUnit.SECONDS);

            assertEquals(137, exitCode.orElseThrow());
            assertEquals(1, delegate.forceDestroyCalls());
            assertEquals(0, delegate.exitValueWhileAliveCalls());
        } finally {
            delegate.releaseGracefulWaitLiveness.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 4));
    }

    @Test
    void guardedShutdownRetainsProviderLivenessTimeoutWhileLifecycleBudgetRemains() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofMillis(10));
        DeadlineScriptedProcess delegate = new DeadlineScriptedProcess(true, true, false);
        try {
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> ProcessLifecycle.stop(
                            scanner.guard(delegate),
                            KnownDescendants.empty(),
                            ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofMillis(250))));

            assertTrue(ProcessTreeScanner.causedByOperationDeadline(failure));
            assertTrue(failure.getMessage().contains("procwright-provider-liveness-"));
            assertTrue(delegate.gracefulWaitLivenessEntered.await(1, TimeUnit.SECONDS));
        } finally {
            delegate.releaseGracefulWaitLiveness.countDown();
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 4));
    }

    @Test
    void guardedExitCodeUsesThePostSignalWaitDeadline() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(25), Duration.ofSeconds(1));
        DelayedPostSignalExitProcess delegate = DelayedPostSignalExitProcess.afterGracefulSignal();

        OptionalInt exitCode = ProcessLifecycle.stop(
                scanner.guard(delegate),
                KnownDescendants.empty(),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(200), Duration.ofMillis(100)));

        assertEquals(23, exitCode.orElseThrow());
        assertEquals(0, delegate.forceDestroyCalls());
    }

    @Test
    void guardedExitCodeUsesThePostForceSignalWaitDeadline() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(25), Duration.ofSeconds(1));
        DelayedPostSignalExitProcess delegate = DelayedPostSignalExitProcess.afterForcefulSignal();

        OptionalInt exitCode = ProcessLifecycle.stop(
                scanner.guard(delegate),
                KnownDescendants.empty(),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofMillis(200)));

        assertEquals(23, exitCode.orElseThrow());
        assertEquals(1, delegate.forceDestroyCalls());
    }

    @Test
    void guardedDescendantLivenessTimeoutAtLifecycleDeadlineRemainsLiveAndIsForceStopped() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofSeconds(5));
        DeadlineScriptedProcess root = new DeadlineScriptedProcess(false, false, true);
        DeadlineScriptedProcessHandle descendant = new DeadlineScriptedProcessHandle(62, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OptionalInt> shutdown = executor.submit(() -> ProcessLifecycle.stop(
                    scanner.guard(root),
                    knownDescendants(scanner.guardObserved(descendant)),
                    ShutdownPolicy.interruptThenKill(Duration.ofMillis(40), Duration.ofMillis(250))));
            assertTrue(descendant.gracefulWaitLivenessEntered.await(1, TimeUnit.SECONDS));
            OptionalInt exitCode = shutdown.get(5, TimeUnit.SECONDS);

            assertEquals(137, exitCode.orElseThrow());
            assertEquals(1, descendant.recordedForceDestroyCalls());
            assertFalse(descendant.isAlive());
        } finally {
            descendant.releaseGracefulWaitLiveness.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 4));
    }

    @Test
    void guardedDescendantProviderLivenessTimeoutRemainsFailureWhileLifecycleBudgetRemains() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofMillis(10));
        DeadlineScriptedProcess root = new DeadlineScriptedProcess(false, false, true);
        DeadlineScriptedProcessHandle descendant = new DeadlineScriptedProcessHandle(63, true);
        try {
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> ProcessLifecycle.stop(
                            scanner.guard(root),
                            knownDescendants(scanner.guardObserved(descendant)),
                            ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofMillis(250))));

            assertTrue(ProcessTreeScanner.causedByOperationDeadline(failure));
            assertTrue(failure.getMessage().contains("procwright-provider-handle-liveness-"));
            assertTrue(descendant.gracefulWaitLivenessEntered.await(1, TimeUnit.SECONDS));
        } finally {
            descendant.releaseGracefulWaitLiveness.countDown();
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 4));
    }

    @Test
    void expiredDeadlineStillRecognizesAnAlreadyExitedProcess() throws Exception {
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();

        assertTrue(ProcessLifecycle.waitFor(new CompletedProcess(), Duration.ofNanos(1), descendants));
    }

    @Test
    void guardedProcessCompletionUsesLivenessPollingWithoutInvokingProviderWaitFor() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess();
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertTrue(ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ofSeconds(1), descendants, clock));

        assertEquals(2, delegate.livenessCalls());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void guardedProcessGetsAFinalLivenessProbeDuringTheLastPollInterval() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess(3);
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertTrue(ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ofMillis(250), descendants, clock));

        assertEquals(4, delegate.livenessCalls());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void guardedProcessTimeoutCompletesWithoutInvokingProviderWaitFor() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess(Integer.MAX_VALUE);
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertFalse(ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ofMillis(250), descendants, clock));

        assertTrue(clock.nanoTime() <= Duration.ofMillis(250).toNanos());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void providerFailureRemainsVisibleWhenItAdvancesTheOuterClockPastDeadline() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        AdvancingPollClock clock = new AdvancingPollClock();
        CommandExecutionException providerFailure = new CommandExecutionException(
                CommandExecutionException.Reason.RUNTIME_FAILURE, "provider liveness failed");
        Process delegate = new PollingCompletionProcess() {
            @Override
            public boolean isAlive() {
                clock.advance(Duration.ofMillis(250).toNanos());
                throw providerFailure;
            }
        };

        CommandExecutionException observed = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.waitFor(
                        scanner.guard(delegate), Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));

        assertSame(providerFailure, observed);
    }

    @Test
    void descendantSnapshotAccumulatesHandlesAcrossPolls() throws Exception {
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        ProcessHandle observedBeforeReparenting = ProcessHandle.current();

        assertTrue(ProcessLifecycle.waitFor(
                new ReparentingProcess(observedBeforeReparenting), Duration.ofSeconds(1), descendants));

        assertTrue(descendants.current().contains(observedBeforeReparenting));
    }

    @Test
    void descendantSnapshotPrunesExitedHandles() throws Exception {
        ProcessHandle exited = new TestProcessHandle(42, false);
        ProcessHandle live = ProcessHandle.current();
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot(knownDescendants(exited));

        assertTrue(ProcessLifecycle.waitFor(new ReparentingProcess(live), Duration.ofSeconds(1), descendants));

        assertFalse(descendants.current().contains(exited));
        assertTrue(descendants.current().contains(live));
    }
}
