/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CallbackClosedFailureContractTest {

    @Test
    void decoderThrownClosedFailureStillTerminatesTheLineSession() throws Exception {
        LineSessionException callbackFailure = new LineSessionException(
                LineSessionException.Reason.CLOSED, new LineTranscript("", false, false), "decoder closed");
        AtomicInteger calls = new AtomicInteger();
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        LineSessionTestFixtures.ControllableProcess process = new LineSessionTestFixtures.ControllableProcess(
                stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            calls.incrementAndGet();
            throw callbackFailure;
        });
        try (DefaultLineSession session = LineSessionTestFixtures.openLineSession(process, settings)) {
            assertSame(callbackFailure, assertThrows(LineSessionException.class, () -> session.request("first")));
            assertFalse(process.isAlive(), "a callback failure after writing must terminate the process");
            ExecutionException exit = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
            assertSame(callbackFailure, exit.getCause());
            assertEquals(
                    LineSessionException.Reason.CLOSED,
                    assertThrows(LineSessionException.class, () -> session.request("retry"))
                            .reason());
            assertEquals(1, calls.get());
            assertEquals("first\n", stdin.toString(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adapterThrownClosedFailureStillTerminatesTheProtocolSession(boolean failWriting) throws Exception {
        ProtocolSessionException callbackFailure = new ProtocolSessionException(
                ProtocolSessionException.Reason.CLOSED, new ProtocolTranscript("", false, false), "adapter closed");
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        ProtocolSessionContractSupport.ControllableProcess process =
                new ProtocolSessionContractSupport.ControllableProcess();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writes.incrementAndGet();
                writer.writeLine(request);
                writer.flush();
                if (failWriting) {
                    throw callbackFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                reads.incrementAndGet();
                throw callbackFailure;
            }
        };
        try (DefaultProtocolSession<String, String> session =
                ProtocolSessionContractSupport.protocolSession(process, adapter, ProtocolSessionSettings.defaults())) {
            assertSame(callbackFailure, assertThrows(ProtocolSessionException.class, () -> session.request("first")));
            assertFalse(process.isAlive(), "an admitted adapter failure must terminate the process");
            ExecutionException exit = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(1, TimeUnit.SECONDS));
            assertSame(callbackFailure, exit.getCause());
            assertEquals(
                    ProtocolSessionException.Reason.CLOSED,
                    assertThrows(ProtocolSessionException.class, () -> session.request("retry"))
                            .reason());
            assertEquals(1, writes.get());
            assertEquals(failWriting ? 0 : 1, reads.get());
        }
    }
}
