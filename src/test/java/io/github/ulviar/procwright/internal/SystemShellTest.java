/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SystemShellTest {

    private static final Path SYSTEM_ROOT =
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("procwright-system-root");
    private static final Path SYSTEM_DIRECTORY = SYSTEM_ROOT.resolve("System32");
    private static final Path COMMAND_INTERPRETER = SYSTEM_DIRECTORY.resolve("cmd.exe");
    private static final Path ATTACKER_ROOT = SYSTEM_ROOT.resolveSibling("procwright-attacker-root");

    @Test
    void nonWindowsUsesTheFixedPosixShellWithoutConsultingWindowsState() {
        TestPlatform platform = new TestPlatform(false);

        assertEquals(List.of("/bin/sh", "-c", "printf ready"), SystemShell.command("printf ready", platform));
        assertEquals(0, platform.windowsSystemRootReads);
    }

    @Test
    void windowsUsesOnlyTheCanonicalAbsoluteSystem32CommandInterpreter() {
        TestPlatform platform = new TestPlatform(true);

        assertEquals(
                List.of(COMMAND_INTERPRETER.toString(), "/d", "/s", "/c", "echo ready"),
                SystemShell.command("echo ready", platform));
        assertEquals(1, platform.windowsSystemRootReads);
    }

    @Test
    void windowsFailsClosedWithoutAnAbsoluteSystemRoot() {
        TestPlatform missing = new TestPlatform(true);
        missing.systemRoot = Optional.empty();
        assertLaunchFailure(missing);

        TestPlatform relative = new TestPlatform(true);
        relative.systemRoot = Optional.of(Path.of("relative-windows"));
        assertLaunchFailure(relative);
    }

    @Test
    void windowsFailsClosedWhenCanonicalResolutionEscapesTheTrustedSystemRoot() {
        TestPlatform systemDirectoryEscape = new TestPlatform(true);
        systemDirectoryEscape.canonicalSystemDirectory = ATTACKER_ROOT.resolve("System32");
        assertLaunchFailure(systemDirectoryEscape);

        TestPlatform platform = new TestPlatform(true);
        platform.canonicalCommandInterpreter = ATTACKER_ROOT.resolve("cmd.exe");

        assertLaunchFailure(platform);
    }

    @Test
    void windowsFailsClosedWhenSystem32CanonicalizesToAnotherDirectoryInsideTheRoot() {
        TestPlatform platform = new TestPlatform(true);
        platform.canonicalSystemDirectory = SYSTEM_ROOT.resolve("relocated-system32");

        assertLaunchFailure(platform);
    }

    @Test
    void windowsCanonicalPathComparisonIsCaseInsensitiveOnEveryTestPlatform() {
        TestPlatform platform = new TestPlatform(true);
        Path differentlyCasedSystemDirectory = SYSTEM_ROOT.resolve("sYsTeM32");
        platform.canonicalSystemDirectory = differentlyCasedSystemDirectory;

        assertEquals(
                List.of(differentlyCasedSystemDirectory.resolve("cmd.exe").toString(), "/d", "/s", "/c", "echo ready"),
                SystemShell.command("echo ready", platform));
    }

    @Test
    void windowsFailsClosedUnlessTheInterpreterIsAUsableRegularExecutable() {
        TestPlatform notRegular = new TestPlatform(true);
        notRegular.regularFile = false;
        assertLaunchFailure(notRegular);

        TestPlatform notReadable = new TestPlatform(true);
        notReadable.readable = false;
        assertLaunchFailure(notReadable);

        TestPlatform notExecutable = new TestPlatform(true);
        notExecutable.executable = false;
        assertLaunchFailure(notExecutable);
    }

    @Test
    void windowsRedactsCanonicalResolutionIoFailurePaths() {
        String sensitivePath = SYSTEM_ROOT.resolve("private-location").toString();
        TestPlatform platform = new TestPlatform(true);
        platform.resolutionFailure = new NoSuchFileException(sensitivePath);

        CommandExecutionException failure = assertLaunchFailure(platform);

        assertEquals("Trusted Windows command interpreter is unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(sensitivePath));
    }

    @Test
    void windowsRedactsCanonicalResolutionSecurityFailurePaths() {
        String sensitivePath = SYSTEM_ROOT.resolve("denied-location").toString();
        TestPlatform platform = new TestPlatform(true);
        platform.securityFailure = new SecurityException("access denied: " + sensitivePath);

        CommandExecutionException failure = assertLaunchFailure(platform);

        assertEquals("Trusted Windows command interpreter is unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(sensitivePath));
    }

    private static CommandExecutionException assertLaunchFailure(TestPlatform platform) {
        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> SystemShell.command("echo ready", platform));
        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, failure.reason());
        return failure;
    }

    private static final class TestPlatform implements SystemShell.Platform {
        private final boolean windows;
        private Optional<Path> systemRoot = Optional.of(SYSTEM_ROOT);
        private Path canonicalSystemDirectory = SYSTEM_DIRECTORY;
        private Path canonicalCommandInterpreter = COMMAND_INTERPRETER;
        private boolean regularFile = true;
        private boolean readable = true;
        private boolean executable = true;
        private IOException resolutionFailure;
        private SecurityException securityFailure;
        private int windowsSystemRootReads;

        private TestPlatform(boolean windows) {
            this.windows = windows;
        }

        @Override
        public boolean isWindows() {
            return windows;
        }

        @Override
        public Optional<Path> windowsSystemRoot() {
            windowsSystemRootReads++;
            return systemRoot;
        }

        @Override
        public Path toRealPath(Path path) throws IOException {
            if (securityFailure != null) {
                throw securityFailure;
            }
            if (resolutionFailure != null) {
                throw resolutionFailure;
            }
            if (path.equals(SYSTEM_DIRECTORY)) {
                return canonicalSystemDirectory;
            }
            if (path.equals(COMMAND_INTERPRETER)) {
                return canonicalCommandInterpreter;
            }
            return path;
        }

        @Override
        public boolean isRegularFile(Path path) {
            return regularFile;
        }

        @Override
        public boolean isReadable(Path path) {
            return readable;
        }

        @Override
        public boolean isExecutable(Path path) {
            return executable;
        }

        @Override
        public boolean sameWindowsPath(Path first, Path second) {
            return first.toString().equalsIgnoreCase(second.toString());
        }
    }
}
