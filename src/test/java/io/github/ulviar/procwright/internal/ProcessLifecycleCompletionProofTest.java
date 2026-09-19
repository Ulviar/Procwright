/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.CompletedProcess;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSourceContaining;
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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleCompletionProofTest {
    @Test
    void unobservableKnownDescendantIsSignalledButCannotProveGracefulCleanup() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(45);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        new CompletedProcess(),
                        knownDescendants(descendant),
                        ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100))));

        assertEquals(1, descendant.destroyCalls());
        assertEquals(1, descendant.forceDestroyCalls());
        assertTrue(failure.getMessage().contains("did not exit after forceful termination"));
    }

    @Test
    void unobservableKnownDescendantIsSignalledButCannotProveForcefulCleanup() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(46);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(
                        new CompletedProcess(), knownDescendants(descendant), Duration.ofMillis(100)));

        assertEquals(0, descendant.destroyCalls());
        assertEquals(1, descendant.forceDestroyCalls());
        assertTrue(failure.getMessage().contains("did not exit during forceful cleanup"));
    }

    @Test
    void finalStabilizationScanCannotTurnItsOwnTimeoutIntoSuccess() throws Exception {
        FinalScanBlockingProcess process = new FinalScanBlockingProcess();
        MutableProcessHandle exited = new MutableProcessHandle(48);
        exited.destroyForcibly();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(process, knownDescendants(exited), Duration.ofMillis(100)));

        assertTrue(failure.getMessage().contains("did not exit during forceful cleanup"));
        assertTrue(process.scanCalls() >= 3);
        assertTrue(eventually(() -> ProcessTreeScanner.shared().availableOperationPermits()
                == ProcessTreeScanner.SHARED_OPERATION_CAPACITY));
    }

    @Test
    void initialScanDeadlineCannotProveAnAlreadyExitedRootTree() throws Exception {
        InitialScanBlockingExitedProcess process = new InitialScanBlockingExitedProcess();
        FutureTask<Throwable> cleanup = new FutureTask<>(() -> {
            try {
                ProcessLifecycle.forceStop(process, Duration.ofMillis(25));
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        Thread caller = new Thread(cleanup, "initial-scan-deadline-cleanup-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(process.awaitScan());
            Throwable failure = cleanup.get(1, TimeUnit.SECONDS);

            assertTrue(failure instanceof CommandExecutionException);
            assertTrue(failure.getMessage().contains("did not exit during forceful cleanup"));
            assertEquals(0, process.forceDestroyCalls());
        } finally {
            process.releaseScan();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertFalse(caller.isAlive());
        assertTrue(eventually(() -> ProcessTreeScanner.shared().availableOperationPermits()
                == ProcessTreeScanner.SHARED_OPERATION_CAPACITY));
    }

    @Test
    void descendantOverflowCannotProduceASuccessfulCompletionProof() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        Set<ProcessHandle> known = new LinkedHashSet<>();
        for (int index = 0; index < limit; index++) {
            MutableProcessHandle exited = new MutableProcessHandle(50_000L + index);
            exited.destroyForcibly();
            known.add(exited);
        }
        OverflowAfterInitializationProcess process = new OverflowAfterInitializationProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(process, knownDescendants(known), Duration.ofSeconds(2)));

        failureSourceContaining(failure, "bounded descendant limit");
        assertEquals(0, process.overflow().forceDestroyCalls());
    }

    private static final class UnobservableProcessHandle implements ProcessHandle {

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

    private static final class FinalScanBlockingProcess extends Process {

        private final AtomicInteger scanCalls = new AtomicInteger();

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
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Stream<ProcessHandle> descendants() {
            if (scanCalls.incrementAndGet() >= 3) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                while (deadline - System.nanoTime() > 0) {
                    LockSupport.parkNanos(deadline - System.nanoTime());
                    Thread.interrupted();
                }
            }
            return Stream.empty();
        }

        int scanCalls() {
            return scanCalls.get();
        }
    }

    private static final class InitialScanBlockingExitedProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger scanCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();
        private final CountDownLatch scanEntered = new CountDownLatch(1);
        private final CountDownLatch releaseScan = new CountDownLatch(1);

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
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
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
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (scanCalls.incrementAndGet() == 1) {
                alive.set(false);
                scanEntered.countDown();
                boolean restoreInterrupt = false;
                while (true) {
                    try {
                        releaseScan.await();
                        break;
                    } catch (InterruptedException interruption) {
                        restoreInterrupt = true;
                    }
                }
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
            return Stream.empty();
        }

        boolean awaitScan() throws InterruptedException {
            return scanEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseScan() {
            releaseScan.countDown();
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class OverflowAfterInitializationProcess extends Process {

        private final AtomicInteger scans = new AtomicInteger();
        private final MutableProcessHandle overflow = new MutableProcessHandle(60_000);

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
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Stream<ProcessHandle> descendants() {
            return scans.getAndIncrement() == 0 ? Stream.empty() : Stream.of(overflow);
        }

        MutableProcessHandle overflow() {
            return overflow;
        }
    }
}
