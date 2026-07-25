/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class OutputPumpStartupTestSupport extends OutputPumpTestSupport {
    static void construct(HelperKind helper, DefaultSession session, PumpStarter starter) {
        ZeroReadBackoff backoff = ZeroReadBackoff.exponential();
        switch (helper) {
            case EXPECT -> new DefaultExpect(session, ExpectSettings.defaults(), backoff, starter);
            case LINE ->
                new DefaultLineSession(
                        session,
                        LineSessionSettings.defaults(),
                        LineSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter));
            case PROTOCOL ->
                new DefaultProtocolSession<>(
                        session,
                        noOpAdapter(),
                        ProtocolSessionSettings.defaults(),
                        ProtocolSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter));
            case STREAM ->
                new DefaultStreamSession(
                        session,
                        streamPlan(),
                        diagnostics(),
                        StreamSessionTestDependencies.withBackoffAndPumpStarter(backoff, starter));
        }
    }

    static ProtocolAdapter<String, String> noOpAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
    }

    static StreamExecutionPlan streamPlan() {
        LaunchPlan launchPlan = new LaunchPlan(
                List.of("stub"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                PtyProvider.unavailable(),
                TerminalSize.defaults());
        return new StreamExecutionPlan(sessionPlan, Duration.ZERO, 64, chunk -> {}, DiagnosticsSettings.disabled());
    }

    static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    static void closePumpStream(InputStream stream) {
        try {
            stream.close();
        } catch (IOException failure) {
            throw new IllegalStateException("test pump close failed", failure);
        }
    }

    enum HelperKind {
        EXPECT,
        LINE,
        PROTOCOL,
        STREAM
    }

    static final class FailingPumpStarter implements PumpStarter {

        final int failingOrdinal;
        final Throwable failure;
        final AtomicInteger starts = new AtomicInteger();
        final List<Thread> startedThreads = new ArrayList<>();

        FailingPumpStarter(int failingOrdinal, Throwable failure) {
            this.failingOrdinal = failingOrdinal;
            this.failure = failure;
        }

        @Override
        public Thread start(String namePrefix, Runnable task) {
            if (starts.incrementAndGet() == failingOrdinal) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            Thread thread = Threading.start(namePrefix, task);
            startedThreads.add(thread);
            return thread;
        }

        boolean awaitStartedThreadsStopped() throws InterruptedException {
            for (Thread thread : startedThreads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }
    }

    static final class StartThenThrowPumpStarter implements PumpStarter {

        final int failingOrdinal;
        final Throwable failure;
        final AtomicInteger starts = new AtomicInteger();
        final List<Thread> startedThreads = new ArrayList<>();
        final List<Throwable> uncaughtFailures = new CopyOnWriteArrayList<>();

        StartThenThrowPumpStarter(int failingOrdinal, Throwable failure) {
            this.failingOrdinal = failingOrdinal;
            this.failure = failure;
        }

        @Override
        public Thread start(String namePrefix, Runnable task) {
            int ordinal = starts.incrementAndGet();
            Thread thread = new Thread(task, namePrefix + ordinal);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, uncaught) -> uncaughtFailures.add(uncaught));
            thread.start();
            startedThreads.add(thread);
            if (ordinal == failingOrdinal) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            return thread;
        }

        boolean awaitStartedThreadsStopped() throws InterruptedException {
            for (Thread thread : startedThreads) {
                thread.join(TimeUnit.SECONDS.toMillis(1));
                if (thread.isAlive()) {
                    return false;
                }
            }
            return true;
        }

        List<Throwable> uncaughtFailures() {
            return List.copyOf(uncaughtFailures);
        }
    }

    static final class BlockingPublicReadInputStream extends InputStream {

        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for test input", exception);
            }
            if (closed.get()) {
                throw new IOException("Stream closed");
            }
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            return read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.set(true);
            release.countDown();
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseEof() {
            release.countDown();
        }

        int closeCalls() {
            return closes.get();
        }
    }
}
