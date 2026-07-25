/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.StreamExit;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SessionOutputOwnerLifecycleTest extends SessionOutputOwnershipTestSupport {

    @Test
    void streamOwnerDrainsAndClosesOutputAfterNaturalProcessExit() throws Exception {
        BlockingDrainInputStream stdout = new BlockingDrainInputStream();
        BlockingDrainInputStream stderr = new BlockingDrainInputStream();
        StubProcess process = new StubProcess(stdout, stderr);
        DefaultSession rawSession = defaultSessionWith(process);
        DefaultStreamSession session = new DefaultStreamSession(rawSession, streamPlan(Duration.ZERO), diagnostics());

        try {
            stdout.awaitReadStarted();
            stderr.awaitReadStarted();
            process.completeExit(0);

            awaitProcessTerminal(rawSession);
            assertFalse(rawSession.onExit().isDone());
            assertFalse(stdout.isClosed(), "process lifecycle must not close output owned by a draining helper");
            assertFalse(stderr.isClosed(), "process lifecycle must not close output owned by a draining helper");
            assertFalse(session.onExit().isDone(), "stream exit must wait for its output pumps");

            stdout.releaseEof();
            stderr.releaseEof();
            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            assertTrue(stdout.awaitClosed(), "the output owner must close its stream after EOF");
            assertTrue(stderr.awaitClosed(), "the output owner must close its stream after EOF");
            assertEquals(
                    0, rawSession.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
            session.close();
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseEof();
            stderr.releaseEof();
            session.close();
        }
    }

    @Test
    void lineOwnerDrainsAndClosesOutputAfterNaturalProcessExit() throws Exception {
        assertHelperDrainsAndClosesOutput(
                rawSession -> new DefaultLineSession(rawSession, LineSessionSettings.defaults()));
    }

    @Test
    void protocolOwnerDrainsAndClosesOutputAfterNaturalProcessExit() throws Exception {
        assertHelperDrainsAndClosesOutput(rawSession ->
                new DefaultProtocolSession<>(rawSession, noOpAdapter(), ProtocolSessionSettings.defaults()));
    }

    @Test
    void expectOwnerDrainsAndClosesOutputAfterNaturalProcessExit() throws Exception {
        assertHelperDrainsAndClosesOutput(rawSession -> new DefaultExpect(rawSession, ExpectSettings.defaults()));
    }

    @Test
    void streamOwnerClosesOutputOnceAfterForcedProcessExit() throws Exception {
        assertHelperClosesOutputAfterForcedExit(
                rawSession -> new DefaultStreamSession(rawSession, streamPlan(Duration.ZERO), diagnostics()));
    }

    @Test
    void lineOwnerClosesOutputOnceAfterForcedProcessExit() throws Exception {
        assertHelperClosesOutputAfterForcedExit(
                rawSession -> new DefaultLineSession(rawSession, LineSessionSettings.defaults()));
    }

    @Test
    void protocolOwnerClosesOutputOnceAfterForcedProcessExit() throws Exception {
        assertHelperClosesOutputAfterForcedExit(rawSession ->
                new DefaultProtocolSession<>(rawSession, noOpAdapter(), ProtocolSessionSettings.defaults()));
    }

    @Test
    void expectOwnerClosesOutputOnceAfterForcedProcessExit() throws Exception {
        assertHelperClosesOutputAfterForcedExit(rawSession -> new DefaultExpect(rawSession, ExpectSettings.defaults()));
    }

    private static void assertHelperDrainsAndClosesOutput(OutputHelperFactory helperFactory) throws Exception {
        BlockingDrainInputStream stdout = new BlockingDrainInputStream();
        BlockingDrainInputStream stderr = new BlockingDrainInputStream();
        StubProcess process = new StubProcess(stdout, stderr);
        DefaultSession rawSession = defaultSessionWith(process);
        AutoCloseable helper = helperFactory.open(rawSession);

        try {
            stdout.awaitReadStarted();
            stderr.awaitReadStarted();
            process.completeExit(0);

            awaitProcessTerminal(rawSession);
            assertFalse(rawSession.onExit().isDone());
            assertFalse(stdout.isClosed(), "process lifecycle must not close output owned by a draining helper");
            assertFalse(stderr.isClosed(), "process lifecycle must not close output owned by a draining helper");

            stdout.releaseEof();
            stderr.releaseEof();

            assertTrue(stdout.awaitClosed(), "the output owner must close stdout after EOF");
            assertTrue(stderr.awaitClosed(), "the output owner must close stderr after EOF");
            assertEquals(
                    0, rawSession.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
            helper.close();
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseEof();
            stderr.releaseEof();
            helper.close();
        }
    }

    private static void assertHelperClosesOutputAfterForcedExit(OutputHelperFactory helperFactory) throws Exception {
        BlockingDrainInputStream stdout = new BlockingDrainInputStream();
        BlockingDrainInputStream stderr = new BlockingDrainInputStream();
        StubProcess process = new StubProcess(stdout, stderr, () -> {
            stdout.releaseEof();
            stderr.releaseEof();
        });
        DefaultSession rawSession = defaultSessionWith(process);
        AutoCloseable helper = helperFactory.open(rawSession);

        try {
            stdout.awaitReadStarted();
            stderr.awaitReadStarted();

            helper.close();

            awaitProcessTerminal(rawSession);
            assertTrue(stdout.awaitClosed(), "the output owner must close stdout after the process publishes EOF");
            assertTrue(stderr.awaitClosed(), "the output owner must close stderr after the process publishes EOF");
            rawSession.onExit().get(2, TimeUnit.SECONDS);
            helper.close();
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseEof();
            stderr.releaseEof();
            helper.close();
        }
    }

    private static ProtocolAdapter<String, String> noOpAdapter() {
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

    private static final class BlockingDrainInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEof = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean isClosed = new AtomicBoolean();
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                releaseEof.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while awaiting test EOF", exception);
            }
            if (isClosed.get()) {
                throw new IOException("Stream closed before EOF");
            }
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            isClosed.set(true);
            releaseEof.countDown();
            closed.countDown();
        }

        private void awaitReadStarted() throws InterruptedException {
            if (!readStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Stream output pump did not start");
            }
        }

        private void releaseEof() {
            releaseEof.countDown();
        }

        private boolean isClosed() {
            return isClosed.get();
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(2, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    @FunctionalInterface
    private interface OutputHelperFactory {

        AutoCloseable open(DefaultSession session);
    }
}
