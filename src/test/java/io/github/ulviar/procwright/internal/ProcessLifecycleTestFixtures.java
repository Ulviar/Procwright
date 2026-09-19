/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class ProcessLifecycleTestFixtures {

    private ProcessLifecycleTestFixtures() {}

    static List<Throwable> failureSources(Throwable failure) {
        List<Throwable> aggregateSources = FailureAggregation.sources(failure);
        if (aggregateSources.size() != 1 || aggregateSources.get(0) != failure) {
            return aggregateSources;
        }
        if (failure instanceof io.github.ulviar.procwright.command.CommandExecutionException
                && failure.getCause() != null) {
            java.util.ArrayList<Throwable> sources = new java.util.ArrayList<>();
            sources.add(failure.getCause());
            sources.addAll(List.of(failure.getSuppressed()));
            return List.copyOf(sources);
        }
        return aggregateSources;
    }

    static Throwable failureSourceContaining(Throwable failure, String text) {
        return failureSources(failure).stream()
                .filter(source ->
                        source.getMessage() != null && source.getMessage().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No failure source contains: " + text, failure));
    }

    static KnownDescendants knownDescendants(ProcessHandle... handles) {
        return knownDescendants(List.of(handles));
    }

    static KnownDescendants knownDescendants(Iterable<? extends ProcessHandle> handles) {
        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> indexed = new LinkedHashMap<>();
        boolean truncated = false;
        for (ProcessHandle handle : handles) {
            ProcessTreeScanner.HandleIdentity identity = ProcessTreeScanner.identity(handle);
            if (indexed.containsKey(identity)) {
                continue;
            }
            if (indexed.size() == ProcessTreeScanner.shared().descendantLimit()) {
                truncated = true;
                break;
            }
            indexed.put(identity, handle);
        }
        return KnownDescendants.copyOf(indexed, truncated, false);
    }

    static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    static final class CompletedProcess extends Process {

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
            return Stream.empty();
        }
    }

    static class MutableProcessHandle implements ProcessHandle {

        private final long pid;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger gracefulDestroyCalls = new AtomicInteger();
        private final AtomicInteger forceDestroyCalls = new AtomicInteger();

        MutableProcessHandle(long pid) {
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
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            recordGracefulDestroyAttempt();
            markExited();
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            recordForcefulDestroyAttempt();
            markExited();
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

        int gracefulDestroyCalls() {
            return gracefulDestroyCalls.get();
        }

        int forceDestroyCalls() {
            return forceDestroyCalls.get();
        }

        final void recordGracefulDestroyAttempt() {
            gracefulDestroyCalls.incrementAndGet();
        }

        final void recordForcefulDestroyAttempt() {
            forceDestroyCalls.incrementAndGet();
        }

        final void markExited() {
            alive.set(false);
        }
    }

    abstract static class WaiterProcess extends Process {

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

    static final class AdvancingWaitClock {

        private volatile long nanos;

        public long nanoTime() {
            return nanos;
        }

        void advanceTo(Duration elapsed) {
            nanos = elapsed.toNanos();
        }
    }

    static MutableProcessHandle failsOnGracefulDestroy(long pid, Throwable gracefulFailure) {
        return new MutableProcessHandle(pid) {
            @Override
            public boolean destroy() {
                recordGracefulDestroyAttempt();
                throwUnchecked(gracefulFailure);
                return true;
            }
        };
    }

    static MutableProcessHandle failsOnGracefulAndForcefulDestroy(
            long pid, Throwable gracefulFailure, Throwable forcefulFailure) {
        return new MutableProcessHandle(pid) {
            @Override
            public boolean destroy() {
                recordGracefulDestroyAttempt();
                throwUnchecked(gracefulFailure);
                return true;
            }

            @Override
            public boolean destroyForcibly() {
                recordForcefulDestroyAttempt();
                markExited();
                throwUnchecked(forcefulFailure);
                return true;
            }
        };
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("test failure must be unchecked", failure);
    }
}
