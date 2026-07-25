/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.Session;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class SessionRawOutputOwnershipContractTest extends SessionOutputOwnershipTestSupport {

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardedInputStreamOperations")
    void everyGuardedInputStreamObservationSelectsRawOwnership(
            String operationName, GuardedInputStreamOperation operation) throws Exception {
        SessionOutputOwnership ownership = new SessionOutputOwnership();
        InputStream rawOutput = ownership.publicStream(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));

        operation.invoke(rawOutput);

        assertThrows(IllegalStateException.class, () -> ownership.claim("helper"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("guardedInputStreamOperations")
    void everyGuardedInputStreamObservationRejectsHelperOwnedOutputBeforeDelegateMutation(
            String operationName, GuardedInputStreamOperation operation) {
        SessionOutputOwnership ownership = new SessionOutputOwnership();
        TrackingInputStream delegate = new TrackingInputStream(new byte[] {1, 2, 3, 4});
        InputStream rawOutput = ownership.publicStream(delegate);
        ownership.claim("helper");

        assertThrows(IllegalStateException.class, () -> operation.invoke(rawOutput));

        assertEquals(0, delegate.observations());
    }

    private static Stream<Arguments> guardedInputStreamOperations() {
        return Stream.of(
                Arguments.of("read()", (GuardedInputStreamOperation) InputStream::read),
                Arguments.of("read(byte[])", (GuardedInputStreamOperation) input -> input.read(new byte[2])),
                Arguments.of(
                        "read(byte[], int, int)", (GuardedInputStreamOperation) input -> input.read(new byte[4], 1, 2)),
                Arguments.of("readAllBytes()", (GuardedInputStreamOperation) InputStream::readAllBytes),
                Arguments.of("readNBytes(int)", (GuardedInputStreamOperation) input -> input.readNBytes(2)),
                Arguments.of("readNBytes(byte[], int, int)", (GuardedInputStreamOperation)
                        input -> input.readNBytes(new byte[4], 1, 2)),
                Arguments.of("skip(long)", (GuardedInputStreamOperation) input -> input.skip(2)),
                Arguments.of("skipNBytes(long)", (GuardedInputStreamOperation) input -> input.skipNBytes(2)),
                Arguments.of("available()", (GuardedInputStreamOperation) InputStream::available),
                Arguments.of("transferTo(OutputStream)", (GuardedInputStreamOperation)
                        input -> input.transferTo(OutputStream.nullOutputStream())),
                Arguments.of("mark(int)", (GuardedInputStreamOperation) input -> input.mark(2)),
                Arguments.of("reset()", (GuardedInputStreamOperation) InputStream::reset),
                Arguments.of("markSupported()", (GuardedInputStreamOperation) InputStream::markSupported),
                Arguments.of("close()", (GuardedInputStreamOperation) InputStream::close));
    }

    @Test
    void rawPublicCloseAfterLifecycleCloseIsHarmless() throws Exception {
        CloseCountingInputStream stdout = new CloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);
        InputStream rawStdout = session.stdout();

        process.completeExit(0);
        session.onExit().get(2, TimeUnit.SECONDS);

        assertTrue(stdout.awaitClose());
        assertEquals(1, stdout.closeCalls());
        assertDoesNotThrow(rawStdout::close);
        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void staleRawPublicCloseCannotCloseHelperOwnedOutput() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();
        CloseCountingInputStream delegate = new CloseCountingInputStream();
        InputStream rawOutput = ownership.publicStream(delegate);

        ownership.claim("helper");

        assertThrows(IllegalStateException.class, rawOutput::close);
        assertEquals(0, delegate.closeCalls());
    }

    @Test
    void rawAndLifecycleClosePhysicalOutputDelegateExactlyOnce() throws Exception {
        CloseCountingInputStream stdout = new CloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);
        InputStream firstRawStdout = session.stdout();
        InputStream secondRawStdout = session.stdout();

        firstRawStdout.close();
        secondRawStdout.close();
        process.completeExit(0);
        session.onExit().get(2, TimeUnit.SECONDS);

        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void concurrentRawAndLifecycleClosePhysicalOutputDelegateExactlyOnce() throws Exception {
        BlockingCloseCountingInputStream stdout = new BlockingCloseCountingInputStream();
        StubProcess process = new StubProcess(stdout);
        DefaultSession session = defaultSessionWith(process);
        InputStream rawStdout = session.stdout();
        CompletableFuture<Throwable> rawCloseOutcome = new CompletableFuture<>();
        Thread rawCloser = new Thread(() -> {
            try {
                rawStdout.close();
                rawCloseOutcome.complete(null);
            } catch (Throwable failure) {
                rawCloseOutcome.complete(failure);
            }
        });
        rawCloser.setDaemon(true);
        rawCloser.start();
        try {
            assertTrue(stdout.awaitCloseStarted());

            process.completeExit(0);

            awaitProcessTerminal(session);
            assertFalse(session.onExit().isDone());
            assertEquals(1, stdout.closeCalls());
        } finally {
            stdout.releaseClose();
            process.completeExit(0);
            rawCloser.join(TimeUnit.SECONDS.toMillis(2));
            session.close();
        }

        assertFalse(rawCloser.isAlive());
        assertNull(rawCloseOutcome.get(2, TimeUnit.SECONDS));
        assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
        assertEquals(1, stdout.closeCalls());
    }

    @Test
    void helperClaimFailsAfterPublicOutputReadSelectsRawMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[] {'x'}))) {
            assertEquals('x', session.stdout().read());

            assertThrows(IllegalStateException.class, () -> session.expect().open());
        }
    }

    @Test
    void helperClaimFailsAfterPublicOutputCloseSelectsRawMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[0]))) {
            session.stdout().close();

            assertThrows(IllegalStateException.class, () -> session.expect().open());
        }
    }

    @Test
    void helperClaimFailsWhilePublicOutputReadIsInFlight() throws Exception {
        BlockingInputStream stdout = new BlockingInputStream();
        try (Session session = sessionWith(stdout)) {
            CompletableFuture<Integer> read = new CompletableFuture<>();
            Thread thread = new Thread(
                    () -> {
                        try {
                            read.complete(session.stdout().read());
                        } catch (Throwable throwable) {
                            read.completeExceptionally(throwable);
                        }
                    },
                    "procwright-test-blocking-public-output-read");
            thread.setDaemon(true);
            thread.start();
            stdout.awaitReadStarted();

            assertThrows(IllegalStateException.class, () -> session.expect().open());

            stdout.release();
            assertEquals(-1, read.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void rawOutputStreamsCannotBeReadAfterLineSessionClaimsOutputOwnership() throws Exception {
        DefaultSession rawSession = defaultSessionWith(new ByteArrayInputStream(new byte[0]));

        DefaultLineSession session = new DefaultLineSession(rawSession, LineSessionSettings.defaults());
        try {
            assertThrows(IllegalStateException.class, rawSession.stdout()::read);
            assertThrows(IllegalStateException.class, rawSession.stderr()::read);
        } finally {
            session.close();
        }
    }

    @Test
    void rawOutputMarkSelectsPublicMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[] {'x'}))) {
            session.stdout().mark(1);

            assertThrows(IllegalStateException.class, () -> session.expect().open());
        }
    }

    @Test
    void rawOutputMarkSupportedSelectsPublicMode() throws Exception {
        try (Session session = sessionWith(new ByteArrayInputStream(new byte[] {'x'}))) {
            assertTrue(session.stdout().markSupported());

            assertThrows(IllegalStateException.class, () -> session.expect().open());
        }
    }

    @Test
    void rawOutputMarkAndResetFailAfterHelperClaimsOwnership() throws Exception {
        DefaultSession rawSession = defaultSessionWith(new ByteArrayInputStream(new byte[] {'x'}));
        InputStream rawStdout = rawSession.stdout();

        DefaultLineSession session = new DefaultLineSession(rawSession, LineSessionSettings.defaults());
        try {
            assertThrows(IllegalStateException.class, () -> rawStdout.mark(1));
            assertThrows(IllegalStateException.class, rawStdout::reset);
        } finally {
            session.close();
        }
    }

    @Test
    void rawOutputMarkSupportedFailsAfterHelperClaimsOwnership() throws Exception {
        DefaultSession rawSession = defaultSessionWith(new ByteArrayInputStream(new byte[] {'x'}));
        InputStream rawStdout = rawSession.stdout();

        DefaultLineSession session = new DefaultLineSession(rawSession, LineSessionSettings.defaults());
        try {
            assertThrows(IllegalStateException.class, rawStdout::markSupported);
        } finally {
            session.close();
        }
    }

    @Test
    void rawOutputStreamsCannotBeReadAfterStreamSessionClaimsOutputOwnership() throws Exception {
        DefaultSession rawSession = defaultSessionWith(new ByteArrayInputStream(new byte[0]));

        DefaultStreamSession session = new DefaultStreamSession(rawSession, streamPlan(Duration.ZERO), diagnostics());
        try {
            assertThrows(IllegalStateException.class, rawSession.stdout()::read);
            assertThrows(IllegalStateException.class, rawSession.stderr()::read);
        } finally {
            session.close();
        }
    }

    private static Session sessionWith(InputStream stdout) {
        return defaultSessionWith(stdout);
    }

    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while blocking test input stream", exception);
            }
            if (closed.get()) {
                throw new IOException("Stream closed");
            }
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
            release.countDown();
        }

        private void awaitReadStarted() throws InterruptedException {
            if (!readStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Public output read did not start");
            }
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class CloseCountingInputStream extends ByteArrayInputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private CloseCountingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            try {
                super.close();
            } finally {
                closed.countDown();
            }
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(2, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class BlockingCloseCountingInputStream extends ByteArrayInputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private BlockingCloseCountingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseClose.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            super.close();
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            releaseClose.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private final AtomicInteger observations = new AtomicInteger();

        private TrackingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            observations.incrementAndGet();
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            observations.incrementAndGet();
            return delegate.read(bytes);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            observations.incrementAndGet();
            return delegate.read(bytes, offset, length);
        }

        @Override
        public byte[] readAllBytes() {
            observations.incrementAndGet();
            return delegate.readAllBytes();
        }

        @Override
        public byte[] readNBytes(int length) throws IOException {
            observations.incrementAndGet();
            return delegate.readNBytes(length);
        }

        @Override
        public int readNBytes(byte[] bytes, int offset, int length) {
            observations.incrementAndGet();
            return delegate.readNBytes(bytes, offset, length);
        }

        @Override
        public long skip(long count) {
            observations.incrementAndGet();
            return delegate.skip(count);
        }

        @Override
        public void skipNBytes(long count) throws IOException {
            observations.incrementAndGet();
            delegate.skipNBytes(count);
        }

        @Override
        public int available() {
            observations.incrementAndGet();
            return delegate.available();
        }

        @Override
        public long transferTo(OutputStream output) throws IOException {
            observations.incrementAndGet();
            return delegate.transferTo(output);
        }

        @Override
        public synchronized void mark(int readLimit) {
            observations.incrementAndGet();
            delegate.mark(readLimit);
        }

        @Override
        public synchronized void reset() {
            observations.incrementAndGet();
            delegate.reset();
        }

        @Override
        public boolean markSupported() {
            observations.incrementAndGet();
            return delegate.markSupported();
        }

        @Override
        public void close() throws IOException {
            observations.incrementAndGet();
            delegate.close();
        }

        private int observations() {
            return observations.get();
        }
    }

    @FunctionalInterface
    private interface GuardedInputStreamOperation {

        void invoke(InputStream input) throws IOException;
    }
}
