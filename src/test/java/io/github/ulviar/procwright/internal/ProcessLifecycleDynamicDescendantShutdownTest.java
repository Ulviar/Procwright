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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleDynamicDescendantShutdownTest extends ProcessLifecycleSharedSupport {
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
    void pendingGracefulDescendantReceivesOneForcefulSignalAfterEscalation() {
        PendingAcrossPhaseProcess process = new PendingAcrossPhaseProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertEquals(0, process.descendant().gracefulDestroyCalls());
        assertEquals(1, process.descendant().forceDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void forcefulPhaseCannotEraseAGracefulDynamicDiscoveryGapAfterReparenting() {
        ReparentingDeadlineProcess process = new ReparentingDeadlineProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(25), Duration.ofMillis(50))));

        assertTrue(failure.getMessage().contains("did not exit after forceful termination"));
        assertTrue(process.hiddenDescendant().isAlive());
        assertEquals(0, process.hiddenDescendant().forceDestroyCalls());
        process.hiddenDescendant().destroyForcibly();
    }

    @Test
    void falseGracefulHandleResultDoesNotCloseOutputThroughProcessFallback() {
        FalseGracefulResultProcess process = new FalseGracefulResultProcess();

        ProcessTreeShutdown.stop(
                process,
                KnownDescendants.empty(),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                (threadPrefix, action) -> action.run());

        assertTrue(process.shutdownHookCreatedDescendant());
        assertEquals(0, process.processDestroyCalls());
        assertEquals(1, process.descendant().gracefulDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void forcefulWaitDiscoversAndForceStopsLateDescendant() {
        ForceLateDescendantProcess process = new ForceLateDescendantProcess();

        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().forceDestroyCalls());
    }

    private static final class ReparentingDeadlineProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean gracefulSignalled = new AtomicBoolean();
        private final AtomicInteger scans = new AtomicInteger();
        private final MutableProcessHandle hiddenDescendant = new MutableProcessHandle(38);
        private final ProcessHandle rootHandle = new MutableProcessHandle(37) {
            @Override
            public boolean destroy() {
                gracefulSignalled.set(true);
                return true;
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
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            gracefulSignalled.set(true);
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
            int scan = scans.incrementAndGet();
            if (scan == 2 && gracefulSignalled.get()) {
                alive.set(false);
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expectedDeadlineCancellation) {
                    return Stream.of(hiddenDescendant);
                }
            }
            return Stream.empty();
        }

        MutableProcessHandle hiddenDescendant() {
            return hiddenDescendant;
        }
    }

    private static final class PendingAcrossPhaseProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean descendantVisible = new AtomicBoolean();
        private final MutableProcessHandle descendant = new MutableProcessHandle(41);
        private final ProcessHandle rootHandle = new MutableProcessHandle(42) {
            @Override
            public boolean destroy() {
                descendantVisible.set(true);
                return true;
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

        MutableProcessHandle descendant() {
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

        MutableProcessHandle descendant() {
            return descendant;
        }
    }
}
