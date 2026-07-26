/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
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
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000")
                        .withMaxRequestBytes(1));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request("too-large"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void writerErrorSupersedesTheTypedFailureHandledByTheAdapter() throws Exception {
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
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000")
                        .withMaxRequestBytes(1));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> session.request("too-large"));

            assertSame(secondaryFailure, thrown);
            ProtocolSessionException requestLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, requestLimit.reason());
            assertIndependentFailures(secondaryFailure, requestLimit);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp = assertThrows(AssertionError.class, () -> session.request("x"));
            assertSame(secondaryFailure, followUp);
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
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab\n", "--stderr=", "--hold-millis=5000")
                .withMaxResponseChars(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void lineReaderErrorSupersedesTheTypedFailureHandledByTheAdapter() throws Exception {
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
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab\n", "--stderr=", "--hold-millis=5000")
                .withMaxResponseChars(1));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> session.request(""));

            assertSame(secondaryFailure, thrown);
            ProtocolSessionException responseLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseLimit.reason());
            assertIndependentFailures(secondaryFailure, responseLimit);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp = assertThrows(AssertionError.class, () -> session.request(""));
            assertSame(secondaryFailure, followUp);
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolByteReaderFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalArgumentException secondaryFailure = new IllegalArgumentException("secondary byte reader failure");
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
                } catch (ProtocolSessionException ignored) {
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab", "--stderr=", "--hold-millis=5000")
                .withMaxResponseBytes(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void byteReaderErrorSupersedesTheTypedFailureHandledByTheAdapter() throws Exception {
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
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab", "--stderr=", "--hold-millis=5000")
                .withMaxResponseBytes(1));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> session.request(""));

            assertSame(secondaryFailure, thrown);
            ProtocolSessionException responseLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseLimit.reason());
            assertIndependentFailures(secondaryFailure, responseLimit);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp = assertThrows(AssertionError.class, () -> session.request(""));
            assertSame(secondaryFailure, followUp);
        } finally {
            session.close();
        }
    }

    private static boolean causeChainContains(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void assertIndependentFailures(Throwable terminalFailure, Throwable handledFailure) {
        assertFalse(causeChainContains(terminalFailure, handledFailure));
        assertFalse(causeChainContains(handledFailure, terminalFailure));
        assertEquals(0, terminalFailure.getSuppressed().length);
        assertEquals(0, handledFailure.getSuppressed().length);
    }
}
