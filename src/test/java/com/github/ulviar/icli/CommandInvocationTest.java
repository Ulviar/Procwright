package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class CommandInvocationTest {

    @Test
    void capturesImmutableArgumentsSnapshot() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("logs");

        CommandInvocation invocation =
                CommandInvocation.builder().args(arguments).build();

        arguments.add("--follow");

        assertEquals(1, invocation.arguments().size());
        assertEquals("logs", invocation.arguments().getFirst());
    }

    @Test
    void capturesPerCallOverrides() {
        CapturePolicy.Bounded capturePolicy = CapturePolicy.bounded(1024);
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        Path workingDirectory = Path.of("project");

        CommandInvocation invocation = CommandInvocation.builder()
                .args("status")
                .workingDirectory(workingDirectory)
                .putEnvironment("TERM", "dumb")
                .capture(capturePolicy)
                .shutdown(shutdownPolicy)
                .timeout(Duration.ofSeconds(3))
                .charset(StandardCharsets.UTF_8)
                .output(OutputMode.MERGED)
                .build();

        assertEquals("status", invocation.arguments().getFirst());
        assertEquals(workingDirectory, invocation.workingDirectory().orElseThrow());
        assertEquals("dumb", invocation.environment().get("TERM"));
        assertEquals(capturePolicy, invocation.capturePolicy().orElseThrow());
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.timeout().orElseThrow());
        assertEquals(StandardCharsets.UTF_8, invocation.charset().orElseThrow());
        assertEquals(OutputMode.MERGED, invocation.outputMode().orElseThrow());
    }

    @Test
    void comparesByValue() {
        CommandInvocation left = CommandInvocation.builder()
                .args("status")
                .putEnvironment("LC_ALL", "C")
                .capture(CapturePolicy.bounded(1024))
                .build();
        CommandInvocation right = CommandInvocation.builder()
                .args("status")
                .putEnvironment("LC_ALL", "C")
                .capture(CapturePolicy.bounded(1024))
                .build();

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void rejectsInvalidEnvironmentName() {
        CommandInvocation.Builder builder = CommandInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("", "value"));
    }

    @Test
    void rejectsNegativeTimeout() {
        CommandInvocation.Builder builder = CommandInvocation.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.timeout(Duration.ofMillis(-1)));
    }
}
