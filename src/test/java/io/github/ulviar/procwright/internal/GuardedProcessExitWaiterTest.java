/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class GuardedProcessExitWaiterTest extends ProcessLifecycleSharedSupport {

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
    void guardedProcessCompletionUsesLivenessPollingWithoutInvokingProviderWaitFor() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess();
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertTrue(ProcessExitWaiter.waitFor(scanner.guard(delegate), Duration.ofSeconds(1), descendants, clock));

        assertEquals(2, delegate.livenessCalls());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void guardedProcessGetsAFinalLivenessProbeDuringTheLastPollInterval() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess(3);
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertTrue(ProcessExitWaiter.waitFor(scanner.guard(delegate), Duration.ofMillis(250), descendants, clock));

        assertEquals(4, delegate.livenessCalls());
        assertEquals(0, delegate.timedWaitCalls());
    }

    @Test
    void guardedProcessTimeoutCompletesWithoutInvokingProviderWaitFor() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        PollingCompletionProcess delegate = new PollingCompletionProcess(Integer.MAX_VALUE);
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        AdvancingPollClock clock = new AdvancingPollClock();

        assertFalse(ProcessExitWaiter.waitFor(scanner.guard(delegate), Duration.ofMillis(250), descendants, clock));

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
                () -> ProcessExitWaiter.waitFor(
                        scanner.guard(delegate), Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));

        assertSame(providerFailure, observed);
    }

    @Test
    void guardedProcessDoesNotStartANewProviderProbeAfterTheDeadline() throws Exception {
        AdvancingClock clock = new AdvancingClock();
        GuardedDeadlineProcess delegate = new GuardedDeadlineProcess(clock);
        Process guarded = new ProcessTreeScanner(2, 4, Duration.ofMillis(25)).guard(delegate);

        assertFalse(ProcessExitWaiter.waitFor(guarded, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));

        assertEquals(1, delegate.livenessCalls.get());
    }

    @Test
    void guardedWaitPropagatesTheOriginalSleepInterruption() {
        InterruptedException expected = new InterruptedException("stop waiting");
        Process guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guard(new AlwaysLiveProcess());
        ProcessExitWaiter.PollClock clock = new ProcessExitWaiter.PollClock() {
            @Override
            public long nanoTime() {
                return 0;
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                throw expected;
            }
        };

        InterruptedException actual = assertThrows(
                InterruptedException.class,
                () -> ProcessExitWaiter.waitFor(guarded, Duration.ofSeconds(1), new LiveDescendantSnapshot(), clock));

        assertSame(expected, actual);
    }

    private abstract static class TestProcess extends Process {

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
            if (isAlive()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class AlwaysLiveProcess extends TestProcess {

        @Override
        public boolean isAlive() {
            return true;
        }
    }

    private static final class GuardedDeadlineProcess extends TestProcess {

        private final AdvancingClock clock;
        private final AtomicInteger livenessCalls = new AtomicInteger();

        private GuardedDeadlineProcess(AdvancingClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isAlive() {
            livenessCalls.incrementAndGet();
            return true;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            clock.nanos = Duration.ofMillis(250).toNanos();
            return Stream.empty();
        }
    }

    private static final class AdvancingClock implements ProcessExitWaiter.PollClock {

        private volatile long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long durationNanos) {
            nanos += durationNanos;
        }
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

    private static final class AdvancingPollClock implements ProcessExitWaiter.PollClock {

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
}
