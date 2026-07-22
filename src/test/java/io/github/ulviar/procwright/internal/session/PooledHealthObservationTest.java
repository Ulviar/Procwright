/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class PooledHealthObservationTest {

    private static final int REQUEST_COUNT = 512;

    @Test
    void linePoolHealthChecksDoNotRetainExitFutureDependentsAcrossRequests() throws Exception {
        LoopbackProcess process = new LoopbackProcess("ok\n");
        DefaultSession session = session(process);
        DefaultLineSession worker = new DefaultLineSession(session, LineSessionSettings.defaults());
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> worker,
                LineSessionSettings.defaults(),
                WorkerPoolSettings.<LineSession>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1));
        try {
            int baseline = session.exitDependentCount();

            for (int index = 0; index < REQUEST_COUNT; index++) {
                assertEquals("ok", pool.request("request-" + index).text());
            }

            assertEquals(baseline, session.exitDependentCount());
        } finally {
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void protocolPoolHealthChecksDoNotRetainExitFutureDependentsAcrossRequests() throws Exception {
        LoopbackProcess process = new LoopbackProcess("ok\n");
        DefaultSession session = session(process);
        DefaultProtocolSession<String, String> worker =
                new DefaultProtocolSession<>(session, lineAdapter(), ProtocolSessionSettings.defaults());
        DefaultPooledProtocolSession<String, String> pool = new DefaultPooledProtocolSession<>(
                () -> worker,
                WorkerPoolSettings.<ProtocolSession<String, String>>defaults(ignored -> {}, ignored -> true)
                        .withWarmupSize(1));
        try {
            int baseline = session.exitDependentCount();

            for (int index = 0; index < REQUEST_COUNT; index++) {
                assertEquals("ok", pool.request("request-" + index));
            }

            assertEquals(baseline, session.exitDependentCount());
        } finally {
            pool.closeAsync().get(1, TimeUnit.SECONDS);
        }
    }

    private static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8);
    }

    private static ProtocolAdapter<String, String> lineAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return readers.stdout().readLine(64);
            }
        };
    }

    private static final class LoopbackProcess extends Process {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final PipedInputStream stdout = new PipedInputStream(8 * 1024);
        private final PipedOutputStream childStdout;
        private final LinkedBlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
        private final Thread responder;
        private final OutputStream stdin;

        private LoopbackProcess(String reply) throws IOException {
            childStdout = new PipedOutputStream(stdout);
            byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
            stdin = new OutputStream() {
                @Override
                public synchronized void write(int value) throws IOException {
                    if (!alive.get()) {
                        throw new IOException("process is closed");
                    }
                    if (value == '\n') {
                        replies.add(replyBytes);
                    }
                }
            };
            responder = new Thread(null, this::respond, "pooled-health-loopback-responder", 0, false);
            responder.setDaemon(true);
            responder.start();
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            complete();
        }

        @Override
        public Process destroyForcibly() {
            complete();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private void complete() {
            if (alive.compareAndSet(true, false)) {
                responder.interrupt();
                try {
                    childStdout.close();
                } catch (IOException ignored) {
                    // The session may have already closed the connected read end.
                }
                exited.countDown();
            }
        }

        private void respond() {
            try {
                while (alive.get()) {
                    byte[] reply = replies.take();
                    childStdout.write(reply);
                    childStdout.flush();
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Process/session shutdown closes either end of the test pipe.
            }
        }
    }
}
