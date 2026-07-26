/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failsOnGracefulAndForcefulDestroy;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failsOnGracefulDestroy;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSourceContaining;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSources;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeShutdownFailureContinuationTest {

    @Test
    void rootLivenessFailureStillReachesForcefulDestroyFallback() throws Exception {
        LivenessRestrictedProcess process = new LivenessRestrictedProcess();

        assertFalse(ProcessLifecycle.waitFor(process, Duration.ofNanos(1), new LiveDescendantSnapshot()));
        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> ProcessLifecycle.forceStop(process, Duration.ofMillis(100)));

        failureSourceContaining(failure, "discovery did not complete");
        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void failingOrdinaryExitFallbackIsObservedOnceAndRetainsIdentity() {
        IllegalStateException expected = new IllegalStateException("exit observation failed");
        SingleFailingExitValueProcess process = new SingleFailingExitValueProcess(expected);

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                () -> ProcessLifecycle.forceStop(process, KnownDescendants.empty(), Duration.ZERO));

        assertTrue(failureSources(actual).contains(expected));
        assertEquals(1, process.exitValueCalls.get());
    }

    @Test
    void cleanupPreservesFirstFailureAndAttemptsEveryDescendantRootAndEscalationPhase() throws Exception {
        AssertionError firstDescendantFailure = new AssertionError("first descendant graceful failure");
        IllegalStateException secondDescendantFailure = new IllegalStateException("second descendant graceful failure");
        AssertionError rootGracefulFailure = new AssertionError("root graceful failure");
        IllegalStateException descendantForceFailure = new IllegalStateException("descendant force failure");
        AssertionError rootForceFailure = new AssertionError("root force failure");
        ProcessTreeFailureProcess process = new ProcessTreeFailureProcess(
                firstDescendantFailure,
                secondDescendantFailure,
                rootGracefulFailure,
                descendantForceFailure,
                rootForceFailure);
        LinkedHashSet<ProcessHandle> descendants = new LinkedHashSet<>();
        descendants.add(process.secondDescendant());
        descendants.add(process.firstDescendant());

        Error thrown = assertThrows(
                Error.class,
                () -> ProcessLifecycle.stop(
                        process,
                        knownDescendants(descendants),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofMillis(100))));

        assertSame(rootGracefulFailure, thrown.getCause());
        assertEquals(
                List.of(
                        rootGracefulFailure,
                        firstDescendantFailure,
                        secondDescendantFailure,
                        descendantForceFailure,
                        rootForceFailure),
                failureSources(thrown));
        assertEquals(1, process.firstDescendant().gracefulDestroyCalls());
        assertEquals(1, process.secondDescendant().gracefulDestroyCalls());
        assertEquals(1, process.rootGracefulHandleCalls());
        assertEquals(1, process.firstDescendant().forceDestroyCalls());
        assertEquals(1, process.secondDescendant().forceDestroyCalls());
        assertEquals(1, process.rootForceHandleCalls());
        assertTrue(eventually(() -> process.rootGracefulFallbackCalls() == 1));
        assertTrue(eventually(() -> process.rootForceFallbackCalls() == 1));
    }

    private static final class ProcessTreeFailureProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final MutableProcessHandle firstDescendant;
        private final MutableProcessHandle secondDescendant;
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

        private ProcessTreeFailureProcess(
                Throwable firstDescendantFailure,
                Throwable secondDescendantFailure,
                Throwable rootGracefulFailure,
                Throwable descendantForceFailure,
                Throwable rootForceFailure) {
            firstDescendant = failsOnGracefulDestroy(50, firstDescendantFailure);
            secondDescendant = failsOnGracefulAndForcefulDestroy(51, secondDescendantFailure, descendantForceFailure);
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

        private MutableProcessHandle firstDescendant() {
            return firstDescendant;
        }

        private MutableProcessHandle secondDescendant() {
            return secondDescendant;
        }

        private int rootGracefulHandleCalls() {
            return rootGracefulHandleCalls.get();
        }

        private int rootGracefulFallbackCalls() {
            return rootGracefulFallbackCalls.get();
        }

        private int rootForceHandleCalls() {
            return rootForceHandleCalls.get();
        }

        private int rootForceFallbackCalls() {
            return rootForceFallbackCalls.get();
        }
    }

    private static final class LivenessRestrictedProcess extends Process {

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

        private int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }
    }

    private static final class SingleFailingExitValueProcess extends Process {

        private final RuntimeException exitFailure;
        private final AtomicInteger livenessCalls = new AtomicInteger();
        private final AtomicInteger exitValueCalls = new AtomicInteger();
        private final ProcessHandle handle = new MutableProcessHandle(901);

        private SingleFailingExitValueProcess(RuntimeException exitFailure) {
            this.exitFailure = exitFailure;
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
            exitValueCalls.incrementAndGet();
            throw exitFailure;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            if (livenessCalls.incrementAndGet() <= 2) {
                return true;
            }
            throw new SecurityException("liveness unavailable");
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
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
}
