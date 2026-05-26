package io.github.ulviar.icli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class SessionInvocationTest {

    @Test
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("-i");

        SessionInvocation invocation =
                SessionInvocation.builder().args(arguments).build();

        arguments.add("--ignored");

        assertEquals(1, invocation.arguments().size());
        assertEquals("-i", invocation.arguments().get(0));
    }

    @Test
    void capturesPerSessionOverrides() {
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        Path workingDirectory = Path.of("project");

        SessionInvocation invocation = SessionInvocation.builder()
                .args("-i")
                .workingDirectory(workingDirectory)
                .putEnvironment("TERM", "dumb")
                .shutdown(shutdownPolicy)
                .idleTimeout(Duration.ofSeconds(3))
                .charset(StandardCharsets.UTF_8)
                .terminal(TerminalPolicy.REQUIRED)
                .readiness(session -> {})
                .readinessTimeout(Duration.ofSeconds(4))
                .build();

        assertEquals("-i", invocation.arguments().get(0));
        assertEquals(workingDirectory, invocation.workingDirectory().orElseThrow());
        assertEquals("dumb", invocation.environment().get("TERM"));
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.idleTimeout().orElseThrow());
        assertEquals(StandardCharsets.UTF_8, invocation.charset().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(4), invocation.readinessTimeout().orElseThrow());
        assertEquals(true, invocation.readinessProbe().isPresent());
    }

    @Test
    void rejectsInvalidEnvironmentName() {
        SessionInvocation.Builder builder = SessionInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("", "value"));
    }

    @Test
    void rejectsNegativeIdleTimeout() {
        SessionInvocation.Builder builder = SessionInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.idleTimeout(Duration.ofMillis(-1)));
    }

    @Test
    void rejectsInvalidReadinessTimeout() {
        SessionInvocation.Builder builder = SessionInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.readinessTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.readinessTimeout(Duration.ofMillis(-1)));
    }
}
