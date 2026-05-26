package io.github.ulviar.icli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.icli.command.CharsetPolicy;
import io.github.ulviar.icli.command.EnvironmentPolicy;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class PooledProtocolSessionInvocationTest {

    @Test
    void builderCapturesProtocolAndPoolOverrides() {
        CharsetPolicy charsetPolicy = CharsetPolicy.report(StandardCharsets.UTF_16LE);
        Consumer<ProtocolSession<String, String>> readinessProbe = session -> {};
        Consumer<ProtocolSession<String, String>> resetHook = session -> {};
        Predicate<ProtocolSession<String, String>> healthCheck = session -> true;

        PooledProtocolSessionInvocation<String, String> invocation =
                PooledProtocolSessionInvocation.<String, String>builder(
                                ProtocolSessionOptions.defaults(), PooledProtocolSessionOptions.defaults())
                        .args("worker")
                        .workingDirectory(Path.of("work"))
                        .putEnvironment("MODE", "test")
                        .cleanEnvironment()
                        .idleTimeout(Duration.ofSeconds(3))
                        .terminal(TerminalPolicy.DISABLED)
                        .requestTimeout(Duration.ofSeconds(4))
                        .transcriptLimit(128)
                        .outputBacklogLimit(256)
                        .maxRequestBytes(512)
                        .maxRequestChars(32)
                        .maxResponseBytes(1024)
                        .maxResponseChars(64)
                        .charsetPolicy(charsetPolicy)
                        .readiness(readinessProbe)
                        .readinessTimeout(Duration.ofSeconds(5))
                        .maxSize(3)
                        .warmupSize(2)
                        .minIdle(1)
                        .acquireTimeout(Duration.ofMillis(250))
                        .hookTimeout(Duration.ofMillis(125))
                        .maxRequestsPerWorker(7)
                        .maxWorkerAge(Duration.ofMinutes(5))
                        .backgroundReplenishment(false)
                        .reset(resetHook)
                        .healthCheck(healthCheck)
                        .build();

        ProtocolSessionInvocation<String, String> worker = invocation.protocolSessionInvocation();
        assertEquals(java.util.List.of("worker"), worker.arguments());
        assertEquals(Path.of("work"), worker.workingDirectory().orElseThrow());
        assertEquals("test", worker.environment().get("MODE"));
        assertEquals(EnvironmentPolicy.CLEAN, worker.environmentPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), worker.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.DISABLED, worker.terminalPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(4), worker.options().requestTimeout());
        assertEquals(128, worker.options().transcriptLimit());
        assertEquals(256, worker.options().outputBacklogLimit());
        assertEquals(512, worker.options().maxRequestBytes());
        assertEquals(32, worker.options().maxRequestChars());
        assertEquals(1024, worker.options().maxResponseBytes());
        assertEquals(64, worker.options().maxResponseChars());
        assertEquals(charsetPolicy, worker.options().charsetPolicy());
        assertSame(readinessProbe, worker.readinessProbe().orElseThrow());
        assertEquals(Duration.ofSeconds(5), worker.readinessTimeout().orElseThrow());
        assertEquals(3, invocation.options().maxSize());
        assertEquals(2, invocation.options().warmupSize());
        assertEquals(1, invocation.options().minIdle());
        assertEquals(Duration.ofMillis(250), invocation.options().acquireTimeout());
        assertEquals(Duration.ofMillis(125), invocation.options().hookTimeout());
        assertEquals(7, invocation.options().maxRequestsPerWorker());
        assertEquals(Duration.ofMinutes(5), invocation.options().maxWorkerAge());
        assertEquals(false, invocation.options().backgroundReplenishment());
        assertSame(resetHook, invocation.resetHook());
        assertSame(healthCheck, invocation.healthCheck());
    }

    @Test
    void builderRejectsNullAndInvalidInputs() {
        PooledProtocolSessionInvocation.Builder<String, String> builder =
                PooledProtocolSessionInvocation.builder(ProtocolSessionOptions.defaults());

        assertThrows(NullPointerException.class, () -> PooledProtocolSessionInvocation.builder(null));
        assertThrows(
                NullPointerException.class,
                () -> PooledProtocolSessionInvocation.builder(ProtocolSessionOptions.defaults(), null));
        assertThrows(NullPointerException.class, () -> builder.reset(null));
        assertThrows(NullPointerException.class, () -> builder.healthCheck(null));
        assertThrows(NullPointerException.class, () -> builder.arg(null));
        assertThrows(IllegalArgumentException.class, () -> builder.maxSize(0));
        assertThrows(IllegalArgumentException.class, () -> builder.warmupSize(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.minIdle(-1));
        assertThrows(NullPointerException.class, () -> builder.acquireTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.acquireTimeout(Duration.ZERO));
        assertThrows(NullPointerException.class, () -> builder.hookTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.hookTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.maxRequestsPerWorker(0));
        assertThrows(NullPointerException.class, () -> builder.maxWorkerAge(null));
        assertThrows(IllegalArgumentException.class, () -> builder.maxWorkerAge(Duration.ofMillis(-1)));
    }

    @Test
    void builderValidatesWarmupAndMinIdleAgainstFinalMaxSizeAtBuildTime() {
        PooledProtocolSessionInvocation<String, String> invocation =
                PooledProtocolSessionInvocation.<String, String>builder(ProtocolSessionOptions.defaults())
                        .warmupSize(2)
                        .minIdle(1)
                        .maxSize(3)
                        .build();

        assertEquals(2, invocation.options().warmupSize());
        assertEquals(1, invocation.options().minIdle());
        assertEquals(3, invocation.options().maxSize());
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionInvocation.<String, String>builder(
                        ProtocolSessionOptions.defaults())
                .warmupSize(2)
                .maxSize(1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> PooledProtocolSessionInvocation.<String, String>builder(
                        ProtocolSessionOptions.defaults())
                .minIdle(2)
                .maxSize(1)
                .build());
    }
}
