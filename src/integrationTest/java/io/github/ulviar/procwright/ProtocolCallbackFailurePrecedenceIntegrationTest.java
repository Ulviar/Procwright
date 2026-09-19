/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolCallbackFailurePrecedenceIntegrationTest {

    @Test
    void caughtWriterFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalStateException secondaryFailure = new IllegalStateException("secondary writer failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException ignored) {
                    throw secondaryFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("ignore-stdin", "--millis=5000").withMaxRequestBytes(1));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request("too-large"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
            assertExitFailedWith(session, failure);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtWriterFailurePrecedesSecondaryError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary writer error");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("ignore-stdin", "--millis=5000").withMaxRequestBytes(1));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request("too-large"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
            ProtocolSessionException requestLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, requestLimit.reason());
            assertSame(requestLimit, failure.getCause());
            assertExitFailedWith(session, failure);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, followUp.reason());
            assertSame(requestLimit, followUp.getCause());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolLineReaderFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalArgumentException secondaryFailure = new IllegalArgumentException("secondary line reader failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readLine(1);
                } catch (ProtocolSessionException ignored) {
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("partial", "--stdout=ab\n", "--stderr=", "--hold-millis=5000")
                        .withMaxResponseChars(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            assertExitFailedWith(session, failure);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtLineReaderFailurePrecedesSecondaryError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary line reader error");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readLine(1);
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("partial", "--stdout=ab\n", "--stderr=", "--hold-millis=5000")
                        .withMaxResponseChars(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            ProtocolSessionException responseLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseLimit.reason());
            assertSame(responseLimit, failure.getCause());
            assertExitFailedWith(session, failure);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
            assertSame(responseLimit, followUp.getCause());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolByteReaderFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalArgumentException secondaryFailure = new IllegalArgumentException("secondary byte reader failure");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readExactly(2);
                    return "unexpected";
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("partial", "--stdout=a", "--stderr=", "--hold-millis=5000")
                        .withMaxResponseBytes(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertNotNull(caughtFailure.get(), "the adapter must catch the reader budget failure");
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            assertExitFailedWith(session, failure);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtByteReaderFailurePrecedesSecondaryError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary byte reader error");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readExactly(2);
                    return "unexpected";
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("partial", "--stdout=a", "--stderr=", "--hold-millis=5000")
                        .withMaxResponseBytes(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            ProtocolSessionException responseLimit = caughtFailure.get();
            assertNotNull(responseLimit, "the adapter must catch the reader budget failure");
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseLimit.reason());
            assertSame(responseLimit, failure.getCause());
            assertExitFailedWith(session, failure);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
            assertSame(responseLimit, followUp.getCause());
        } finally {
            session.close();
        }
    }

    private static void assertExitFailedWith(
            ProtocolSession<String, String> session, ProtocolSessionException selectedFailure) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        assertSame(selectedFailure, observed.getCause());
    }
}
