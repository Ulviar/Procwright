/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.testcli;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ScenarioRegistry {

    private static final List<ScenarioDefinition> DEFINITIONS = List.of(
            scenario("catalog", "metadata", "Prints the simulator scenario catalog.", ScenarioRegistry::catalog),
            scenario(
                    "exit",
                    "lifecycle",
                    "Writes configured output and exits with a configured code.",
                    LifecycleScenarios::exit),
            scenario(
                    "sleep",
                    "lifecycle",
                    "Sleeps before completing, optionally announcing start and finish.",
                    LifecycleScenarios::sleep),
            scenario(
                    "never-exit",
                    "lifecycle",
                    "Starts and then blocks until the parent terminates it.",
                    LifecycleScenarios::neverExit),
            scenario(
                    "shutdown-hook",
                    "lifecycle",
                    "Registers a slow shutdown hook and then exits or blocks.",
                    LifecycleScenarios::shutdownHook),
            scenario(
                    "spawn-child",
                    "lifecycle",
                    "Starts a child test-cli process and optionally waits for it.",
                    LifecycleScenarios::spawnChild),
            scenario(
                    "spawn-tree",
                    "lifecycle",
                    "Starts a child test-cli process that starts its own leaf process.",
                    LifecycleScenarios::spawnTree),
            scenario(
                    "repeat-spawn",
                    "lifecycle",
                    "Runs a child test-cli scenario repeatedly and reports each exit code.",
                    LifecycleScenarios::repeatSpawn),
            scenario(
                    "stream",
                    "output",
                    "Emits interleaved stdout/stderr chunks with optional delay.",
                    OutputScenarios::stream),
            scenario(
                    "long-run",
                    "output",
                    "Emits bounded heartbeat output over a configurable long-running interval.",
                    OutputScenarios::longRun),
            scenario("burst", "output", "Writes large bounded bursts to stdout and stderr.", OutputScenarios::burst),
            scenario(
                    "partial",
                    "output",
                    "Writes unterminated partial stdout/stderr and then waits.",
                    OutputScenarios::partial),
            scenario(
                    "binary",
                    "output",
                    "Writes raw bytes, including NUL and invalid text bytes.",
                    OutputScenarios::binary),
            scenario(
                    "ansi-prompt",
                    "terminal",
                    "Writes an ANSI-colored prompt without a trailing newline.",
                    OutputScenarios::ansiPrompt),
            scenario(
                    "terminal-check",
                    "terminal",
                    "Succeeds only when a console-like terminal is visible.",
                    LaunchScenarios::terminalCheck),
            scenario(
                    "platform-newlines",
                    "platform",
                    "Writes platform-sensitive newline patterns to stdout and stderr.",
                    PlatformScenarios::platformNewlines),
            scenario(
                    "platform-probe",
                    "platform",
                    "Prints OS, separator, and newline metadata visible to the child process.",
                    PlatformScenarios::platformProbe),
            scenario(
                    "stdin-echo",
                    "stdin",
                    "Reads stdin and echoes it as text, hex, or byte count.",
                    InputScenarios::stdinEcho),
            scenario(
                    "ignore-stdin",
                    "stdin",
                    "Does not drain stdin while the process remains alive.",
                    InputScenarios::ignoreStdin),
            scenario(
                    "line-repl",
                    "protocol",
                    "Runs a line-oriented request/response protocol.",
                    ProtocolScenarios::lineRepl),
            scenario(
                    "controlled-line-repl",
                    "protocol",
                    "Runs a line protocol with health, reset, delay, noise, and stderr control requests.",
                    ProtocolScenarios::controlledLineRepl),
            scenario(
                    "exit-after-read",
                    "protocol",
                    "Reads one stdin line and exits without producing a response.",
                    ProtocolScenarios::exitAfterRead),
            scenario(
                    "two-line-delay-repl",
                    "protocol",
                    "Returns two response lines per request with a configurable delay.",
                    ProtocolScenarios::twoLineDelayRepl),
            scenario(
                    "length-line-frame",
                    "protocol",
                    "Runs a length-line framed protocol for arbitrary request bodies.",
                    ProtocolScenarios::lengthLineFrame),
            scenario(
                    "jsonl",
                    "protocol",
                    "Runs a JSON Lines style protocol with optional malformed replies.",
                    ProtocolScenarios::jsonLines),
            scenario(
                    "content-length",
                    "protocol",
                    "Runs a Content-Length framed protocol.",
                    ProtocolScenarios::contentLength),
            scenario(
                    "argv-env-cwd",
                    "launch",
                    "Prints selected argv, environment, and working-directory state.",
                    LaunchScenarios::argvEnvCwd),
            scenario(
                    "mixed-load",
                    "load",
                    "Combines bounded CPU work, memory allocation, and output ticks.",
                    LoadScenarios::mixedLoad),
            scenario(
                    "flaky",
                    "nondeterminism",
                    "Uses a seed to model deterministic failure, delay, or hang.",
                    NondeterministicScenarios::flaky));

    private static final Map<String, ScenarioDefinition> BY_NAME = byName();

    private ScenarioRegistry() {}

    static Scenario find(String name) {
        ScenarioDefinition definition = BY_NAME.get(Objects.requireNonNull(name, "name"));
        if (definition == null) {
            throw new IllegalArgumentException("unknown test-cli scenario: " + name);
        }
        return definition.scenario();
    }

    private static int catalog(ScenarioContext context) throws IOException {
        String currentFamily = "";
        for (ScenarioDefinition definition : DEFINITIONS) {
            if (!definition.family().equals(currentFamily)) {
                currentFamily = definition.family();
                context.stdoutLine("[" + currentFamily + "]");
            }
            context.stdoutLine(definition.name() + " - " + definition.description());
        }
        return 0;
    }

    private static Map<String, ScenarioDefinition> byName() {
        Map<String, ScenarioDefinition> scenarios = new LinkedHashMap<>();
        for (ScenarioDefinition definition : DEFINITIONS) {
            if (scenarios.put(definition.name(), definition) != null) {
                throw new IllegalStateException("duplicate scenario: " + definition.name());
            }
        }
        return Map.copyOf(scenarios);
    }

    private static ScenarioDefinition scenario(String name, String family, String description, Scenario scenario) {
        return new ScenarioDefinition(name, family, description, scenario);
    }
}
