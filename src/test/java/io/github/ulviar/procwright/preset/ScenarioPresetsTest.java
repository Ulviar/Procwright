/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.preset;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.InteractiveScenario;
import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.RunScenario;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ScenarioPresetsTest {

    @Test
    void presetsTransformDraftsWithoutMutatingTheirInputs() {
        RunScenario.Draft run = Procwright.command("tool").run();
        LineSessionScenario.Draft line = Procwright.command("tool").lineSession();
        InteractiveScenario.Draft interactive = Procwright.command("tool").interactive();
        StreamScenario.Draft stream = Procwright.command("tool").listen();

        assertNotSame(run, ScenarioPresets.commandAutomation(run, Duration.ofSeconds(3), 4096));
        assertNotSame(run, ScenarioPresets.environmentDiagnostics(run, Duration.ofSeconds(2), 2048));
        assertNotSame(run, ScenarioPresets.binaryOutputCapture(run, Duration.ofSeconds(4), 8192));
        assertInstanceOf(
                LineSessionScenario.Draft.class,
                ScenarioPresets.replLineMode(line, Duration.ofMinutes(1), TerminalPolicy.AUTO));
        assertInstanceOf(
                InteractiveScenario.Draft.class,
                ScenarioPresets.promptAutomationSession(interactive, Duration.ofSeconds(30), TerminalPolicy.REQUIRED));
        assertInstanceOf(
                InteractiveScenario.Draft.class,
                ScenarioPresets.terminalRequiredSession(interactive, Duration.ofSeconds(15)));
        assertInstanceOf(StreamScenario.Draft.class, ScenarioPresets.logFollowing(stream, Duration.ofSeconds(10)));
    }

    @Test
    void presetsRejectInvalidPolicies() {
        RunScenario.Draft run = Procwright.command("tool").run();
        LineSessionScenario.Draft line = Procwright.command("tool").lineSession();
        InteractiveScenario.Draft interactive = Procwright.command("tool").interactive();
        StreamScenario.Draft stream = Procwright.command("tool").listen();

        assertThrows(IllegalArgumentException.class, () -> ScenarioPresets.commandAutomation(run, Duration.ZERO, 1024));
        assertThrows(
                IllegalArgumentException.class, () -> ScenarioPresets.environmentDiagnostics(run, Duration.ZERO, 1024));
        assertThrows(
                IllegalArgumentException.class, () -> ScenarioPresets.binaryOutputCapture(run, Duration.ZERO, 1024));
        assertThrows(
                IllegalArgumentException.class, () -> ScenarioPresets.commandAutomation(run, Duration.ofSeconds(1), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScenarioPresets.replLineMode(line, Duration.ofSeconds(-1), TerminalPolicy.DISABLED));
        assertThrows(NullPointerException.class, () -> ScenarioPresets.replLineMode(line, Duration.ofSeconds(1), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ScenarioPresets.promptAutomationSession(
                        interactive, Duration.ofSeconds(-1), TerminalPolicy.DISABLED));
        assertThrows(
                IllegalArgumentException.class, () -> ScenarioPresets.logFollowing(stream, Duration.ofSeconds(-1)));
    }
}
