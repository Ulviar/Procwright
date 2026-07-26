/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeShutdownProviderDeadlineTest extends ProcessLifecycleSharedSupport {

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
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> ProcessLifecycle.stop(
                            scanner.guard(delegate),
                            KnownDescendants.empty(),
                            ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofMillis(250))));

            CommandExecutionException providerFailure = failureSources(failure).stream()
                    .filter(CommandExecutionException.class::isInstance)
                    .map(CommandExecutionException.class::cast)
                    .filter(ProcessTreeScanner::causedByOperationDeadline)
                    .findFirst()
                    .orElseThrow();
            assertTrue(ProcessTreeScanner.causedByOperationDeadline(providerFailure));
            failureSourceContaining(failure, "procwright-provider-liveness-");
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

    private static final class DelayedPostSignalExitProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();
        private final int exitOnLivenessCall;
        private final long gracefulSignalDelayMillis;
        private final long forcefulSignalDelayMillis;
        private final ProcessHandle rootHandle = new MutableProcessHandle(60) {
            @Override
            public boolean destroy() {
                sleepUninterruptibly(gracefulSignalDelayMillis);
                return true;
            }

            @Override
            public boolean destroyForcibly() {
                forceDestroyCalls.incrementAndGet();
                sleepUninterruptibly(forcefulSignalDelayMillis);
                return true;
            }

            @Override
            public boolean isAlive() {
                return alive.get();
            }
        };

        private DelayedPostSignalExitProcess(
                int exitOnLivenessCall, long gracefulSignalDelayMillis, long forcefulSignalDelayMillis) {
            this.exitOnLivenessCall = exitOnLivenessCall;
            this.gracefulSignalDelayMillis = gracefulSignalDelayMillis;
            this.forcefulSignalDelayMillis = forcefulSignalDelayMillis;
        }

        private static DelayedPostSignalExitProcess afterGracefulSignal() {
            return new DelayedPostSignalExitProcess(2, 120, 0);
        }

        private static DelayedPostSignalExitProcess afterForcefulSignal() {
            return new DelayedPostSignalExitProcess(4, 0, 120);
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
            return 23;
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
            return 23;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            forceDestroyCalls.incrementAndGet();
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() == exitOnLivenessCall) {
                sleepUninterruptibly(120);
                alive.set(false);
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
            return forceDestroyCalls.get();
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

    private static void sleepUninterruptibly(long milliseconds) {
        boolean restoreInterrupt = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException interruption) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }
}
