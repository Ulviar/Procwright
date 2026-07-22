/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class SystemPtyProviderTest {

    private static final Path SCRIPT = PtyTestPaths.SCRIPT;
    private static final Path SHELL = PtyTestPaths.SHELL;
    private static final Path STTY = PtyTestPaths.STTY;
    private static final Path ENV = PtyTestPaths.ENV;
    private static final Path DD = PtyTestPaths.DD;

    @Test
    void windowsIsExplicitlyUnavailableWithoutProbingUnixExecutables() {
        SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect(
                "Windows 11",
                path -> fail("Windows detection must not probe Unix executable " + path),
                (flavor, tools) -> fail("Windows detection must not launch a capability probe"),
                () -> fail("Windows detection must not construct Unix tool candidates"));

        assertFalse(support.available());
        assertEquals(SystemPtyProvider.ScriptFlavor.UNAVAILABLE, support.flavor());
        assertEquals("Windows ConPTY support is not implemented in the core artifact yet", support.description());
    }

    @Test
    void darwinIsNotMisclassifiedAsWindows() {
        Predicate<Path> allPrerequisites = executablePaths(SCRIPT, SHELL, STTY, ENV, DD);

        SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect(
                "Darwin",
                allPrerequisites,
                (flavor, tools) -> flavor == SystemPtyProvider.ScriptFlavor.BSD,
                PtyTestPaths.candidates());

        assertSupport(support, SystemPtyProvider.ScriptFlavor.BSD);
    }

    @Test
    void exactCapabilityProbeSelectsLinuxAndBsdFlavors() {
        Predicate<Path> allPrerequisites = executablePaths(SCRIPT, SHELL, STTY, ENV, DD);

        SystemPtyProvider.SystemPtySupport linux = SystemPtyProvider.SystemPtySupport.detect(
                "Linux",
                allPrerequisites,
                (flavor, tools) -> flavor == SystemPtyProvider.ScriptFlavor.UTIL_LINUX,
                PtyTestPaths.candidates());
        SystemPtyProvider.SystemPtySupport bsd = SystemPtyProvider.SystemPtySupport.detect(
                "Mac OS X",
                allPrerequisites,
                (flavor, tools) -> flavor == SystemPtyProvider.ScriptFlavor.BSD,
                PtyTestPaths.candidates());

        assertSupport(linux, SystemPtyProvider.ScriptFlavor.UTIL_LINUX);
        assertSupport(bsd, SystemPtyProvider.ScriptFlavor.BSD);
    }

    @Test
    void missingBoundedReaderFailsBeforeCapabilityProbing() {
        SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect(
                "Mac OS X",
                executablePaths(SCRIPT, SHELL, STTY, ENV),
                (flavor, tools) -> fail("missing dd(1) must prevent capability probes"),
                PtyTestPaths.candidates());

        assertFalse(support.available());
        assertEquals("Unix PTY support requires dd(1) in a trusted system path", support.description());
    }

    @Test
    void eachMissingPrerequisiteMakesTheProviderExplicitlyUnavailable() {
        List<DetectionCase> cases = List.of(
                new DetectionCase(
                        executablePaths(SHELL, STTY, ENV, DD),
                        "Unix PTY support requires script(1) in a trusted system path"),
                new DetectionCase(
                        executablePaths(SCRIPT, STTY, ENV, DD), "Unix PTY support requires executable /bin/sh"),
                new DetectionCase(
                        executablePaths(SCRIPT, SHELL, ENV, DD),
                        "Unix PTY support requires stty(1) in a trusted system path"),
                new DetectionCase(
                        executablePaths(SCRIPT, SHELL, STTY, DD),
                        "Unix PTY support requires env(1) in a trusted system path"),
                new DetectionCase(
                        executablePaths(SCRIPT, SHELL, STTY, ENV),
                        "Unix PTY support requires dd(1) in a trusted system path"));

        for (DetectionCase detectionCase : cases) {
            SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect(
                    "Linux",
                    detectionCase.executableProbe(),
                    (flavor, tools) -> fail("missing prerequisites must prevent capability probes"),
                    PtyTestPaths.candidates());

            assertFalse(support.available(), detectionCase.expectedDescription());
            assertEquals(SystemPtyProvider.ScriptFlavor.UNAVAILABLE, support.flavor());
            assertEquals(detectionCase.expectedDescription(), support.description());
        }
    }

    @Test
    void busyBoxUnknownAndFailedExitPropagationStayUnavailable() {
        Predicate<Path> allPrerequisites = executablePaths(SCRIPT, SHELL, STTY, ENV, DD);
        AtomicInteger probes = new AtomicInteger();

        SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect(
                "Linux",
                allPrerequisites,
                (flavor, tools) -> {
                    probes.incrementAndGet();
                    return false;
                },
                PtyTestPaths.candidates());

        assertFalse(support.available());
        assertEquals(SystemPtyProvider.ScriptFlavor.UNAVAILABLE, support.flavor());
        assertEquals(2, probes.get(), "both exact Linux-compatible invocations must be rejected");
        assertTrue(support.description().contains("capability probe"));
    }

    @Test
    void detectionUsesTheExactScriptPathThatPassedTheProbe() {
        Path secondScript = PtyTestPaths.SECOND_SCRIPT;
        Predicate<Path> allPrerequisites = executablePaths(SCRIPT, secondScript, SHELL, STTY, ENV, DD);

        SystemPtyProvider.SystemPtySupport support = SystemPtyProvider.SystemPtySupport.detect(
                "Linux",
                allPrerequisites,
                (flavor, tools) ->
                        tools.scriptPath().equals(secondScript) && flavor == SystemPtyProvider.ScriptFlavor.UTIL_LINUX,
                PtyTestPaths.candidates());

        assertTrue(support.available());
        assertEquals(secondScript, support.scriptPath());
    }

    @Test
    void launchPlanSeparatesHostileChildEnvironmentFromMinimalWrapperEnvironment() {
        SystemPtyProvider provider = new SystemPtyProvider(supported(SystemPtyProvider.ScriptFlavor.BSD));
        LinkedHashMap<String, String> childEnvironment = new LinkedHashMap<>();
        childEnvironment.put("SHELLOPTS", "xtrace");
        childEnvironment.put("PS4", "$(should-not-run) secret-value");
        childEnvironment.put("LD_PRELOAD", "/tmp/not-a-library");
        childEnvironment.put("DYLD_INSERT_LIBRARIES", "/tmp/not-a-library");
        childEnvironment.put("EXACT", " spaces ' quotes\nline=two Ж");
        PtyRequest request = new PtyRequest(
                List.of("/usr/bin/true", "$(hostile)", "a=b", "line\nbreak"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                childEnvironment,
                new TerminalSize(80, 24));

        SystemPtyProvider.PtyLaunchPlan plan = provider.planFor(request);
        List<String> wrapperCommand =
                PtyBootstrap.commandFor(supported(SystemPtyProvider.ScriptFlavor.BSD), plan.terminalSize());

        assertEquals(
                Map.of("SHELL", SHELL.toString(), "LC_ALL", "C", "LANG", "C", "TERM", "dumb"),
                plan.wrapperEnvironment());
        assertFalse(wrapperCommand.toString().contains("secret-value"));
        assertFalse(wrapperCommand.toString().contains("LD_PRELOAD"));
        assertFalse(plan.wrapperEnvironment().keySet().stream().anyMatch(childEnvironment::containsKey));
        assertEquals(
                " spaces ' quotes\nline=two Ж", plan.payload().environment().get("EXACT"));
        assertEquals(request.command(), plan.payload().command());
        assertEquals("xterm-256color", plan.payload().environment().get("TERM"));
        assertNotEquals(plan.payload().environment(), plan.wrapperEnvironment());
    }

    @Test
    void bsdAndUtilLinuxCommandsContainOnlyFixedWrapperTokens() {
        PtyRequest request = new PtyRequest(
                List.of("/usr/bin/true", "secret-command-argument"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of("SECRET_NAME", "secret-environment-value"),
                new TerminalSize(91, 37));
        SystemPtyProvider.PtyLaunchPlan bsd =
                new SystemPtyProvider(supported(SystemPtyProvider.ScriptFlavor.BSD)).planFor(request);
        SystemPtyProvider.PtyLaunchPlan utilLinux =
                new SystemPtyProvider(supported(SystemPtyProvider.ScriptFlavor.UTIL_LINUX)).planFor(request);
        List<String> bsdCommand =
                PtyBootstrap.commandFor(supported(SystemPtyProvider.ScriptFlavor.BSD), bsd.terminalSize());
        List<String> utilLinuxCommand =
                PtyBootstrap.commandFor(supported(SystemPtyProvider.ScriptFlavor.UTIL_LINUX), utilLinux.terminalSize());

        assertEquals(
                List.of(
                        SCRIPT.toString(),
                        "-q",
                        "-e",
                        "/dev/null",
                        SHELL.toString(),
                        "-c",
                        PtyBootstrap.SHELL_PROGRAM,
                        "procwright-pty-bootstrap",
                        STTY.toString(),
                        ENV.toString(),
                        DD.toString(),
                        "37",
                        "91"),
                bsdCommand);
        String utilLinuxWrapper = List.of(
                        SHELL.toString(),
                        "-c",
                        PtyBootstrap.SHELL_PROGRAM,
                        "procwright-pty-bootstrap",
                        STTY.toString(),
                        ENV.toString(),
                        DD.toString(),
                        "37",
                        "91")
                .stream()
                .map(SystemPtyProviderTest::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
        assertEquals(
                List.of(SCRIPT.toString(), "-q", "-e", "-c", "exec " + utilLinuxWrapper, "/dev/null"),
                utilLinuxCommand);
        for (List<String> command : List.of(bsdCommand, utilLinuxCommand)) {
            String rendered = String.join(" ", command);
            assertFalse(rendered.contains("secret-command-argument"));
            assertFalse(rendered.contains("SECRET_NAME"));
            assertFalse(rendered.contains("secret-environment-value"));
        }
    }

    @Test
    void assignmentLikeExecutableFailsClosedWithoutDisclosingTheToken() {
        String executable = "hostile=executable-secret";
        SystemPtyProvider provider = new SystemPtyProvider(
                supported(SystemPtyProvider.ScriptFlavor.BSD),
                PtyBootstrap::prepare,
                builder -> fail("an ambiguous executable must fail before ProcessBuilder.start"),
                Duration.ofSeconds(1));
        PtyRequest request = new PtyRequest(
                List.of(executable, "", "a=b"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of(),
                new TerminalSize(80, 24));

        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> provider.start(request));

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
        for (Throwable current = failure; current != null; current = current.getCause()) {
            assertFalse(String.valueOf(current.getMessage()).contains(executable));
        }
    }

    @Test
    void optionLikeExecutableRemainsOpaqueAfterTheProbedEnvSeparator() {
        SystemPtyProvider provider = new SystemPtyProvider(supported(SystemPtyProvider.ScriptFlavor.BSD));
        PtyRequest request = new PtyRequest(
                List.of("-opaque-target", "", "a=b"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of("PATH", "/trusted-by-child-only"),
                new TerminalSize(80, 24));

        SystemPtyProvider.PtyLaunchPlan plan = provider.planFor(request);

        assertEquals(request.command(), plan.payload().command());
        assertTrue(PtyBootstrap.SHELL_PROGRAM.contains("exec \"$env_path\" -i -- \"$@\""));
        assertFalse(PtyBootstrap.SHELL_PROGRAM.contains("arch"));
    }

    @Test
    void launchFailureDoesNotExposeChildEnvironmentValues() {
        String secret = "secret-environment-value-4d673a";
        String secretExecutable = "/secret-target-4d673a";
        SystemPtyProvider.SystemPtySupport missing = new SystemPtyProvider.SystemPtySupport(
                SystemPtyProvider.ScriptFlavor.BSD,
                PtyTestPaths.MISSING_SCRIPT,
                SHELL,
                STTY,
                ENV,
                DD,
                "missing test provider");
        SystemPtyProvider provider = new SystemPtyProvider(missing);
        PtyRequest request = new PtyRequest(
                List.of(secretExecutable, "secret-argument-4d673a"),
                Optional.empty(),
                EnvironmentPolicy.CLEAN,
                Map.of("SECRET", secret),
                new TerminalSize(80, 24));

        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> provider.start(request));

        for (Throwable current = failure; current != null; current = current.getCause()) {
            assertFalse(String.valueOf(current.getMessage()).contains(secret));
            assertFalse(String.valueOf(current.getMessage()).contains(secretExecutable));
            assertFalse(String.valueOf(current.getMessage()).contains("secret-argument-4d673a"));
        }
    }

    @Test
    void helperPathsMustBeAbsoluteEvenForInjectedSupport() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemTools(Path.of("script"), SHELL, STTY, ENV, DD));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemPtySupport(
                        SystemPtyProvider.ScriptFlavor.BSD, SCRIPT, Path.of("sh"), STTY, ENV, DD, "relative helper"));
    }

    @Test
    void detectionCandidatePathsMustAllBeAbsolute() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemToolCandidates(
                        List.of(Path.of("script")), SHELL, List.of(STTY), List.of(ENV), List.of(DD)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemToolCandidates(
                        List.of(SCRIPT), Path.of("sh"), List.of(STTY), List.of(ENV), List.of(DD)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemToolCandidates(
                        List.of(SCRIPT), SHELL, List.of(Path.of("stty")), List.of(ENV), List.of(DD)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemToolCandidates(
                        List.of(SCRIPT), SHELL, List.of(STTY), List.of(Path.of("env")), List.of(DD)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SystemPtyProvider.SystemToolCandidates(
                        List.of(SCRIPT), SHELL, List.of(STTY), List.of(ENV), List.of(Path.of("dd"))));
    }

    private static void assertSupport(
            SystemPtyProvider.SystemPtySupport support, SystemPtyProvider.ScriptFlavor expectedFlavor) {
        assertTrue(support.available());
        assertEquals(expectedFlavor, support.flavor());
        assertEquals(SCRIPT, support.scriptPath());
        assertEquals(SHELL, support.shellPath());
        assertEquals(STTY, support.sttyPath());
        assertEquals(ENV, support.envPath());
        assertEquals(DD, support.ddPath());
    }

    private static SystemPtyProvider.SystemPtySupport supported(SystemPtyProvider.ScriptFlavor flavor) {
        return new SystemPtyProvider.SystemPtySupport(flavor, SCRIPT, SHELL, STTY, ENV, DD, flavor + " test provider");
    }

    private static Predicate<Path> executablePaths(Path... paths) {
        Set<Path> executable = Set.of(paths);
        return executable::contains;
    }

    private static String shellQuote(String value) {
        return value.isEmpty() ? "''" : "'" + value.replace("'", "'\\''") + "'";
    }

    private record DetectionCase(Predicate<Path> executableProbe, String expectedDescription) {}
}
