/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandException;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.testcli.TestCli;
import org.junit.jupiter.api.Test;

final class ApiUsageExamplesTest {

    @Test
    void runSnippetReturnsOutputAndRejectsNonzeroExitWithItsDiagnostics() {
        CommandSpec success = ExampleSupport.workerCommand("finite");
        assertEquals(
                "procwright-ready\n",
                ApiUsageExamples.runCommand(
                        success.executable(), success.arguments().toArray(String[]::new)));

        CommandSpec failure = testCommand().withArgs("exit", "--exit-code=7", "--stderr=failed command");
        CommandException exception = assertThrows(
                CommandException.class,
                () -> ApiUsageExamples.runCommand(
                        failure.executable(), failure.arguments().toArray(String[]::new)));
        assertEquals(7, exception.result().exitCode().orElseThrow());
        assertEquals("failed command", exception.result().stderr());
    }

    @Test
    void adapterSnippetFlushesRequestsAndKeepsConsecutiveResponsesSeparate() {
        try (ProtocolSession<String, String> session = Procwright.command(ExampleSupport.workerCommand("line"))
                .protocolSession(ApiUsageExamples::lineAdapter)
                .open()) {
            assertEquals("response:café 世界", session.request("café 世界"));
            assertEquals("response:", session.request(""));
        }
    }

    @Test
    void expectSnippetAnswersAnUnterminatedPromptAndRequiresTheMatchingAcknowledgment() {
        String reply = "café 世界";
        ApiUsageExamples.answerPrompt(ExampleSupport.workerCommand("expect"), reply);

        CommandSpec rejectedReply = testCommand()
                .withArgs("line-repl", "--prompt=ready> ", "--exit-command=" + reply, "--bye-text=rejected reply");
        ExpectException exception =
                assertThrows(ExpectException.class, () -> ApiUsageExamples.answerPrompt(rejectedReply, reply));
        assertEquals(ExpectException.Reason.EOF, exception.reason());
        assertTrue(exception.transcript().text().contains("rejected reply"));
    }

    private static CommandSpec testCommand() {
        return CommandSpec.of(ExampleSupport.javaExecutable())
                .withArgs("-cp", System.getProperty("java.class.path"), TestCli.class.getName());
    }
}
