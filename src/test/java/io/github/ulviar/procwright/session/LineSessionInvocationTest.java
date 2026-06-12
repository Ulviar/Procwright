/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
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
        assertEquals("repl", invocation.arguments().get(0));
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
                .readiness(session -> {})
                .readinessTimeout(Duration.ofSeconds(4))
                .build();

        assertEquals("repl", invocation.arguments().get(0));
        assertEquals(workingDirectory, invocation.workingDirectory().orElseThrow());
        assertEquals("dumb", invocation.environment().get("TERM"));
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(4), invocation.readinessTimeout().orElseThrow());
        assertEquals(true, invocation.readinessProbe().isPresent());
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

    @Test
    void rejectsInvalidReadinessTimeout() {
        LineSessionInvocation.Builder builder = LineSessionInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.readinessTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.readinessTimeout(Duration.ofMillis(-1)));
    }
}
