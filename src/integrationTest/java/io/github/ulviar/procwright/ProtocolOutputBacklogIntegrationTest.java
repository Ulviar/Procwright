/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProtocolOutputBacklogIntegrationTest {

    @Test
    void stdoutBacklogOverflowIsVisibleWhenAdapterReadsOtherStream() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new StderrLineAdapter(16), call -> call.withArgs(
                                        "partial", "--stdout=" + "o".repeat(4096), "--stderr=", "--hold-millis=5000")
                                .withOutputBacklogLimit(128))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }

    @Test
    void requestAfterStdoutBacklogOverflowReportsOverflowReason() {
        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StderrLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=" + "o".repeat(4096), "--stderr=", "--hold-millis=5000")
                        .withOutputBacklogLimit(128))) {
            ProtocolSessionException overflow = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());

            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void stderrBacklogOverflowFailsOnlyWhenReadAndNeverExposesParseableSuffix() throws Exception {
        String parseableSuffix = "plausible-response\n";
        String stderr = "e".repeat(4096) + parseableSuffix;
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StderrLineAdapter(64), call -> call.withArgs(
                                "partial", "--stdout=", "--stderr=" + stderr, "--hold-millis=5000")
                        .withOutputBacklogLimit(64)
                        .withTranscriptLimit(8192));
        try {
            ProtocolSessionException overflow = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertTrue(overflow.transcript().text().contains(parseableSuffix));
            session.onExit().get(2, TimeUnit.SECONDS);

            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
            assertTrue(followUp.exitCode().isPresent());
        } finally {
            session.close();
        }
    }

    @Test
    void unreadChattyStderrDoesNotKillLongLivedProtocolSession() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new TextLineAdapter(), call -> call.withArgs("controlled-line-repl")
                        .withOutputBacklogLimit(1024))) {
            for (int request = 0; request < 5; request++) {
                assertEquals(
                        "response:stderr-burst",
                        session.request("stderr-burst", Duration.ofSeconds(5)),
                        "request " + request + " must survive unread stderr beyond the backlog limit");
            }
        }
    }

    @Test
    void unreadStderrOverflowRemainsNonfatalAfterSuccessfulResponseAndProcessExit() throws Exception {
        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=ok\n", "--stderr=" + "e".repeat(4096), "--hold-millis=0")
                        .withOutputBacklogLimit(64))) {
            assertEquals("ok", session.request("", Duration.ofSeconds(2)));
            session.onExit().get(2, TimeUnit.SECONDS);

            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, followUp.reason());
        }
    }

    @Test
    void stderrOverflowMarkerSurvivesProcessExitUntilItsFirstRead() throws Exception {
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch allowStderrRead = new CountDownLatch(1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitIgnoringInterrupts(allowStderrRead);
                return readers.stderr().readLine(16);
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=", "--stderr=" + "e".repeat(4096), "--hold-millis=0")
                .withOutputBacklogLimit(64));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProtocolSessionException> request = executor.submit(() ->
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(5))));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));
            session.onExit().get(2, TimeUnit.SECONDS);

            allowStderrRead.countDown();
            ProtocolSessionException overflow = request.get(2, TimeUnit.SECONDS);

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertTrue(overflow.exitCode().isPresent());
        } finally {
            allowStderrRead.countDown();
            session.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void stderrStaysReadableThroughBoundedQueue() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new StderrEchoAdapter(), call -> call.withArgs("controlled-line-repl")
                        .withOutputBacklogLimit(1024))) {
            assertEquals("ping", session.request("ping", Duration.ofSeconds(2)));
        }
    }

    private static final class StderrLineAdapter implements ProtocolAdapter<String, String> {

        private final int maxChars;

        private StderrLineAdapter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stderr().readLine(maxChars);
        }
    }

    private static final class StderrEchoAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(":stderr " + request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stderr().readLine(64);
        }
    }
}
