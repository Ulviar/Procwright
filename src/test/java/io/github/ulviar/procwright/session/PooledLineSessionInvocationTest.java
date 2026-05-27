package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class PooledLineSessionInvocationTest {

    @Test
    void builderCapturesLineSessionAndPoolOverrides() {
        PooledLineSessionInvocation invocation = PooledLineSessionInvocation.builder()
                .args("repl")
                .workingDirectory(Path.of("work"))
                .putEnvironment("MODE", "test")
                .idleTimeout(Duration.ofSeconds(3))
                .terminal(TerminalPolicy.DISABLED)
                .maxSize(3)
                .warmupSize(2)
                .minIdle(1)
                .acquireTimeout(Duration.ofMillis(250))
                .hookTimeout(Duration.ofMillis(125))
                .maxRequestsPerWorker(7)
                .maxWorkerAge(Duration.ofMinutes(5))
                .backgroundReplenishment(false)
                .build();

        assertEquals(
                java.util.List.of("repl"), invocation.lineSessionInvocation().arguments());
        assertEquals(
                Path.of("work"),
                invocation.lineSessionInvocation().workingDirectory().orElseThrow());
        assertEquals("test", invocation.lineSessionInvocation().environment().get("MODE"));
        assertEquals(
                Duration.ofSeconds(3),
                invocation.lineSessionInvocation().idleTimeout().orElseThrow());
        assertEquals(
                TerminalPolicy.DISABLED,
                invocation.lineSessionInvocation().terminalPolicy().orElseThrow());
        assertEquals(3, invocation.options().maxSize());
        assertEquals(2, invocation.options().warmupSize());
        assertEquals(1, invocation.options().minIdle());
        assertEquals(Duration.ofMillis(250), invocation.options().acquireTimeout());
        assertEquals(Duration.ofMillis(125), invocation.options().hookTimeout());
        assertEquals(7, invocation.options().maxRequestsPerWorker());
        assertEquals(Duration.ofMinutes(5), invocation.options().maxWorkerAge());
        assertEquals(false, invocation.options().backgroundReplenishment());
    }

    @Test
    void builderRejectsInvalidCallbacksAndArguments() {
        PooledLineSessionInvocation.Builder builder = PooledLineSessionInvocation.builder();

        assertThrows(NullPointerException.class, () -> builder.reset(null));
        assertThrows(NullPointerException.class, () -> builder.healthCheck(null));
        assertThrows(NullPointerException.class, () -> builder.arg(null));
    }

    @Test
    void builderValidatesWarmupAgainstFinalMaxSizeAtBuildTime() {
        PooledLineSessionInvocation invocation =
                PooledLineSessionInvocation.builder().warmupSize(2).maxSize(3).build();

        assertEquals(2, invocation.options().warmupSize());
        assertEquals(3, invocation.options().maxSize());
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionInvocation.builder()
                .warmupSize(2)
                .maxSize(1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionInvocation.builder()
                .minIdle(2)
                .maxSize(1)
                .build());
    }
}
