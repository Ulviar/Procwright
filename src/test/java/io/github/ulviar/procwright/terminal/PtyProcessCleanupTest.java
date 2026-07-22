/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PtyProcessCleanupTest {

    @Test
    void infiniteDescendantDiscoveryIsIdentityBoundedAndSignalsRootFirst() {
        PtyTestProcess root = PtyTestProcess.hanging();
        AtomicBoolean rootWasSignalledBeforeDiscovery = new AtomicBoolean();
        AtomicBoolean streamClosed = new AtomicBoolean();
        AtomicInteger generated = new AtomicInteger();
        List<TestHandle> handles = new ArrayList<>();

        PtyProcessCleanup.CleanupResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> PtyProcessCleanup.terminate(
                        root,
                        ignored -> Stream.<ProcessHandle>generate(() -> {
                                    rootWasSignalledBeforeDiscovery.compareAndSet(false, root.destroyed());
                                    TestHandle handle = new TestHandle(generated.incrementAndGet());
                                    handles.add(handle);
                                    return handle;
                                })
                                .onClose(() -> streamClosed.set(true)),
                        Duration.ofMillis(250),
                        32));

        assertTrue(rootWasSignalledBeforeDiscovery.get());
        assertTrue(streamClosed.get());
        assertTrue(result.truncated());
        assertEquals(32, result.discoveredCount());
        assertEquals(32, generated.get());
        assertEquals(32, handles.size());
        assertTrue(handles.stream().allMatch(TestHandle::normalTerminationRequested));
        assertTrue(handles.stream().allMatch(TestHandle::forcedTerminationRequested));
    }

    @Test
    void cleanupUsesOneAbsoluteDeadlineAcrossRepeatedDiscovery() {
        PtyTestProcess root = PtyTestProcess.hanging();
        AtomicInteger generated = new AtomicInteger();

        PtyProcessCleanup.CleanupResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> PtyProcessCleanup.terminate(
                        root,
                        ignored -> Stream.<ProcessHandle>generate(() -> {
                            LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                            return new TestHandle(generated.incrementAndGet());
                        }),
                        Duration.ofMillis(25),
                        256));

        assertTrue(result.truncated());
        assertTrue(result.discoveredCount() < 100);
        assertTrue(root.destroyed());
    }

    private static final class TestHandle implements ProcessHandle {

        private final long pid;
        private final AtomicBoolean normalTerminationRequested = new AtomicBoolean();
        private final AtomicBoolean forcedTerminationRequested = new AtomicBoolean();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private TestHandle(long pid) {
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
            return Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return new CompletableFuture<>();
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            normalTerminationRequested.set(true);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forcedTerminationRequested.set(true);
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestHandle;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        private boolean normalTerminationRequested() {
            return normalTerminationRequested.get();
        }

        private boolean forcedTerminationRequested() {
            return forcedTerminationRequested.get();
        }
    }
}
