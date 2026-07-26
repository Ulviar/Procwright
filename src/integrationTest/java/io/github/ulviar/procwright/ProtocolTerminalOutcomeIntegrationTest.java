/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.openProtocolSession;
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
            session.onExit().get(2, TimeUnit.SECONDS);
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
    void illegalArgumentExceptionFromProtocolAdapterRemainsProtocolDecoderFailure() {
        IllegalArgumentException adapterFailure = new IllegalArgumentException("bad response");
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(),
                        new FailingDecoderAdapter(adapterFailure),
                        call -> call.withArgs("exit", "--stdout=ignored"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
        assertSame(adapterFailure, exception.getCause());
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

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("length-line-frame")
                        .withTranscriptLimit(8))) {
            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class, () -> session.request("0123456789abcdef0123456789abcdef"));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertInstanceOf(NullPointerException.class, exception.getCause());
            assertEquals(
                    "Protocol response decoder returned null",
                    exception.getCause().getMessage());
            assertTrue(exception.transcript().truncated());
            assertTrue(exception.transcript().text().length() <= 8);
            session.onExit().get(2, TimeUnit.SECONDS);
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
            session.onExit().get(2, TimeUnit.SECONDS);
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
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp =
                    assertThrows(AssertionError.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertSame(writerError, followUp);
        }
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
