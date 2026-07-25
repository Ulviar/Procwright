/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.invokeConcurrently;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.Session;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExpectDraftOwnershipIntegrationTest {

    @Test
    void expectDraftBranchDoesNotMutateTheBaseSettings() {
        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("ansi-prompt")
                .open()) {
            Expect.Draft base = session.expect().withTimeout(Duration.ofSeconds(2));
            Expect.Draft stripped = base.withAnsiControlSequenceStripping();

            assertNotSame(base, stripped);
            try (Expect expect = base.open()) {
                expect.expectText("\u001B[31mREADY\u001B[0m> ");
                assertTrue(expect.transcript().text().contains("\u001B"));
            }
        }
    }

    @Test
    void configuringExpectDoesNotClaimSessionOutput() throws Exception {
        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl")
                .open()) {
            Expect.Draft draft = session.expect().withTimeout(Duration.ofSeconds(1));

            session.sendLine("pid");
            BufferedReader stdout = new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8));
            assertTrue(stdout.readLine().startsWith("response:pid:"));
            assertThrows(IllegalStateException.class, draft::open);
        }
    }

    @Test
    void secondExpectOpenFailsWhileTheFirstOwnsOutput() {
        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl")
                .open()) {
            Expect.Draft draft = session.expect().withTimeout(Duration.ofSeconds(1));
            try (Expect expect = draft.open()) {
                assertNotNull(expect);
                assertThrows(IllegalStateException.class, draft::open);
            }
        }
    }

    @Test
    void concurrentExpectOpensHaveExactlyOneOutputOwner() throws Exception {
        try (Session session = Procwright.command(TestCliSupport.command())
                .interactive()
                .withArgs("controlled-line-repl")
                .open()) {
            Expect.Draft draft = session.expect().withTimeout(Duration.ofSeconds(1));
            List<ExpectOpenAttempt> attempts = invokeConcurrently(() -> attemptOpen(draft), () -> attemptOpen(draft));
            List<Expect> opened = attempts.stream()
                    .filter(attempt -> attempt.expect() != null)
                    .map(ExpectOpenAttempt::expect)
                    .toList();
            List<Throwable> failures = attempts.stream()
                    .filter(attempt -> attempt.failure() != null)
                    .map(ExpectOpenAttempt::failure)
                    .toList();
            try {
                assertEquals(1, opened.size());
                assertEquals(1, failures.size());
                assertInstanceOf(IllegalStateException.class, failures.get(0));
            } finally {
                opened.forEach(Expect::close);
            }
        }
    }

    private static ExpectOpenAttempt attemptOpen(Expect.Draft draft) {
        try {
            return new ExpectOpenAttempt(draft.open(), null);
        } catch (Throwable failure) {
            return new ExpectOpenAttempt(null, failure);
        }
    }

    private record ExpectOpenAttempt(Expect expect, Throwable failure) {}
}
