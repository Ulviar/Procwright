/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.internal.LaunchMode;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class SessionOutputOwnershipTest {

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("compositeGuardedInputStreamOperations")
    void compositeGuardedInputStreamOperationAcquiresOwnershipExactlyOnce(
            String operationName, GuardedInputStreamOperation operation) throws Exception {
        AtomicInteger admissions = new AtomicInteger();
        SessionOutputOwnership ownership = new SessionOutputOwnership(admissions::incrementAndGet);
        InputStream rawOutput = ownership.publicStream(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));

        operation.invoke(rawOutput);

        assertEquals(1, admissions.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("compositeGuardedInputStreamOperations")
    void lifecycleCloseAfterCompositeAdmissionDoesNotCauseNestedReadmission(
            String operationName, GuardedInputStreamOperation operation) throws Exception {
        AtomicReference<SessionOutputOwnership> ownershipReference = new AtomicReference<>();
        AtomicBoolean closeSelected = new AtomicBoolean();
        AtomicInteger admissions = new AtomicInteger();
        SessionOutputOwnership ownership = new SessionOutputOwnership(() -> {
            admissions.incrementAndGet();
            if (closeSelected.compareAndSet(false, true)) {
                assertTrue(Objects.requireNonNull(ownershipReference.get(), "ownership")
                        .claimLifecycleClose());
            }
        });
        ownershipReference.set(ownership);
        InputStream rawOutput = ownership.publicStream(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));

        assertDoesNotThrow(() -> operation.invoke(rawOutput));

        assertTrue(closeSelected.get());
        assertEquals(1, admissions.get());
    }

    private static Stream<Arguments> compositeGuardedInputStreamOperations() {
        return Stream.of(
                Arguments.of("read(byte[])", (GuardedInputStreamOperation) input -> input.read(new byte[2])),
                Arguments.of("readAllBytes()", (GuardedInputStreamOperation) InputStream::readAllBytes),
                Arguments.of("readNBytes(int)", (GuardedInputStreamOperation) input -> input.readNBytes(2)),
                Arguments.of("readNBytes(byte[], int, int)", (GuardedInputStreamOperation)
                        input -> input.readNBytes(new byte[4], 1, 2)),
                Arguments.of("skipNBytes(long)", (GuardedInputStreamOperation) input -> input.skipNBytes(2)),
                Arguments.of("transferTo(OutputStream)", (GuardedInputStreamOperation)
                        input -> input.transferTo(OutputStream.nullOutputStream())));
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
    void lifecycleCloseClaimPreventsAHelperFromTakingOutputResponsibility() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();

        assertTrue(ownership.claimLifecycleClose());

        assertThrows(IllegalStateException.class, () -> ownership.claim("late helper"));
        assertFalse(ownership.claimLifecycleClose(), "lifecycle close responsibility must be claimed once");
    }

    @Test
    void helperClaimPreventsLifecycleFromClosingItsOutput() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();

        ownership.claim("helper");

        assertFalse(ownership.claimLifecycleClose());
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

    @Test
    void streamTimeoutWatcherStopsAfterEarlyProcessExit() throws Exception {
        StubProcess process = new StubProcess(new ByteArrayInputStream("done\n".getBytes(StandardCharsets.UTF_8)));
        DefaultSession rawSession = defaultSessionWith(process);

        try (DefaultStreamSession session =
                new DefaultStreamSession(rawSession, streamPlan(Duration.ofSeconds(Long.MAX_VALUE)), diagnostics())) {
            process.completeExit(0);

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
            session.timeoutWatcherStopped().get(2, TimeUnit.SECONDS);
        }
    }

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

    private static void awaitProcessTerminal(DefaultSession session) throws InterruptedException {
        CountDownLatch terminal = new CountDownLatch(1);
        session.observeExit((result, failure) -> terminal.countDown());
        assertTrue(terminal.await(2, TimeUnit.SECONDS), "process terminal signal did not complete");
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

    private static Session sessionWith(InputStream stdout) {
        return defaultSessionWith(stdout);
    }

    private static DefaultSession defaultSessionWith(InputStream stdout) {
        return defaultSessionWith(new StubProcess(stdout));
    }

    private static DefaultSession defaultSessionWith(StubProcess process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    private static StreamExecutionPlan streamPlan(Duration timeout) {
        LaunchPlan launchPlan = new LaunchPlan(
                LaunchMode.DIRECT,
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
        return new StreamExecutionPlan(sessionPlan, timeout, 1024, chunk -> {}, DiagnosticsSettings.disabled());
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "session-test", CommandEcho.empty());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private static final class StubProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final OutputStream stdin = OutputStream.nullOutputStream();
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final Runnable onDestroy;

        private StubProcess(InputStream stdout) {
            this(stdout, new ByteArrayInputStream(new byte[0]));
        }

        private StubProcess(InputStream stdout, InputStream stderr) {
            this(stdout, stderr, () -> {});
        }

        private StubProcess(InputStream stdout, InputStream stderr, Runnable onDestroy) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.onDestroy = onDestroy;
        }

        private void completeExit(int exitCode) {
            exit.complete(exitCode);
            alive.set(false);
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
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Stub process failed", exception);
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Stub process failed", exception);
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("Process is still alive");
            }
            return value;
        }

        @Override
        public void destroy() {
            onDestroy.run();
            exit.complete(143);
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public long pid() {
            return 1L;
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

    @FunctionalInterface
    private interface OutputHelperFactory {

        AutoCloseable open(DefaultSession session);
    }
}
