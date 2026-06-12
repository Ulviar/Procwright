/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class CommandSpecTest {

    @Test
    void rejectsBlankExecutable() {
        assertThrows(
                IllegalArgumentException.class, () -> CommandSpec.builder(" ").build());
    }

    @Test
    void rejectsBlankShellCommand() {
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.shell(" "));
    }

    @Test
    void rejectsNulExecutableWithoutEchoingIt() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> CommandSpec.builder("hidden\0tool"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void rejectsNulShellCommandWithoutEchoingIt() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> CommandSpec.shell("hidden\0command"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void rejectsNulArgumentWithoutEchoingIt() {
        CommandSpec.Builder builder = CommandSpec.builder("tool");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.arg("hidden\0argument"));

        assertFalse(exception.getMessage().contains("hidden"));
    }

    @Test
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("status");

        CommandSpec spec = CommandSpec.builder("git").args(arguments).build();

        arguments.add("--short");

        assertEquals("git", spec.executable());
        assertEquals(1, spec.arguments().size());
        assertEquals("status", spec.arguments().get(0));
    }

    @Test
    void capturesWorkingDirectoryAndEnvironment() {
        Path workingDirectory = Path.of("project");

        CommandSpec spec = CommandSpec.builder("python")
                .workingDirectory(workingDirectory)
                .putEnvironment("PYTHONUTF8", "1")
                .cleanEnvironment()
                .build();

        assertEquals(workingDirectory, spec.workingDirectory().orElseThrow());
        assertEquals("1", spec.environment().get("PYTHONUTF8"));
        assertEquals(EnvironmentPolicy.CLEAN, spec.environmentPolicy());
    }

    @Test
    void comparesByValue() {
        CommandSpec left = CommandSpec.builder("git")
                .args("status")
                .workingDirectory(Path.of("project"))
                .putEnvironment("LC_ALL", "C")
                .build();
        CommandSpec right = CommandSpec.builder("git")
                .args("status")
                .workingDirectory(Path.of("project"))
                .putEnvironment("LC_ALL", "C")
                .build();

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void rejectsInvalidEnvironmentName() {
        CommandSpec.Builder builder = CommandSpec.builder("tool");

        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("BAD=NAME", "value"));
    }

    @Test
    void rejectsInvalidEnvironmentValueWithoutEchoingIt() {
        CommandSpec.Builder builder = CommandSpec.builder("tool");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("SECRET", "hidden\0value"));

        assertFalse(exception.getMessage().contains("hidden"));
    }
}
