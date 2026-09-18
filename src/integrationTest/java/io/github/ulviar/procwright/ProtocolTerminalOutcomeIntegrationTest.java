/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProtocolTerminalOutcomeIntegrationTest {

    @Test
    void adapterIllegalStateExceptionIsNotMisclassifiedAsClosed() throws Exception {
        IllegalStateException adapterFailure = new IllegalStateException("adapter writer failed");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                throw adapterFailure;
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000"));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertSame(adapterFailure, failure.getCause());
            assertExitFailedWith(session, failure);
        } finally {
            session.close();
        }
    }

    @Test
    void requestAgainstExitedProcessReportsProcessExited() throws Exception {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new TextLineAdapter(), call -> call.withArgs("exit", "--stdout=gone"))) {
            session.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);

            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, exception.reason());
        }
    }

    @Test
    void illegalArgumentExceptionFromProtocolAdapterRemainsProtocolDecoderFailure() throws Exception {
        IllegalArgumentException adapterFailure = new IllegalArgumentException("bad response");
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new FailingDecoderAdapter(adapterFailure),
                call -> call.withArgs("exit", "--stdout=ignored"))) {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertSame(adapterFailure, exception.getCause());
            assertExitFailedWith(session, exception);
        }
    }

    @Test
    void nullProtocolResponseIsATerminalDecoderFailure() throws Exception {
        ProtocolAdapter<String, String> adapter = new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                return null;
            }
        };

        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("length-line-frame").withTranscriptLimit(8))) {
            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class, () -> session.request("0123456789abcdef0123456789abcdef"));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertInstanceOf(NullPointerException.class, exception.getCause());
            assertEquals(
                    "Protocol response decoder returned null",
                    exception.getCause().getMessage());
            assertTrue(exception.transcript().truncated());
            assertTrue(exception.transcript().text().length() <= 8);
            assertExitFailedWith(session, exception);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("again"));
            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, followUp.reason());
        }
    }

    @Test
    void decoderErrorClosesSessionAfterConsumingResponse() throws Exception {
        AssertionError decoderError = new AssertionError("decoder failed");
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                throw decoderError;
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl"))) {
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertSame(decoderError, thrown);
            assertExitFailedWith(session, decoderError);
            AssertionError followUp =
                    assertThrows(AssertionError.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertSame(decoderError, followUp);
        }
    }

    @Test
    void writerErrorClosesSessionAndPreservesError() throws Exception {
        AssertionError writerError = new AssertionError("writer failed");
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                throw writerError;
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl"))) {
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertSame(writerError, thrown);
            assertExitFailedWith(session, writerError);
            AssertionError followUp =
                    assertThrows(AssertionError.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertSame(writerError, followUp);
        }
    }

    private static void assertExitFailedWith(ProtocolSession<String, String> session, Throwable selectedFailure) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        assertSame(selectedFailure, observed.getCause());
    }

    private static final class FailingDecoderAdapter implements ProtocolAdapter<String, String> {

        private final IllegalArgumentException failure;

        private FailingDecoderAdapter(IllegalArgumentException failure) {
            this.failure = failure;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            throw failure;
        }
    }
}
