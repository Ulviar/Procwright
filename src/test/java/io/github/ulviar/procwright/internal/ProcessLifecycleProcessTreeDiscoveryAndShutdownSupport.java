/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class ProcessLifecycleProcessTreeDiscoveryAndShutdownSupport extends ProcessLifecycleSharedSupport {
    static final class LateDescendantProcess extends Process {

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

        MutableProcessHandle descendant() {
            return descendant;
        }
    }

    static final class SpawnInProgressProcess extends Process {

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

        boolean descendantSignalledWhileRootAlive() {
            return descendantSignalledWhileRootAlive.get();
        }

        boolean rootSurvivedFirstPostDiscoveryPoll() {
            return rootSurvivedFirstPostDiscoveryPoll.get();
        }

        MutableProcessHandle descendant() {
            return descendant;
        }
    }

    static final class FalseGracefulResultProcess extends Process {

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

        boolean shutdownHookCreatedDescendant() {
            return descendantCreated.get();
        }

        int processDestroyCalls() {
            return processDestroyCalls.get();
        }

        MutableProcessHandle descendant() {
            return descendant;
        }
    }

    static final class SecurityRestrictedProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();
        private final RuntimeException descendantFailure;

        SecurityRestrictedProcess() {
            this(new SecurityException("descendant enumeration is denied"));
        }

        SecurityRestrictedProcess(RuntimeException descendantFailure) {
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

        void complete() {
            alive.set(false);
        }

        int destroyCalls() {
            return destroyCalls.get();
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    static final class ForceLateDescendantProcess extends Process {

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

        MutableProcessHandle descendant() {
            return descendant;
        }
    }

    static final class SuccessiveDescendantsHandle implements ProcessHandle {

        private final long pid;
        private final int descendantLimit;
        private final AtomicInteger discoveryCalls = new AtomicInteger();

        SuccessiveDescendantsHandle(long pid, int descendantLimit) {
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

        int discoveryCalls() {
            return discoveryCalls.get();
        }
    }

    static final class UnobservableProcessHandle implements ProcessHandle {

        private final long pid;
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        UnobservableProcessHandle(long pid) {
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

        int destroyCalls() {
            return destroyCalls.get();
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }
}
