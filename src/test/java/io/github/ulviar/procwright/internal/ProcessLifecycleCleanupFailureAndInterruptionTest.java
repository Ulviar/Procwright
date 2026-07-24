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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    void rootLivenessFailureStillReachesForcefulDestroyFallback() throws Exception {
        LivenessRestrictedProcess process = new LivenessRestrictedProcess();

        assertFalse(ProcessLifecycle.waitFor(process, Duration.ofNanos(1), new LiveDescendantSnapshot()));
        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void failingOrdinaryExitFallbackIsObservedOnceAndRetainsIdentity() {
        IllegalStateException expected = new IllegalStateException("exit observation failed");
        SingleFailingExitValueProcess process = new SingleFailingExitValueProcess(expected);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class, () -> ProcessLifecycle.forceStop(process, Set.of(), Duration.ZERO));

        assertSame(expected, actual);
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
}
