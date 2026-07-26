/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.session.ExpectException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectOperationAdmissionTest {

    @Test
    void rejectedArgumentsAndTimeoutsDoNotMutateTranscript() {
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults());
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
        DefaultExpect expect = new DefaultExpect(
                session(new ControllableProcess(new FeedInputStream(), new FeedInputStream())),
                ExpectSettings.defaults());
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
}
