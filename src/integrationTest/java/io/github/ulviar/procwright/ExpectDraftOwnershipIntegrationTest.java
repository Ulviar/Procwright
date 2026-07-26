/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ScenarioDraftIntegrationSupport.invokeConcurrently;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.Expect;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExpectDraftOwnershipIntegrationTest {

    @Test
    void expectDraftBranchDoesNotMutateTheBaseSettings() {
        ExpectScenario.Draft base = Procwright.command(TestCliSupport.command())
                .interactive()
                .expect()
                .withArgs("ansi-prompt")
                .withTimeout(Duration.ofSeconds(2));
        ExpectScenario.Draft stripped = base.withAnsiControlSequenceStripping();

        assertNotSame(base, stripped);
        try (Expect expect = base.open()) {
            expect.expectText("\u001B[31mREADY\u001B[0m> ");
            assertTrue(expect.transcript().text().contains("\u001B"));
        }
    }

    @Test
    void oneDraftOpensIndependentExpectProcessesConcurrently() throws Exception {
        ExpectScenario.Draft draft = Procwright.command(TestCliSupport.command())
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(2));

        List<String> responses = invokeConcurrently(() -> exchange(draft, "alpha"), () -> exchange(draft, "beta"));

        assertTrue(responses.get(0).contains("echo:alpha"));
        assertTrue(responses.get(1).contains("echo:beta"));
    }

    @Test
    void readinessRunsThroughTheSelectedExpectHandle() {
        try (Expect expect = Procwright.command(TestCliSupport.command())
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withReadiness(handle -> handle.expectText("ready> "))
                .open()) {
            expect.sendLine("ready");
            expect.expectText("echo:ready");
        }
    }

    private static String exchange(ExpectScenario.Draft draft, String request) {
        try (Expect expect = draft.open()) {
            expect.expectText("ready> ");
            expect.sendLine(request);
            expect.expectText("echo:" + request);
            return expect.transcript().text();
        }
    }
}
