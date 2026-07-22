/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ProcwrightCandidateAdapterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final ProcwrightCandidateAdapter candidate = new ProcwrightCandidateAdapter();

    @Test
    void runPreservesEnvironmentAndStdinConfiguration() {
        CommandOutcome environment = candidate.run(
                CommandRequest.of(fixtureCommand("env", "PROCWRIGHT_REQUEST_ENVIRONMENT"), TIMEOUT)
                        .withEnvironment("PROCWRIGHT_REQUEST_ENVIRONMENT", "preserved")
                        .withEnvironment("PROCWRIGHT_UNUSED_ENVIRONMENT", "also-preserved"),
                4096);
        CommandOutcome stdin = candidate.run(
                CommandRequest.of(fixtureCommand("stdin-echo"), TIMEOUT)
                        .withStdin("request-input".getBytes(StandardCharsets.UTF_8)),
                4096);

        assertEquals(OutcomeStatus.PASS, environment.status(), environment.note());
        assertEquals("env:preserved\n", environment.stdoutText());
        assertEquals(OutcomeStatus.PASS, stdin.status(), stdin.note());
        assertEquals("stdin:request-input", stdin.stdoutText());
    }

    @Test
    void streamStillDeliversBothSourcesThroughTheListener() {
        CommandOutcome outcome = candidate.stream(CommandRequest.of(fixtureCommand("stream", "3", "1"), TIMEOUT), 4096);

        assertEquals(OutcomeStatus.PASS, outcome.status(), outcome.note());
        assertTrue(outcome.stdoutText().contains("out:2"));
        assertTrue(outcome.stderrText().contains("err:2"));
        assertTrue(outcome.note().contains("observedWhileRunning=true"), outcome.note());
    }

    @Test
    void sessionTerminalsRetainLineExpectAndPoolBehavior() {
        CommandOutcome line = candidate.lineSession(CommandRequest.of(fixtureCommand("line-repl"), TIMEOUT), TIMEOUT);
        CommandOutcome expect = candidate.expectPrompt(CommandRequest.of(fixtureCommand("prompt"), TIMEOUT), TIMEOUT);
        CommandOutcome pooled = candidate.pooled(fixtureCommand("line-repl"), TIMEOUT);

        assertEquals(OutcomeStatus.PASS, line.status(), line.note());
        assertTrue(line.stdoutText().contains("response:alpha"));
        assertTrue(line.stdoutText().contains("response:beta"));
        assertEquals(OutcomeStatus.PASS, expect.status(), expect.note());
        assertTrue(expect.stdoutText().contains("accepted:status"));
        assertEquals(OutcomeStatus.PASS, pooled.status(), pooled.note());
        assertTrue(pooled.stdoutText().contains("created:"));
    }

    private static List<String> fixtureCommand(String... arguments) {
        ArrayList<String> command = new ArrayList<>();
        command.add(ProcessSupport.javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ComparisonFixtureProgram.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }
}
