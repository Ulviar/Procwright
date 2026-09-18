/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.BlockingLivenessProcess;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSourceContaining;
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

final class ProcessTreeShutdownProviderDeadlineTest {

    private static final Duration POST_SIGNAL_WAIT_BUDGET = Duration.ofSeconds(1);
    // Each delay fits the wait budget, but together they outlive the original operation deadline.
    private static final long POST_SIGNAL_DELAY_MILLIS = 600;

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
            assertTrue(delegate.awaitLivenessEntry(1, TimeUnit.SECONDS));
            Throwable failure = cleanup.get(1, TimeUnit.SECONDS);

            assertTrue(failure instanceof CommandExecutionException, () -> "unexpected failure: " + failure);
            assertEquals(0, scanner.availableOperationPermits());
        } finally {
            delegate.releaseLiveness();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertFalse(caller.isAlive());
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void guardedShutdownTreatsLivenessTimeoutAtLifecycleDeadlineAsUnknownAndEscalates() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofSeconds(5));
        DeadlineScriptedProcess delegate = DeadlineScriptedProcess.lifecycleDeadlineExpiresFirst();
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
        DeadlineScriptedProcess delegate = DeadlineScriptedProcess.providerDeadlineExpiresFirst();
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(25), Duration.ofSeconds(5));
        DelayedPostSignalExitProcess delegate = DelayedPostSignalExitProcess.afterGracefulSignal();

        OptionalInt exitCode = ProcessLifecycle.stop(
                scanner.guard(delegate),
                KnownDescendants.empty(),
                ShutdownPolicy.interruptThenKill(POST_SIGNAL_WAIT_BUDGET, POST_SIGNAL_WAIT_BUDGET));

        assertEquals(23, exitCode.orElseThrow());
        assertEquals(0, delegate.forceDestroyCalls());
    }

    @Test
    void guardedExitCodeUsesThePostForceSignalWaitDeadline() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(25), Duration.ofSeconds(5));
        DelayedPostSignalExitProcess delegate = DelayedPostSignalExitProcess.afterForcefulSignal();

        OptionalInt exitCode = ProcessLifecycle.stop(
                scanner.guard(delegate),
                KnownDescendants.empty(),
                ShutdownPolicy.interruptThenKill(Duration.ZERO, POST_SIGNAL_WAIT_BUDGET));

        assertEquals(23, exitCode.orElseThrow());
        assertEquals(1, delegate.forceDestroyCalls());
    }

    @Test
    void guardedDescendantLivenessTimeoutAtLifecycleDeadlineRemainsLiveAndIsForceStopped() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(4, 4, Duration.ofMillis(10), Duration.ofSeconds(5));
        DeadlineScriptedProcess root = DeadlineScriptedProcess.completesOnGracefulSignal();
        DeadlineScriptedProcessHandle descendant = DeadlineScriptedProcessHandle.lifecycleDeadlineExpiresFirst(62);
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
        DeadlineScriptedProcess root = DeadlineScriptedProcess.completesOnGracefulSignal();
        DeadlineScriptedProcessHandle descendant = DeadlineScriptedProcessHandle.providerDeadlineExpiresFirst(63);
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
            return new DelayedPostSignalExitProcess(2, POST_SIGNAL_DELAY_MILLIS, 0);
        }

        private static DelayedPostSignalExitProcess afterForcefulSignal() {
            return new DelayedPostSignalExitProcess(4, 0, POST_SIGNAL_DELAY_MILLIS);
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
                sleepUninterruptibly(POST_SIGNAL_DELAY_MILLIS);
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

        private final DeadlineScenario scenario;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueWhileAliveCalls = new AtomicInteger();
        private final CountDownLatch gracefulWaitLivenessEntered = new CountDownLatch(1);
        private final CountDownLatch releaseGracefulWaitLiveness = new CountDownLatch(1);
        private final ProcessHandle rootHandle = new MutableProcessHandle(61) {
            @Override
            public boolean destroy() {
                if (scenario == DeadlineScenario.COMPLETES_ON_GRACEFUL_SIGNAL) {
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

        private DeadlineScriptedProcess(DeadlineScenario scenario) {
            this.scenario = scenario;
        }

        private static DeadlineScriptedProcess lifecycleDeadlineExpiresFirst() {
            return new DeadlineScriptedProcess(DeadlineScenario.LIFECYCLE_DEADLINE_EXPIRES_FIRST);
        }

        private static DeadlineScriptedProcess providerDeadlineExpiresFirst() {
            return new DeadlineScriptedProcess(DeadlineScenario.PROVIDER_DEADLINE_EXPIRES_FIRST);
        }

        private static DeadlineScriptedProcess completesOnGracefulSignal() {
            return new DeadlineScriptedProcess(DeadlineScenario.COMPLETES_ON_GRACEFUL_SIGNAL);
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
            if (livenessCalls.incrementAndGet() == 2 && scenario != DeadlineScenario.COMPLETES_ON_GRACEFUL_SIGNAL) {
                gracefulWaitLivenessEntered.countDown();
                try {
                    releaseGracefulWaitLiveness.await();
                } catch (InterruptedException expected) {
                    if (scenario == DeadlineScenario.PROVIDER_DEADLINE_EXPIRES_FIRST) {
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

        private final DeadlineScenario scenario;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final CountDownLatch gracefulWaitLivenessEntered = new CountDownLatch(1);
        private final CountDownLatch releaseGracefulWaitLiveness = new CountDownLatch(1);

        private DeadlineScriptedProcessHandle(long pid, DeadlineScenario scenario) {
            super(pid);
            this.scenario = scenario;
        }

        private static DeadlineScriptedProcessHandle lifecycleDeadlineExpiresFirst(long pid) {
            return new DeadlineScriptedProcessHandle(pid, DeadlineScenario.LIFECYCLE_DEADLINE_EXPIRES_FIRST);
        }

        private static DeadlineScriptedProcessHandle providerDeadlineExpiresFirst(long pid) {
            return new DeadlineScriptedProcessHandle(pid, DeadlineScenario.PROVIDER_DEADLINE_EXPIRES_FIRST);
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
                    if (scenario == DeadlineScenario.PROVIDER_DEADLINE_EXPIRES_FIRST) {
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

    private enum DeadlineScenario {
        LIFECYCLE_DEADLINE_EXPIRES_FIRST,
        PROVIDER_DEADLINE_EXPIRES_FIRST,
        COMPLETES_ON_GRACEFUL_SIGNAL
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
