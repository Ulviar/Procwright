/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ExpectException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectOperationAdmissionTest {

    @Test
    void rejectedArgumentsAndTimeoutsDoNotMutateTranscript() {
        DefaultExpect expect = expect(new ControllableProcess(new FeedInputStream(), new FeedInputStream()));
        try {
            String before = expect.transcript().text();

            assertThrows(NullPointerException.class, () -> expect.send(null));
            assertThrows(NullPointerException.class, () -> expect.sendLine(null));
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("bad\nline"));
            assertThrows(NullPointerException.class, () -> expect.expectTextMatch(null));
            assertThrows(NullPointerException.class, () -> expect.expectRegexMatch(null));
            assertThrows(NullPointerException.class, () -> expect.expectTextMatch("x", null));
            assertThrows(NullPointerException.class, () -> expect.expectRegexMatch(Pattern.compile("x"), null));
            assertThrows(IllegalArgumentException.class, () -> expect.expectTextMatch("x", Duration.ZERO));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> expect.expectRegexMatch(Pattern.compile("x"), Duration.ofNanos(-1)));

            assertEquals(before, expect.transcript().text());
        } finally {
            expect.close();
        }
    }

    @Test
    void operationsStartedAfterCloseFailBeforeTranscriptOrStdinMutation() {
        DefaultExpect expect = expect(new ControllableProcess(new FeedInputStream(), new FeedInputStream()));
        expect.close();
        String before = expect.transcript().text();

        for (Runnable operation : List.<Runnable>of(
                () -> expect.send("text"),
                () -> expect.sendLine("line"),
                () -> expect.expectTextMatch(""),
                () -> expect.expectRegexMatch(Pattern.compile(".*")))) {
            ExpectException failure = assertThrows(ExpectException.class, operation::run);
            assertEquals(ExpectException.Reason.CLOSED, failure.reason());
            assertEquals(before, expect.transcript().text());
        }
    }

    @Test
    void inputWriteFailureClosesProcessAndOwnsLaterOperationsAndExit() throws Exception {
        IOException writeFailure = new IOException("broken pipe");
        OutputStream stdin = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw writeFailure;
            }
        };
        ControllableProcess process = new ControllableProcess(stdin, new FeedInputStream(), new FeedInputStream());
        DefaultExpect expect = expect(process);

        try {
            ExpectException selected = assertThrows(ExpectException.class, () -> expect.send("text"));

            assertEquals(ExpectException.Reason.FAILURE, selected.reason());
            assertTrue(process.awaitDestroyed());
            ExecutionException exitFailure =
                    assertThrows(ExecutionException.class, () -> expect.onExit().get(1, TimeUnit.SECONDS));
            assertSame(selected, exitFailure.getCause());
            assertSame(selected, assertThrows(ExpectException.class, () -> expect.send("again")));
        } finally {
            expect.close();
        }
    }

    @Test
    void fatalInputWriteFailureKeepsItsIdentityAndTerminatesTheSession() throws Exception {
        AssertionError writeFailure = new AssertionError("fatal broken pipe");
        OutputStream stdin = new OutputStream() {
            @Override
            public void write(int value) {
                throw writeFailure;
            }
        };
        ControllableProcess process = new ControllableProcess(stdin, new FeedInputStream(), new FeedInputStream());
        DefaultExpect expect = expect(process);

        try {
            assertSame(writeFailure, assertThrows(AssertionError.class, () -> expect.send("text")));
            assertTrue(process.awaitDestroyed());
            ExecutionException exitFailure =
                    assertThrows(ExecutionException.class, () -> expect.onExit().get(1, TimeUnit.SECONDS));
            assertSame(writeFailure, exitFailure.getCause());
            assertSame(writeFailure, assertThrows(AssertionError.class, () -> expect.send("again")));
        } finally {
            expect.close();
        }
    }

    @Test
    void repeatedCloseStdinRecordsOneTranscriptAction() {
        DefaultExpect expect = expect(new ControllableProcess(new FeedInputStream(), new FeedInputStream()));
        try {
            expect.closeStdin();
            expect.closeStdin();

            String transcript = expect.transcript().text();
            assertTrue(transcript.contains("close stdin"));
            assertEquals(transcript.indexOf("close stdin"), transcript.lastIndexOf("close stdin"));
        } finally {
            expect.close();
        }
    }
}
