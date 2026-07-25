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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleCleanupFailureAndInterruptionTest
        extends ProcessLifecycleCleanupFailureAndInterruptionSupport {
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
                        RuntimeException.class,
                        () -> ProcessLifecycle.forceStop(process, KnownDescendants.empty(), Duration.ZERO));
            }

            assertEquals(0, BoundedDestroyDispatcher.availablePermits());
            BlockingDestroyProcess rejected = new BlockingDestroyProcess();
            processes.add(rejected);
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> ProcessLifecycle.forceStop(rejected, KnownDescendants.empty(), Duration.ZERO));

            failureSourceContaining(failure, "bounded destroy capacity is exhausted");
            assertEquals(0, rejected.startedCalls(), "capacity rejection must not start another fallback thread");
        } finally {
            processes.forEach(BlockingDestroyProcess::release);
        }
        assertTrue(eventually(() -> BoundedDestroyDispatcher.availablePermits() == baselineCapacity));
        assertTrue(processes.stream().allMatch(process -> process.finishedCalls() == process.startedCalls()));
    }

    @Test
    void rootLivenessFailureStillReachesForcefulDestroyFallback() throws Exception {
        LivenessRestrictedProcess process = new LivenessRestrictedProcess();

        assertFalse(ProcessLifecycle.waitFor(process, Duration.ofNanos(1), new LiveDescendantSnapshot()));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> ProcessLifecycle.forceStop(process, Duration.ofMillis(100)));

        failureSourceContaining(failure, "discovery did not complete");
        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void failingOrdinaryExitFallbackIsObservedOnceAndRetainsIdentity() {
        IllegalStateException expected = new IllegalStateException("exit observation failed");
        SingleFailingExitValueProcess process = new SingleFailingExitValueProcess(expected);

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                () -> ProcessLifecycle.forceStop(process, KnownDescendants.empty(), Duration.ZERO));

        assertTrue(failureSources(actual).contains(expected));
        assertEquals(1, process.exitValueCalls.get());
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

        Error thrown = assertThrows(
                Error.class,
                () -> ProcessLifecycle.stop(
                        process,
                        knownDescendants(descendants),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofMillis(100))));

        assertSame(rootGracefulFailure, thrown.getCause());
        assertEquals(
                List.of(
                        rootGracefulFailure,
                        firstDescendantFailure,
                        secondDescendantFailure,
                        descendantForceFailure,
                        rootForceFailure),
                failureSources(thrown));
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
            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    () -> ProcessLifecycle.stop(
                            process,
                            knownDescendants(process.descendant()),
                            ShutdownPolicy.interruptThenKill(Duration.ofSeconds(1), Duration.ofSeconds(5))));

            assertTrue(thrown.getCause() instanceof CommandExecutionException);
            assertTrue(thrown.getCause().getCause() instanceof InterruptedException);
            assertTrue(failureSources(thrown).contains(gracefulFailure));
            assertTrue(failureSources(thrown).contains(forceFailure));
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
    void gracefulPollingSleepInterruptionEscalatesAndForceStopsLateDescendant() throws Exception {
        SleepInterruptProcess process = new SleepInterruptProcess(1, 2);

        CleanupThreadResult result = interruptDuringPollingSleep(
                () -> ProcessLifecycle.stop(
                        process, ShutdownPolicy.interruptThenKill(Duration.ofSeconds(5), Duration.ofSeconds(1))),
                process);

        assertTrue(result.failure() instanceof CommandExecutionException);
        assertTrue(result.failure().getCause() instanceof InterruptedException);
        assertTrue(result.interruptedAfterCleanup());
        assertEquals(0, process.descendant().gracefulDestroyCalls());
        assertEquals(1, process.descendant().forceDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void forcefulPollingSleepInterruptionRediscoversAndResignalsBeforeRestoringStatus() throws Exception {
        SleepInterruptProcess process = new SleepInterruptProcess(2, 3);

        CleanupThreadResult result =
                interruptDuringPollingSleep(() -> ProcessLifecycle.forceStop(process, Duration.ofSeconds(5)), process);

        assertTrue(result.failure() instanceof CommandExecutionException);
        assertTrue(result.failure().getCause() instanceof InterruptedException);
        assertTrue(result.interruptedAfterCleanup());
        assertEquals(1, process.descendant().forceDestroyCalls());
        assertFalse(process.descendant().isAlive());
        assertEquals(2, process.rootForceSignals.get());
    }

    @Test
    void destroyFallbackObservationInterruptionWinsAfterRootExitAndCleanupRunsWithClearStatus() {
        FallbackObservationInterruptedProcess process = new FallbackObservationInterruptedProcess();
        BoundedDestroyDispatcher.Limiter limiter = new BoundedDestroyDispatcher.Limiter(1);
        DestroyFallbackDispatcher dispatcher = (threadPrefix, action) ->
                BoundedDestroyDispatcher.dispatch(threadPrefix, action, limiter, completion -> {
                    completion.get();
                    Thread.currentThread().interrupt();
                });
        try {
            CommandExecutionException thrown = assertThrows(
                    CommandExecutionException.class,
                    () -> ProcessTreeShutdown.stop(
                            process,
                            knownDescendants(process.descendant()),
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

    private static final class SingleFailingExitValueProcess extends Process {

        private final RuntimeException exitFailure;
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueCalls = new AtomicInteger();
        private final ProcessHandle handle = new MutableProcessHandle(901);

        private SingleFailingExitValueProcess(RuntimeException exitFailure) {
            this.exitFailure = exitFailure;
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
            throw exitFailure;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() <= 2) {
                return true;
            }
            throw new SecurityException("liveness unavailable");
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static CleanupThreadResult interruptDuringPollingSleep(Runnable cleanup, SleepInterruptProcess process)
            throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAfterCleanup = new AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    try {
                        cleanup.run();
                    } catch (Throwable cleanupFailure) {
                        failure.set(cleanupFailure);
                    } finally {
                        interruptedAfterCleanup.set(Thread.currentThread().isInterrupted());
                    }
                },
                "shutdown-polling-interruption-test");
        caller.setDaemon(true);
        caller.start();

        assertTrue(process.waitPollEntered.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> caller.getState() == Thread.State.TIMED_WAITING));
        process.descendantVisible.set(true);
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive());
        return new CleanupThreadResult(failure.get(), interruptedAfterCleanup.get());
    }

    private record CleanupThreadResult(Throwable failure, boolean interruptedAfterCleanup) {}

    private static final class SleepInterruptProcess extends Process {

        private final int forceSignalsBeforeExit;
        private final int livenessCallsBeforePolling;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean descendantVisible = new AtomicBoolean();
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger rootForceSignals = new AtomicInteger();
        private final CountDownLatch waitPollEntered = new CountDownLatch(1);
        private final MutableProcessHandle descendant = new MutableProcessHandle(902);
        private final ProcessHandle rootHandle = new MutableProcessHandle(903) {
            @Override
            public boolean destroy() {
                return true;
            }

            @Override
            public boolean destroyForcibly() {
                super.destroyForcibly();
                if (rootForceSignals.incrementAndGet() >= forceSignalsBeforeExit) {
                    alive.set(false);
                }
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

        private SleepInterruptProcess(int forceSignalsBeforeExit, int livenessCallsBeforePolling) {
            this.forceSignalsBeforeExit = forceSignalsBeforeExit;
            this.livenessCallsBeforePolling = livenessCallsBeforePolling;
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
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            if (rootForceSignals.incrementAndGet() >= forceSignalsBeforeExit) {
                alive.set(false);
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() >= livenessCallsBeforePolling) {
                waitPollEntered.countDown();
            }
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

        MutableProcessHandle descendant() {
            return descendant;
        }
    }
}
