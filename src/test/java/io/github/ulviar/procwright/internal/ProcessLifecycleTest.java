/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleTest {

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
            assertFalse(
                    ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ofMillis(25), new AtomicReference<>()));
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
            Future<Boolean> wait = executor.submit(
                    () -> ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ZERO, new AtomicReference<>()));
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
            Future<Boolean> wait = executor.submit(
                    () -> ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ZERO, new AtomicReference<>()));
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
                    Set.of(),
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
                            Set.of(),
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
    void guardedDescendantLivenessTimeoutAtLifecycleDeadlineRemainsLiveAndIsForceStopped() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofSeconds(5));
        DeadlineScriptedProcess root = new DeadlineScriptedProcess(false, false, true);
        DeadlineScriptedProcessHandle descendant = new DeadlineScriptedProcessHandle(62, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OptionalInt> shutdown = executor.submit(() -> ProcessLifecycle.stop(
                    scanner.guard(root),
                    Set.of(scanner.guardObserved(descendant)),
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
                            Set.of(scanner.guardObserved(descendant)),
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
        AtomicReference<Set<ProcessHandle>> descendants = new AtomicReference<>();

        assertTrue(ProcessLifecycle.waitFor(new CompletedProcess(), Duration.ofNanos(1), descendants));
    }

    @Test
    void guardedProcessCompletionUsesLivenessPollingWithoutInvokingProviderWaitFor() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess();
        AtomicReference<Set<ProcessHandle>> descendants = new AtomicReference<>();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertTrue(ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ofSeconds(1), descendants, clock));

        assertEquals(2, delegate.livenessCalls());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void guardedProcessGetsAFinalLivenessProbeDuringTheLastPollInterval() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess(3);
        AtomicReference<Set<ProcessHandle>> descendants = new AtomicReference<>();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertTrue(ProcessLifecycle.waitFor(scanner.guard(delegate), Duration.ofMillis(250), descendants, clock));

        assertEquals(4, delegate.livenessCalls());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void guardedProcessTimeoutCompletesWithoutInvokingProviderWaitFor() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess(Integer.MAX_VALUE);
        AtomicReference<Set<ProcessHandle>> descendants = new AtomicReference<>();
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
                        scanner.guard(delegate), Duration.ofMillis(250), new AtomicReference<>(), clock));

        assertSame(providerFailure, observed);
    }

    @Test
    void descendantSnapshotAccumulatesHandlesAcrossPolls() throws Exception {
        AtomicReference<Set<ProcessHandle>> descendants = new AtomicReference<>();
        ProcessHandle observedBeforeReparenting = ProcessHandle.current();

        assertTrue(ProcessLifecycle.waitFor(
                new ReparentingProcess(observedBeforeReparenting), Duration.ofSeconds(1), descendants));

        assertTrue(descendants.get().contains(observedBeforeReparenting));
    }

    @Test
    void descendantSnapshotPrunesExitedHandles() throws Exception {
        ProcessHandle exited = new TestProcessHandle(42, false);
        ProcessHandle live = ProcessHandle.current();
        AtomicReference<Set<ProcessHandle>> descendants = new AtomicReference<>(Set.of(exited));

        assertTrue(ProcessLifecycle.waitFor(new ReparentingProcess(live), Duration.ofSeconds(1), descendants));

        assertFalse(descendants.get().contains(exited));
        assertTrue(descendants.get().contains(live));
    }

    @Test
    void blockingDestroyFallbackHasGlobalBoundedCapacity() throws Exception {
        assertTrue(
                eventually(() -> BoundedDestroyDispatcher.availablePermits() == BoundedDestroyDispatcher.capacity()));
        int baselineCapacity = BoundedDestroyDispatcher.availablePermits();
        assertEquals(BoundedDestroyDispatcher.capacity(), baselineCapacity);
        List<BlockingDestroyProcess> processes = new ArrayList<>();
        try {
            for (int index = 0; index < baselineCapacity; index++) {
                BlockingDestroyProcess process = new BlockingDestroyProcess();
                processes.add(process);
                assertThrows(
                        CommandExecutionException.class,
                        () -> ProcessLifecycle.forceStop(process, Set.of(), Duration.ZERO));
            }

            assertEquals(0, BoundedDestroyDispatcher.availablePermits());
            BlockingDestroyProcess rejected = new BlockingDestroyProcess();
            processes.add(rejected);
            CommandExecutionException failure = assertThrows(
                    CommandExecutionException.class,
                    () -> ProcessLifecycle.forceStop(rejected, Set.of(), Duration.ZERO));

            assertTrue(failure.getMessage().contains("bounded destroy capacity is exhausted"));
            assertEquals(0, rejected.startedCalls(), "capacity rejection must not start another fallback thread");
        } finally {
            processes.forEach(BlockingDestroyProcess::release);
        }
        assertTrue(eventually(() -> BoundedDestroyDispatcher.availablePermits() == baselineCapacity));
        assertTrue(processes.stream().allMatch(process -> process.finishedCalls() == process.startedCalls()));
    }

    @Test
    void gracefulShutdownDiscoversAndStopsDescendantCreatedByRootTermination() {
        LateDescendantProcess process = new LateDescendantProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().gracefulDestroyCalls());
    }

    @Test
    void gracefulShutdownDoesNotSignalNewDescendantUntilRootHookExits() {
        SpawnInProgressProcess process = new SpawnInProgressProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertFalse(
                process.descendantSignalledWhileRootAlive(),
                "signalling a newly visible child can interrupt ProcessBuilder.start in the root shutdown hook");
        assertTrue(
                process.rootSurvivedFirstPostDiscoveryPoll(),
                "the fixture must expose the descendant while the shutdown hook root is still alive");
        assertEquals(1, process.descendant().gracefulDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void falseGracefulHandleResultDoesNotCloseOutputThroughProcessFallback() {
        FalseGracefulResultProcess process = new FalseGracefulResultProcess();

        ProcessLifecycle.stop(
                process,
                Set.of(),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                (threadPrefix, action) -> action.run());

        assertTrue(process.shutdownHookCreatedDescendant());
        assertEquals(0, process.processDestroyCalls());
        assertEquals(1, process.descendant().gracefulDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void descendantEnumerationSecurityFailureDoesNotPreventRootShutdown() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertFalse(process.isAlive());
        assertEquals(1, process.destroyCalls());
        assertEquals(0, process.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationSecurityFailureDoesNotPreventForcedRootShutdown() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertFalse(process.isAlive());
        assertEquals(0, process.destroyCalls());
        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationRuntimeFailureDegradesToRootOnlyForNormalExitAndShutdown() throws Exception {
        IllegalStateException enumerationFailure = new IllegalStateException("sysctl descendant lookup failed");
        SecurityRestrictedProcess completed = new SecurityRestrictedProcess(enumerationFailure);
        completed.complete();
        AtomicReference<Set<ProcessHandle>> observed = new AtomicReference<>();

        assertTrue(ProcessLifecycle.waitFor(completed, Duration.ofSeconds(1), observed));
        assertTrue(observed.get().isEmpty());

        SecurityRestrictedProcess graceful = new SecurityRestrictedProcess(enumerationFailure);
        ProcessLifecycle.stop(
                graceful, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));
        assertFalse(graceful.isAlive());
        assertEquals(1, graceful.destroyCalls());

        SecurityRestrictedProcess forceful = new SecurityRestrictedProcess(enumerationFailure);
        ProcessLifecycle.forceStop(forceful, Duration.ofMillis(100));
        assertFalse(forceful.isAlive());
        assertEquals(1, forceful.forceDestroyCalls());
    }

    @Test
    void unobservableKnownDescendantStillReceivesGracefulShutdown() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(45);

        ProcessLifecycle.stop(
                new CompletedProcess(),
                Set.of(descendant),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertEquals(1, descendant.destroyCalls());
        assertEquals(0, descendant.forceDestroyCalls());
    }

    @Test
    void unobservableKnownDescendantStillReceivesForcedShutdown() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(46);

        ProcessLifecycle.forceStop(new CompletedProcess(), Set.of(descendant), Duration.ofMillis(100));

        assertEquals(0, descendant.destroyCalls());
        assertEquals(1, descendant.forceDestroyCalls());
    }

    @Test
    void stabilizationRefreshCannotBypassExpiredCleanupDeadline() {
        SuccessiveDescendantsHandle descendant = new SuccessiveDescendantsHandle(47, 64);

        assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(new CompletedProcess(), Set.of(descendant), Duration.ZERO));

        assertTrue(descendant.discoveryCalls() < 64, "cleanup must stop discovering after its deadline");
    }

    @Test
    void rootLivenessFailureStillReachesForcefulDestroyFallback() throws Exception {
        LivenessRestrictedProcess process = new LivenessRestrictedProcess();

        assertFalse(ProcessLifecycle.waitFor(process, Duration.ofNanos(1), new AtomicReference<>()));
        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void forcefulWaitDiscoversAndForceStopsLateDescendant() {
        ForceLateDescendantProcess process = new ForceLateDescendantProcess();

        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().forceDestroyCalls());
    }

    @Test
    void cleanupPreservesFirstFailureAndAttemptsEveryDescendantRootAndEscalationPhase() throws Exception {
        AssertionError firstDescendantFailure = new AssertionError("first descendant graceful failure");
        IllegalStateException secondDescendantFailure = new IllegalStateException("second descendant graceful failure");
        AssertionError rootGracefulFailure = new AssertionError("root graceful failure");
        IllegalStateException descendantForceFailure = new IllegalStateException("descendant force failure");
        AssertionError rootForceFailure = new AssertionError("root force failure");
        ProcessTreeFailureProcess process = new ProcessTreeFailureProcess(
                firstDescendantFailure,
                secondDescendantFailure,
                rootGracefulFailure,
                descendantForceFailure,
                rootForceFailure);
        LinkedHashSet<ProcessHandle> descendants = new LinkedHashSet<>();
        descendants.add(process.secondDescendant());
        descendants.add(process.firstDescendant());

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> ProcessLifecycle.stop(
                        process, descendants, ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofMillis(100))));

        assertSame(rootGracefulFailure, thrown);
        assertEquals(
                List.of(firstDescendantFailure, secondDescendantFailure, descendantForceFailure, rootForceFailure),
                List.of(thrown.getSuppressed()));
        assertEquals(1, process.firstDescendant().gracefulDestroyCalls());
        assertEquals(1, process.secondDescendant().gracefulDestroyCalls());
        assertEquals(1, process.rootGracefulHandleCalls());
        assertEquals(1, process.firstDescendant().forceDestroyCalls());
        assertEquals(1, process.secondDescendant().forceDestroyCalls());
        assertEquals(1, process.rootForceHandleCalls());
        assertTrue(eventually(() -> process.rootGracefulFallbackCalls() == 1));
        assertTrue(eventually(() -> process.rootForceFallbackCalls() == 1));
    }

    @Test
    void interruptionBecomesPrimaryUntilForcefulCleanupAndThenRestoresStatus() {
        AssertionError gracefulFailure = new AssertionError("graceful descendant failure");
        IllegalStateException forceFailure = new IllegalStateException("forceful descendant failure");
        InterruptingCleanupProcess process = new InterruptingCleanupProcess(gracefulFailure, forceFailure);
        try {
            CommandExecutionException thrown = assertThrows(
                    CommandExecutionException.class,
                    () -> ProcessLifecycle.stop(
                            process,
                            Set.of(process.descendant()),
                            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(1), Duration.ofSeconds(5))));

            assertTrue(thrown.getCause() instanceof InterruptedException);
            assertEquals(List.of(gracefulFailure, forceFailure), List.of(thrown.getSuppressed()));
            assertEquals(1, process.descendant().gracefulDestroyCalls());
            assertEquals(1, process.descendant().forceDestroyCalls());
            assertEquals(1, process.rootForceFallbackCalls());
            assertFalse(process.interruptedDuringForcefulWait());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void destroyFallbackObservationInterruptionWinsAfterRootExitAndCleanupRunsWithClearStatus() {
        FallbackObservationInterruptedProcess process = new FallbackObservationInterruptedProcess();
        BoundedDestroyDispatcher.Limiter limiter = new BoundedDestroyDispatcher.Limiter(1);
        ProcessLifecycle.DestroyFallbackDispatcher dispatcher = (threadPrefix, action) ->
                BoundedDestroyDispatcher.dispatch(threadPrefix, action, limiter, completion -> {
                    completion.get();
                    Thread.currentThread().interrupt();
                });
        try {
            CommandExecutionException thrown = assertThrows(
                    CommandExecutionException.class,
                    () -> ProcessLifecycle.stop(
                            process,
                            Set.of(process.descendant()),
                            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(1), Duration.ofSeconds(1)),
                            dispatcher));

            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, thrown.reason());
            assertTrue(thrown.getCause() instanceof InterruptedException);
            assertEquals(
                    "cleanup boundary observed interrupt status",
                    thrown.getCause().getMessage());
            assertEquals(1, process.rootGracefulFallbackCalls());
            assertEquals(1, process.descendant().gracefulDestroyCalls());
            assertEquals(1, process.descendant().forceDestroyCalls());
            assertFalse(process.interruptedDuringForcefulCleanup());
            assertFalse(process.isAlive());
            assertEquals(1, limiter.availablePermits());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class ProcessTreeFailureProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final ThrowingProcessHandle firstDescendant;
        private final ThrowingProcessHandle secondDescendant;
        private final Throwable rootGracefulFailure;
        private final Throwable rootForceFailure;
        private final AtomicInteger rootGracefulHandleCalls = new AtomicInteger();
        private final AtomicInteger rootGracefulFallbackCalls = new AtomicInteger();
        private final AtomicInteger rootForceHandleCalls = new AtomicInteger();
        private final AtomicInteger rootForceFallbackCalls = new AtomicInteger();
        private final ProcessHandle rootHandle = new MutableProcessHandle(52) {
            @Override
            public boolean destroy() {
                rootGracefulHandleCalls.incrementAndGet();
                throwUnchecked(rootGracefulFailure);
                return false;
            }

            @Override
            public boolean destroyForcibly() {
                rootForceHandleCalls.incrementAndGet();
                alive.set(false);
                throwUnchecked(rootForceFailure);
                return false;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

        private ProcessTreeFailureProcess(
                Throwable firstDescendantFailure,
                Throwable secondDescendantFailure,
                Throwable rootGracefulFailure,
                Throwable descendantForceFailure,
                Throwable rootForceFailure) {
            firstDescendant = new ThrowingProcessHandle(50, firstDescendantFailure, null);
            secondDescendant = new ThrowingProcessHandle(51, secondDescendantFailure, descendantForceFailure);
            this.rootGracefulFailure = rootGracefulFailure;
            this.rootForceFailure = rootForceFailure;
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
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            rootGracefulFallbackCalls.incrementAndGet();
        }

        @Override
        public Process destroyForcibly() {
            rootForceFallbackCalls.incrementAndGet();
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.of(secondDescendant, firstDescendant);
        }

        private ThrowingProcessHandle firstDescendant() {
            return firstDescendant;
        }

        private ThrowingProcessHandle secondDescendant() {
            return secondDescendant;
        }

        private int rootGracefulHandleCalls() {
            return rootGracefulHandleCalls.get();
        }

        private int rootGracefulFallbackCalls() {
            return rootGracefulFallbackCalls.get();
        }

        private int rootForceHandleCalls() {
            return rootForceHandleCalls.get();
        }

        private int rootForceFallbackCalls() {
            return rootForceFallbackCalls.get();
        }
    }

    private static final class InterruptingCleanupProcess extends Process {

        private final AtomicBoolean forceRequested = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final ThrowingProcessHandle descendant;
        private final AtomicInteger livenessChecks = new AtomicInteger();
        private final AtomicInteger rootForceFallbackCalls = new AtomicInteger();
        private final AtomicBoolean interruptedDuringForcefulWait = new AtomicBoolean();
        private final ProcessHandle rootHandle = new MutableProcessHandle(54) {
            @Override
            public boolean destroy() {
                return false;
            }

            @Override
            public boolean destroyForcibly() {
                return false;
            }

            @Override
            public boolean isAlive() {
                return !stopped.get();
            }
        };

        private InterruptingCleanupProcess(Throwable gracefulFailure, Throwable forceFailure) {
            descendant = new ThrowingProcessHandle(53, gracefulFailure, forceFailure);
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
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped.get();
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            rootForceFallbackCalls.incrementAndGet();
            forceRequested.set(true);
            stopped.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            int check = livenessChecks.incrementAndGet();
            if (check == 2) {
                Thread.currentThread().interrupt();
            }
            if (forceRequested.get()) {
                interruptedDuringForcefulWait.set(Thread.currentThread().isInterrupted());
            }
            return !stopped.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.of(descendant);
        }

        private ThrowingProcessHandle descendant() {
            return descendant;
        }

        private int rootForceFallbackCalls() {
            return rootForceFallbackCalls.get();
        }

        private boolean interruptedDuringForcefulWait() {
            return interruptedDuringForcefulWait.get();
        }
    }

    private static final class FallbackObservationInterruptedProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger rootGracefulFallbackCalls = new AtomicInteger();
        private final AtomicBoolean interruptedDuringForcefulCleanup = new AtomicBoolean();
        private final FallbackObservationDescendant descendant =
                new FallbackObservationDescendant(interruptedDuringForcefulCleanup);
        private final ProcessHandle stoppedRoot = new MutableProcessHandle(56) {
            @Override
            public boolean isAlive() {
                return false;
            }
        };

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
            alive.set(false);
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            rootGracefulFallbackCalls.incrementAndGet();
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            if (alive.get()) {
                throw new UnsupportedOperationException("root handle unavailable before fallback");
            }
            return stoppedRoot;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.of(descendant);
        }

        private FallbackObservationDescendant descendant() {
            return descendant;
        }

        private int rootGracefulFallbackCalls() {
            return rootGracefulFallbackCalls.get();
        }

        private boolean interruptedDuringForcefulCleanup() {
            return interruptedDuringForcefulCleanup.get();
        }
    }

    private static final class FallbackObservationDescendant extends MutableProcessHandle {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean interruptedDuringForcefulCleanup;
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        private FallbackObservationDescendant(AtomicBoolean interruptedDuringForcefulCleanup) {
            super(55);
            this.interruptedDuringForcefulCleanup = interruptedDuringForcefulCleanup;
        }

        @Override
        public boolean destroy() {
            gracefulDestroyCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            interruptedDuringForcefulCleanup.set(Thread.currentThread().isInterrupted());
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        private int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class ThrowingProcessHandle extends MutableProcessHandle {

        private final Throwable gracefulFailure;
        private final Throwable forceFailure;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        private ThrowingProcessHandle(long pid, Throwable gracefulFailure, Throwable forceFailure) {
            super(pid);
            this.gracefulFailure = gracefulFailure;
            this.forceFailure = forceFailure;
        }

        @Override
        public boolean destroy() {
            gracefulDestroyCalls.incrementAndGet();
            throwUnchecked(gracefulFailure);
            alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            alive.set(false);
            throwUnchecked(forceFailure);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        private int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("test failure must be unchecked", failure);
    }

    private static final class CompletedProcess extends Process {

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
            return 0;
        }

        @Override
        public void destroy() {}
    }

    private static class PollingCompletionProcess extends Process {

        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger timedWaitCalls = new AtomicInteger();
        private final int livePolls;

        private PollingCompletionProcess() {
            this(1);
        }

        private PollingCompletionProcess(int livePolls) {
            this.livePolls = livePolls;
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
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCalls.incrementAndGet();
            return false;
        }

        @Override
        public int exitValue() {
            if (livenessCalls.get() <= livePolls) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public boolean isAlive() {
            return livenessCalls.incrementAndGet() <= livePolls;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private int livenessCalls() {
            return livenessCalls.get();
        }

        private int timedWaitCalls() {
            return timedWaitCalls.get();
        }
    }

    private static final class AdvancingPollClock implements ProcessLifecycle.PollClock {

        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long durationNanos) {
            nanos += durationNanos;
        }

        private void advance(long durationNanos) {
            nanos += durationNanos;
        }
    }

    private static final class BlockingLivenessProcess extends Process {

        private final CountDownLatch livenessEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLiveness = new CountDownLatch(1);

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
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("process is alive");
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            livenessEntered.countDown();
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    releaseLiveness.await();
                    break;
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return true;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class SecurityLivenessBlockingExitProcess extends Process {

        private final CountDownLatch exitValueEntered = new CountDownLatch(1);
        private final CountDownLatch releaseExitValue = new CountDownLatch(1);

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
        public boolean waitFor(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public int exitValue() {
            exitValueEntered.countDown();
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    releaseExitValue.await();
                    break;
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public boolean isAlive() {
            throw new SecurityException("liveness observation is denied");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class DeadlineScriptedProcess extends Process {

        private final boolean blockGracefulWaitLiveness;
        private final boolean completeWhenLivenessIsInterrupted;
        private final boolean completeOnGracefulDestroy;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueWhileAliveCalls = new AtomicInteger();
        private final CountDownLatch gracefulWaitLivenessEntered = new CountDownLatch(1);
        private final CountDownLatch releaseGracefulWaitLiveness = new CountDownLatch(1);
        private final ProcessHandle rootHandle = new MutableProcessHandle(61) {
            @Override
            public boolean destroy() {
                if (completeOnGracefulDestroy) {
                    alive.set(false);
                }
                return true;
            }

            @Override
            public boolean destroyForcibly() {
                alive.set(false);
                return super.destroyForcibly();
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

        private DeadlineScriptedProcess(
                boolean blockGracefulWaitLiveness,
                boolean completeWhenLivenessIsInterrupted,
                boolean completeOnGracefulDestroy) {
            this.blockGracefulWaitLiveness = blockGracefulWaitLiveness;
            this.completeWhenLivenessIsInterrupted = completeWhenLivenessIsInterrupted;
            this.completeOnGracefulDestroy = completeOnGracefulDestroy;
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
            alive.set(false);
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                exitValueWhileAliveCalls.incrementAndGet();
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() == 2 && blockGracefulWaitLiveness) {
                gracefulWaitLivenessEntered.countDown();
                try {
                    releaseGracefulWaitLiveness.await();
                } catch (InterruptedException expected) {
                    if (completeWhenLivenessIsInterrupted) {
                        alive.set(false);
                    }
                }
            }
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private int forceDestroyCalls() {
            return ((MutableProcessHandle) rootHandle).forceDestroyCalls();
        }

        private int exitValueWhileAliveCalls() {
            return exitValueWhileAliveCalls.get();
        }
    }

    private static final class DeadlineScriptedProcessHandle extends MutableProcessHandle {

        private final boolean completeWhenLivenessIsInterrupted;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final CountDownLatch gracefulWaitLivenessEntered = new CountDownLatch(1);
        private final CountDownLatch releaseGracefulWaitLiveness = new CountDownLatch(1);

        private DeadlineScriptedProcessHandle(long pid, boolean completeWhenLivenessIsInterrupted) {
            super(pid);
            this.completeWhenLivenessIsInterrupted = completeWhenLivenessIsInterrupted;
        }

        @Override
        public boolean destroy() {
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            alive.set(false);
            return super.destroyForcibly();
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() == 2) {
                gracefulWaitLivenessEntered.countDown();
                try {
                    releaseGracefulWaitLiveness.await();
                } catch (InterruptedException expected) {
                    if (completeWhenLivenessIsInterrupted) {
                        alive.set(false);
                    }
                }
            }
            return alive.get();
        }

        private int recordedForceDestroyCalls() {
            return ((MutableProcessHandle) this).forceDestroyCalls();
        }
    }

    private static final class ReparentingProcess extends Process {

        private final ProcessHandle initiallyVisibleDescendant;
        private int polls;
        private boolean alive = true;

        private ReparentingProcess(ProcessHandle initiallyVisibleDescendant) {
            this.initiallyVisibleDescendant = initiallyVisibleDescendant;
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
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            polls++;
            if (polls >= 2) {
                alive = false;
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return polls == 0 ? Stream.of(initiallyVisibleDescendant) : Stream.empty();
        }

        @Override
        public void destroy() {
            alive = false;
        }
    }

    private static final class BlockingDestroyProcess extends Process {

        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

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
        public int waitFor() throws InterruptedException {
            release.await();
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            blockDestroy();
        }

        @Override
        public Process destroyForcibly() {
            blockDestroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("no process handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private void blockDestroy() {
            started.incrementAndGet();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                alive.set(false);
            } finally {
                finished.incrementAndGet();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void release() {
            release.countDown();
        }

        private int startedCalls() {
            return started.get();
        }

        private int finishedCalls() {
            return finished.get();
        }
    }

    private static final class LateDescendantProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean descendantVisible = new AtomicBoolean();
        private final MutableProcessHandle descendant = new MutableProcessHandle(43);
        private final ProcessHandle rootHandle = new MutableProcessHandle(44) {
            @Override
            public boolean destroy() {
                descendantVisible.set(true);
                alive.set(false);
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

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
            alive.set(false);
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            rootHandle.destroy();
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return descendantVisible.get() ? Stream.of(descendant) : Stream.empty();
        }

        private MutableProcessHandle descendant() {
            return descendant;
        }
    }

    private static final class SpawnInProgressProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean gracefulSignalAttempted = new AtomicBoolean();
        private final AtomicBoolean descendantEnumerated = new AtomicBoolean();
        private final AtomicBoolean descendantSignalledWhileRootAlive = new AtomicBoolean();
        private final AtomicInteger postDiscoveryRootPolls = new AtomicInteger();
        private final AtomicBoolean rootSurvivedFirstPostDiscoveryPoll = new AtomicBoolean();
        private final MutableProcessHandle descendant = new MutableProcessHandle(47) {
            @Override
            public boolean destroy() {
                descendantSignalledWhileRootAlive.compareAndSet(false, alive.get());
                return super.destroy();
            }
        };
        private final ProcessHandle rootHandle = new MutableProcessHandle(48) {
            @Override
            public boolean destroy() {
                gracefulSignalAttempted.set(true);
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

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
            alive.set(false);
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !isAlive();
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            gracefulSignalAttempted.set(true);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (descendantEnumerated.get()) {
                int poll = postDiscoveryRootPolls.incrementAndGet();
                if (poll == 1) {
                    rootSurvivedFirstPostDiscoveryPoll.set(alive.get());
                } else {
                    alive.set(false);
                }
            }
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (!gracefulSignalAttempted.get()) {
                return Stream.empty();
            }
            descendantEnumerated.set(true);
            return Stream.of(descendant);
        }

        private boolean descendantSignalledWhileRootAlive() {
            return descendantSignalledWhileRootAlive.get();
        }

        private boolean rootSurvivedFirstPostDiscoveryPoll() {
            return rootSurvivedFirstPostDiscoveryPoll.get();
        }

        private MutableProcessHandle descendant() {
            return descendant;
        }
    }

    private static final class FalseGracefulResultProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean gracefulSignalAttempted = new AtomicBoolean();
        private final AtomicBoolean outputClosed = new AtomicBoolean();
        private final AtomicBoolean descendantCreated = new AtomicBoolean();
        private final AtomicInteger processDestroyCalls = new AtomicInteger();
        private final MutableProcessHandle descendant = new MutableProcessHandle(45);
        private final ProcessHandle rootHandle = new MutableProcessHandle(46) {
            @Override
            public boolean destroy() {
                gracefulSignalAttempted.set(true);
                return false;
            }

            @Override
            public boolean destroyForcibly() {
                alive.set(false);
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

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
            alive.set(false);
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            processDestroyCalls.incrementAndGet();
            outputClosed.set(true);
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (!gracefulSignalAttempted.get() || outputClosed.get()) {
                return Stream.empty();
            }
            descendantCreated.set(true);
            alive.set(false);
            return Stream.of(descendant);
        }

        private boolean shutdownHookCreatedDescendant() {
            return descendantCreated.get();
        }

        private int processDestroyCalls() {
            return processDestroyCalls.get();
        }

        private MutableProcessHandle descendant() {
            return descendant;
        }
    }

    private static final class SecurityRestrictedProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();
        private final RuntimeException descendantFailure;

        private SecurityRestrictedProcess() {
            this(new SecurityException("descendant enumeration is denied"));
        }

        private SecurityRestrictedProcess(RuntimeException descendantFailure) {
            this.descendantFailure = descendantFailure;
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
            alive.set(false);
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            destroyCalls.incrementAndGet();
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw descendantFailure;
        }

        private void complete() {
            alive.set(false);
        }

        private int destroyCalls() {
            return destroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class LivenessRestrictedProcess extends Process {

        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

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
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped.get();
        }

        @Override
        public int exitValue() {
            if (!stopped.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            stopped.set(true);
        }

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            stopped.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (!stopped.get()) {
                throw new SecurityException("root liveness observation is denied");
            }
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new SecurityException("descendant enumeration is denied");
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class ForceLateDescendantProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean forceSignalled = new AtomicBoolean();
        private final AtomicInteger postForceDiscoveries = new AtomicInteger();
        private final MutableProcessHandle descendant = new MutableProcessHandle(48);
        private final ProcessHandle rootHandle = new MutableProcessHandle(49) {
            @Override
            public boolean destroyForcibly() {
                forceSignalled.set(true);
                alive.set(false);
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

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
            alive.set(false);
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            rootHandle.destroyForcibly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            return rootHandle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (!forceSignalled.get()) {
                return Stream.empty();
            }
            return postForceDiscoveries.incrementAndGet() >= 2 ? Stream.of(descendant) : Stream.empty();
        }

        private MutableProcessHandle descendant() {
            return descendant;
        }
    }

    private static class MutableProcessHandle implements ProcessHandle {

        private final long pid;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        private MutableProcessHandle(long pid) {
            this.pid = pid;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            gracefulDestroyCalls.incrementAndGet();
            alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        private int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class SuccessiveDescendantsHandle implements ProcessHandle {

        private final long pid;
        private final int descendantLimit;
        private final AtomicInteger discoveryCalls = new AtomicInteger();

        private SuccessiveDescendantsHandle(long pid, int descendantLimit) {
            this.pid = pid;
            this.descendantLimit = descendantLimit;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            int discovery = discoveryCalls.incrementAndGet();
            return discovery <= descendantLimit ? Stream.of(new MutableProcessHandle(pid + discovery)) : Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            throw new UnsupportedOperationException("exit observation is denied");
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            return true;
        }

        @Override
        public boolean isAlive() {
            throw new SecurityException("liveness observation is denied");
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        private int discoveryCalls() {
            return discoveryCalls.get();
        }
    }

    private static final class UnobservableProcessHandle implements ProcessHandle {

        private final long pid;
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        private UnobservableProcessHandle(long pid) {
            this.pid = pid;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new SecurityException("descendant enumeration is denied");
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            throw new UnsupportedOperationException("exit observation is denied");
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            destroyCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean isAlive() {
            throw new SecurityException("liveness observation is denied");
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        private int destroyCalls() {
            return destroyCalls.get();
        }

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private record TestProcessHandle(long pid, boolean alive) implements ProcessHandle {

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }
}
