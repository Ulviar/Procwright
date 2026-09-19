/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessShutdownSignalsTest {

    @Test
    void descendantsAreSignalledInReverseDiscoveryOrder() {
        List<Long> order = new ArrayList<>();
        RecordingHandle first = new RecordingHandle(501, order);
        RecordingHandle second = new RecordingHandle(502, order);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState tree = initializedTree(new LinkedHashSet<>(List.of(first, second)), failures);
        ProcessShutdownSignals signals = new ProcessShutdownSignals(
                new RootProcess(new RecordingHandle(503, order)), tree, failures, (prefix, action) -> action.run());

        signals.destroyDescendants(ShutdownPhase.FORCEFUL);

        assertEquals(List.of(502L, 501L), order);
    }

    @Test
    void fullTreeSignalConsumesPendingDescendants() {
        RecordingHandle descendant = new RecordingHandle(508, new ArrayList<>());
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        RootProcess process = new RootProcess(new RecordingHandle(509, new ArrayList<>())) {
            @Override
            public Stream<ProcessHandle> descendants() {
                return Stream.of(descendant);
            }
        };
        ShutdownTreeState tree = new ShutdownTreeState(process, failures);
        tree.initialize(KnownDescendants.empty(), Duration.ofSeconds(1));
        tree.discoverPending(Duration.ofSeconds(1));
        ProcessShutdownSignals signals =
                new ProcessShutdownSignals(process, tree, failures, (prefix, action) -> action.run());

        signals.destroyDescendants(ShutdownPhase.FORCEFUL);
        signals.signalPendingDescendants(ShutdownPhase.FORCEFUL);

        assertEquals(1, descendant.forcefulCalls.get());
    }

    @Test
    void falseGracefulHandleResultDoesNotUseProcessFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        RecordingHandle rootHandle = new RecordingHandle(504, new ArrayList<>());
        rootHandle.signalResult = false;
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState tree = initializedTree(Set.of(), failures);
        ProcessShutdownSignals signals = new ProcessShutdownSignals(
                new RootProcess(rootHandle), tree, failures, (prefix, action) -> fallbackCalls.incrementAndGet());

        signals.destroyRoot(ShutdownPhase.GRACEFUL);

        assertEquals(0, fallbackCalls.get());
        assertEquals(1, rootHandle.gracefulCalls.get());
    }

    @Test
    void falseForcefulHandleResultUsesTheBoundedProcessFallback() {
        RecordingHandle rootHandle = new RecordingHandle(505, new ArrayList<>());
        rootHandle.signalResult = false;
        RootProcess process = new RootProcess(rootHandle);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState tree = initializedTree(Set.of(), failures);
        ProcessShutdownSignals signals =
                new ProcessShutdownSignals(process, tree, failures, (prefix, action) -> action.run());

        signals.destroyRoot(ShutdownPhase.FORCEFUL);

        assertEquals(1, rootHandle.forcefulCalls.get());
        assertEquals(1, process.forcefulFallbackCalls.get());
    }

    @Test
    void forcefulSignalFailureRetainsIdentityAndExcludesTheHandleFromCompletionProof() {
        AssertionError expected = new AssertionError("signal failed");
        RecordingHandle failing = new RecordingHandle(506, new ArrayList<>());
        failing.signalFailure = expected;
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState tree = initializedTree(Set.of(failing), failures);
        ProcessShutdownSignals signals = new ProcessShutdownSignals(
                new RootProcess(new RecordingHandle(507, new ArrayList<>())),
                tree,
                failures,
                (prefix, action) -> action.run());

        signals.destroyDescendants(ShutdownPhase.FORCEFUL);

        AssertionError actual = assertThrows(AssertionError.class, failures::rethrowIfPresent);
        assertSame(expected, actual);
        assertSame(ShutdownTreeState.DescendantState.EXITED, tree.observeDescendants());
    }

    @Test
    void unavailableRootHandleUsesTheProcessFallback() {
        RootProcess delegate = new RootProcess(new RecordingHandle(515, new ArrayList<>())) {
            @Override
            public ProcessHandle toHandle() {
                toHandleCalls.incrementAndGet();
                throw new SecurityException("handle unavailable");
            }
        };
        Process process = delegate;
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState tree = new ShutdownTreeState(process, failures);
        ProcessShutdownSignals signals =
                new ProcessShutdownSignals(process, tree, failures, (prefix, action) -> action.run());

        signals.destroyRoot(ShutdownPhase.FORCEFUL);

        assertEquals(1, delegate.toHandleCalls.get());
        assertEquals(1, delegate.forcefulFallbackCalls.get());
    }

    private static ShutdownTreeState initializedTree(Set<ProcessHandle> handles, ShutdownFailureLedger failures) {
        ShutdownTreeState tree =
                new ShutdownTreeState(new RootProcess(new RecordingHandle(599, new ArrayList<>())), failures);
        tree.initialize(knownDescendants(handles), Duration.ofSeconds(1));
        return tree;
    }

    private static class RootProcess extends Process {

        private final ProcessHandle handle;
        private final AtomicInteger forcefulFallbackCalls = new AtomicInteger();
        final AtomicInteger toHandleCalls = new AtomicInteger();

        private RootProcess(ProcessHandle handle) {
            this.handle = handle;
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
        public int exitValue() {
            throw new IllegalThreadStateException("process is alive");
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            forcefulFallbackCalls.incrementAndGet();
            return this;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public ProcessHandle toHandle() {
            toHandleCalls.incrementAndGet();
            return handle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class RecordingHandle implements ProcessHandle {

        private final long pid;
        private final List<Long> order;
        private final AtomicInteger gracefulCalls = new AtomicInteger();
        private final AtomicInteger forcefulCalls = new AtomicInteger();
        private boolean signalResult = true;
        private Throwable signalFailure;
        private boolean alive = true;

        private RecordingHandle(long pid, List<Long> order) {
            this.pid = pid;
            this.order = order;
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
            gracefulCalls.incrementAndGet();
            return signal(false);
        }

        @Override
        public boolean destroyForcibly() {
            forcefulCalls.incrementAndGet();
            return signal(true);
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        private boolean signal(boolean forceful) {
            order.add(pid);
            if (signalFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (signalFailure instanceof Error error) {
                throw error;
            }
            if (signalResult) {
                alive = false;
            }
            return signalResult;
        }
    }
}
