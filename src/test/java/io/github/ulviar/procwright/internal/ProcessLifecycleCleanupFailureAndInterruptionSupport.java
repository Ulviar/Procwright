/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class ProcessLifecycleCleanupFailureAndInterruptionSupport extends ProcessLifecycleSharedSupport {
    static final class ProcessTreeFailureProcess extends Process {

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

        ProcessTreeFailureProcess(
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

        ThrowingProcessHandle firstDescendant() {
            return firstDescendant;
        }

        ThrowingProcessHandle secondDescendant() {
            return secondDescendant;
        }

        int rootGracefulHandleCalls() {
            return rootGracefulHandleCalls.get();
        }

        int rootGracefulFallbackCalls() {
            return rootGracefulFallbackCalls.get();
        }

        int rootForceHandleCalls() {
            return rootForceHandleCalls.get();
        }

        int rootForceFallbackCalls() {
            return rootForceFallbackCalls.get();
        }
    }

    static final class InterruptingCleanupProcess extends Process {

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

        InterruptingCleanupProcess(Throwable gracefulFailure, Throwable forceFailure) {
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

        ThrowingProcessHandle descendant() {
            return descendant;
        }

        int rootForceFallbackCalls() {
            return rootForceFallbackCalls.get();
        }

        boolean interruptedDuringForcefulWait() {
            return interruptedDuringForcefulWait.get();
        }
    }

    static final class FallbackObservationInterruptedProcess extends Process {

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

        FallbackObservationDescendant descendant() {
            return descendant;
        }

        int rootGracefulFallbackCalls() {
            return rootGracefulFallbackCalls.get();
        }

        boolean interruptedDuringForcefulCleanup() {
            return interruptedDuringForcefulCleanup.get();
        }
    }

    static final class FallbackObservationDescendant extends MutableProcessHandle {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean interruptedDuringForcefulCleanup;
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        FallbackObservationDescendant(AtomicBoolean interruptedDuringForcefulCleanup) {
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

        int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    static final class ThrowingProcessHandle extends MutableProcessHandle {

        private final Throwable gracefulFailure;
        private final Throwable forceFailure;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        ThrowingProcessHandle(long pid, Throwable gracefulFailure, Throwable forceFailure) {
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

        int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        int forceDestroyCalls() {
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

    static final class BlockingDestroyProcess extends Process {

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

        void release() {
            release.countDown();
        }

        int startedCalls() {
            return started.get();
        }

        int finishedCalls() {
            return finished.get();
        }
    }

    static final class LivenessRestrictedProcess extends Process {

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

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }
}
