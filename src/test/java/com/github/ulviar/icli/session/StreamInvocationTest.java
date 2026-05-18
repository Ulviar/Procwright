package com.github.ulviar.icli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.ulviar.icli.command.ShutdownPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class StreamInvocationTest {

    @Test
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("--follow");

        StreamInvocation invocation = StreamInvocation.builder().args(arguments).build();

        arguments.add("--ignored");

        assertEquals(1, invocation.arguments().size());
        assertEquals("--follow", invocation.arguments().getFirst());
    }

    @Test
    void capturesPerStreamOverrides() {
        StreamListener listener = chunk -> {};
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        Path workingDirectory = Path.of("project");

        StreamInvocation invocation = StreamInvocation.builder()
                .args("logs", "--follow")
                .workingDirectory(workingDirectory)
                .putEnvironment("NO_COLOR", "1")
                .shutdown(shutdownPolicy)
                .timeout(Duration.ofSeconds(3))
                .keepStdinOpen()
                .onOutput(listener)
                .build();

        assertEquals("logs", invocation.arguments().getFirst());
        assertEquals(workingDirectory, invocation.workingDirectory().orElseThrow());
        assertEquals("1", invocation.environment().get("NO_COLOR"));
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.timeout().orElseThrow());
        assertEquals(StreamStdinPolicy.KEEP_OPEN, invocation.stdinPolicy());
        assertEquals(listener, invocation.listener());
    }

    @Test
    void comparesByValue() {
        StreamInvocation left = StreamInvocation.builder()
                .args("--follow")
                .putEnvironment("LC_ALL", "C")
                .timeout(Duration.ofSeconds(3))
                .keepStdinOpen()
                .build();
        StreamInvocation right = StreamInvocation.builder()
                .args("--follow")
                .putEnvironment("LC_ALL", "C")
                .timeout(Duration.ofSeconds(3))
                .keepStdinOpen()
                .build();

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void rejectsInvalidEnvironmentNameAndNegativeTimeout() {
        StreamInvocation.Builder builder = StreamInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("", "value"));
        assertThrows(IllegalArgumentException.class, () -> builder.timeout(Duration.ofMillis(-1)));
    }
}
