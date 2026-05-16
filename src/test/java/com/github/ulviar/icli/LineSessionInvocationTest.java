package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class LineSessionInvocationTest {

    @Test
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("repl");

        LineSessionInvocation invocation =
                LineSessionInvocation.builder().args(arguments).build();

        arguments.add("--ignored");

        assertEquals(1, invocation.arguments().size());
        assertEquals("repl", invocation.arguments().getFirst());
    }

    @Test
    void capturesPerSessionOverrides() {
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        Path workingDirectory = Path.of("project");

        LineSessionInvocation invocation = LineSessionInvocation.builder()
                .args("repl")
                .workingDirectory(workingDirectory)
                .putEnvironment("TERM", "dumb")
                .shutdown(shutdownPolicy)
                .idleTimeout(Duration.ofSeconds(3))
                .terminal(TerminalPolicy.REQUIRED)
                .build();

        assertEquals("repl", invocation.arguments().getFirst());
        assertEquals(workingDirectory, invocation.workingDirectory().orElseThrow());
        assertEquals("dumb", invocation.environment().get("TERM"));
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
    }

    @Test
    void comparesByValue() {
        LineSessionInvocation left = LineSessionInvocation.builder()
                .args("repl")
                .putEnvironment("LC_ALL", "C")
                .idleTimeout(Duration.ofSeconds(3))
                .terminal(TerminalPolicy.AUTO)
                .build();
        LineSessionInvocation right = LineSessionInvocation.builder()
                .args("repl")
                .putEnvironment("LC_ALL", "C")
                .idleTimeout(Duration.ofSeconds(3))
                .terminal(TerminalPolicy.AUTO)
                .build();

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void rejectsInvalidEnvironmentName() {
        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("", "value"));
    }

    @Test
    void rejectsNegativeIdleTimeout() {
        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.idleTimeout(Duration.ofMillis(-1)));
    }
}
