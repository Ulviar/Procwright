package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("status");

        CommandSpec spec = CommandSpec.builder("git").args(arguments).build();

        arguments.add("--short");

        assertEquals("git", spec.executable());
        assertEquals(1, spec.arguments().size());
        assertEquals("status", spec.arguments().getFirst());
    }

    @Test
    void capturesWorkingDirectoryAndEnvironment() {
        Path workingDirectory = Path.of("project");

        CommandSpec spec = CommandSpec.builder("python")
                .workingDirectory(workingDirectory)
                .putEnvironment("PYTHONUTF8", "1")
                .build();

        assertEquals(workingDirectory, spec.workingDirectory().orElseThrow());
        assertEquals("1", spec.environment().get("PYTHONUTF8"));
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
}
