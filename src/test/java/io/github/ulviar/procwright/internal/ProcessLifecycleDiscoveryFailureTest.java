/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.CompletedProcess;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSourceContaining;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSources;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleDiscoveryFailureTest {
    @Test
    void descendantEnumerationSecurityFailureStillStopsRootAndReportsIncompleteTree() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100))));

        failureSourceContaining(failure, "discovery did not complete");
        assertFalse(process.isAlive());
        assertEquals(1, process.destroyCalls());
        assertEquals(0, process.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationSecurityFailureStillForceStopsRootAndReportsIncompleteTree() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class, () -> ProcessLifecycle.forceStop(process, Duration.ofMillis(100)));

        failureSourceContaining(failure, "discovery did not complete");
        assertFalse(process.isAlive());
        assertEquals(0, process.destroyCalls());
        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void fatalDiscoveryStillCleansTheObservedPrefixBeforeRethrowing() {
        AssertionError expected = new AssertionError("fatal discovery after prefix");
        FatalPrefixProcess process = new FatalPrefixProcess(expected);

        Error actual = assertThrows(Error.class, () -> ProcessLifecycle.forceStop(process, Duration.ofMillis(100)));

        assertSame(expected, actual.getCause());
        assertTrue(failureSources(actual).contains(expected));
        assertFalse(process.isAlive());
        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().forceDestroyCalls());
    }

    @Test
    void fatalKnownRootTraversalStillCleansChildrenFromEarlierRoots() {
        MutableProcessHandle child = new MutableProcessHandle(40);
        MutableProcessHandle firstRoot = new MutableProcessHandle(41) {
            @Override
            public Stream<ProcessHandle> children() {
                return Stream.of(child);
            }
        };
        AssertionError expected = new AssertionError("later root children failed");
        MutableProcessHandle failingRoot = new MutableProcessHandle(42) {
            @Override
            public Stream<ProcessHandle> children() {
                throw expected;
            }
        };

        Error actual = assertThrows(
                Error.class,
                () -> ProcessLifecycle.forceStop(
                        new CompletedProcess(), knownDescendants(firstRoot, failingRoot), Duration.ofMillis(100)));

        assertSame(expected, actual.getCause());
        assertTrue(failureSources(actual).contains(expected));
        assertFalse(child.isAlive());
        assertEquals(1, child.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationRuntimeFailureDegradesNaturalWaitButFailsShutdownProof() throws Exception {
        IllegalStateException enumerationFailure = new IllegalStateException("sysctl descendant lookup failed");
        SecurityRestrictedProcess completed = new SecurityRestrictedProcess(enumerationFailure);
        completed.complete();
        LiveDescendantSnapshot observed = new LiveDescendantSnapshot();

        assertTrue(ProcessLifecycle.waitFor(completed, Duration.ofSeconds(1), observed));
        assertTrue(observed.current().isEmpty());

        SecurityRestrictedProcess graceful = new SecurityRestrictedProcess(enumerationFailure);
        CommandExecutionException gracefulFailure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        graceful, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100))));
        failureSourceContaining(gracefulFailure, "discovery did not complete");
        assertFalse(graceful.isAlive());
        assertEquals(1, graceful.destroyCalls());

        SecurityRestrictedProcess forceful = new SecurityRestrictedProcess(enumerationFailure);
        CommandExecutionException forcefulFailure = assertThrows(
                CommandExecutionException.class, () -> ProcessLifecycle.forceStop(forceful, Duration.ofMillis(100)));
        failureSourceContaining(forcefulFailure, "discovery did not complete");
        assertFalse(forceful.isAlive());
        assertEquals(1, forceful.forceDestroyCalls());
    }

    @Test
    void unavailableWatcherSnapshotPreservesItsTypedCleanupFailure() {
        KnownDescendants unavailable = KnownDescendants.copyOf(new java.util.LinkedHashMap<>(), false, true);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(new CompletedProcess(), unavailable, Duration.ofMillis(100)));

        failureSourceContaining(failure, "discovery did not complete");
    }

    private static final class FatalPrefixProcess extends Process {

        private final AssertionError failure;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final MutableProcessHandle descendant = new MutableProcessHandle(36);
        private final ProcessHandle rootHandle = new MutableProcessHandle(35) {
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

        FatalPrefixProcess(AssertionError failure) {
            this.failure = failure;
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
        public void destroy() {
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
            return Stream.concat(Stream.of(descendant), Stream.generate(() -> {
                throw failure;
            }));
        }

        MutableProcessHandle descendant() {
            return descendant;
        }
    }

    private static final class SecurityRestrictedProcess extends Process {

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
}
