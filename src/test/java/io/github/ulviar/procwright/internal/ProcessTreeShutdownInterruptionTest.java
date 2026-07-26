/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failsOnGracefulAndForcefulDestroy;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSources;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeShutdownInterruptionTest {

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
        SleepInterruptProcess process = SleepInterruptProcess.forGracefulWaitInterruption();

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
        SleepInterruptProcess process = SleepInterruptProcess.forForcefulWaitInterruption();

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

    private static final class InterruptingCleanupProcess extends Process {

        private final AtomicBoolean forceRequested = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final MutableProcessHandle descendant;
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
            descendant = failsOnGracefulAndForcefulDestroy(53, gracefulFailure, forceFailure);
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

        private MutableProcessHandle descendant() {
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

    private static final class SleepInterruptProcess extends Process {

        private final InterruptedWait interruptedWait;
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
                if (rootForceSignals.incrementAndGet() >= interruptedWait.forceSignalsBeforeExit()) {
                    alive.set(false);
                }
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

        private SleepInterruptProcess(InterruptedWait interruptedWait) {
            this.interruptedWait = interruptedWait;
        }

        private static SleepInterruptProcess forGracefulWaitInterruption() {
            return new SleepInterruptProcess(InterruptedWait.GRACEFUL);
        }

        private static SleepInterruptProcess forForcefulWaitInterruption() {
            return new SleepInterruptProcess(InterruptedWait.FORCEFUL);
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
            if (rootForceSignals.incrementAndGet() >= interruptedWait.forceSignalsBeforeExit()) {
                alive.set(false);
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() >= interruptedWait.livenessCallsBeforePolling()) {
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

        private MutableProcessHandle descendant() {
            return descendant;
        }
    }

    private enum InterruptedWait {
        GRACEFUL(1),
        FORCEFUL(2);

        private final int forceSignalsBeforeExit;

        InterruptedWait(int forceSignalsBeforeExit) {
            this.forceSignalsBeforeExit = forceSignalsBeforeExit;
        }

        private int forceSignalsBeforeExit() {
            return forceSignalsBeforeExit;
        }

        private int livenessCallsBeforePolling() {
            return forceSignalsBeforeExit + 1;
        }
    }
}
