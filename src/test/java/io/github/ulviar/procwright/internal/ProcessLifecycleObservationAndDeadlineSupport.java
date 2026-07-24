/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class ProcessLifecycleObservationAndDeadlineSupport extends ProcessLifecycleSharedSupport {
    static class PollingCompletionProcess extends Process {

        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger timedWaitCalls = new AtomicInteger();
        private final int livePolls;

        PollingCompletionProcess() {
            this(1);
        }

        PollingCompletionProcess(int livePolls) {
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

        int livenessCalls() {
            return livenessCalls.get();
        }

        int timedWaitCalls() {
            return timedWaitCalls.get();
        }
    }

    static final class AdvancingPollClock implements ProcessExitWaiter.PollClock {

        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long durationNanos) {
            nanos += durationNanos;
        }

        void advance(long durationNanos) {
            nanos += durationNanos;
        }
    }

    static final class BlockingLivenessProcess extends Process {

        final CountDownLatch livenessEntered = new CountDownLatch(1);
        final CountDownLatch releaseLiveness = new CountDownLatch(1);

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

    static final class SecurityLivenessBlockingExitProcess extends Process {

        final CountDownLatch exitValueEntered = new CountDownLatch(1);
        final CountDownLatch releaseExitValue = new CountDownLatch(1);

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

    static final class DeadlineScriptedProcess extends Process {

        private final boolean blockGracefulWaitLiveness;
        private final boolean completeWhenLivenessIsInterrupted;
        private final boolean completeOnGracefulDestroy;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueWhileAliveCalls = new AtomicInteger();
        final CountDownLatch gracefulWaitLivenessEntered = new CountDownLatch(1);
        final CountDownLatch releaseGracefulWaitLiveness = new CountDownLatch(1);
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

        DeadlineScriptedProcess(
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

        int forceDestroyCalls() {
            return ((MutableProcessHandle) rootHandle).forceDestroyCalls();
        }

        int exitValueWhileAliveCalls() {
            return exitValueWhileAliveCalls.get();
        }
    }

    static final class DeadlineScriptedProcessHandle extends MutableProcessHandle {

        private final boolean completeWhenLivenessIsInterrupted;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();
        final CountDownLatch gracefulWaitLivenessEntered = new CountDownLatch(1);
        final CountDownLatch releaseGracefulWaitLiveness = new CountDownLatch(1);

        DeadlineScriptedProcessHandle(long pid, boolean completeWhenLivenessIsInterrupted) {
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

        int recordedForceDestroyCalls() {
            return ((MutableProcessHandle) this).forceDestroyCalls();
        }
    }

    static final class ReparentingProcess extends Process {

        private final ProcessHandle initiallyVisibleDescendant;
        private int polls;
        private boolean alive = true;

        ReparentingProcess(ProcessHandle initiallyVisibleDescendant) {
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

    record TestProcessHandle(long pid, boolean alive) implements ProcessHandle {

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
