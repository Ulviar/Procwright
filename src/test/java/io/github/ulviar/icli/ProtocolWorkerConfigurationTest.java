package io.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.icli.command.CharsetPolicy;
import io.github.ulviar.icli.command.EnvironmentPolicy;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.session.PooledProtocolSessionInvocation;
import io.github.ulviar.icli.session.PooledProtocolSessionOptions;
import io.github.ulviar.icli.session.ProtocolSession;
import io.github.ulviar.icli.session.ProtocolSessionInvocation;
import io.github.ulviar.icli.session.ProtocolSessionOptions;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ProtocolWorkerConfigurationTest {

    @Test
    void appliesSameWorkerConfigurationToSingleAndPooledProtocolBuilders() {
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        CharsetPolicy charsetPolicy = CharsetPolicy.report(StandardCharsets.UTF_16LE);
        Consumer<ProtocolSession<String, String>> readinessProbe = session -> {};
        ProtocolWorkerConfiguration<String, String> configuration = ProtocolWorkerConfiguration.<String, String>empty()
                .andThen(worker -> {
                    worker.arg("first");
                    worker.args("second", "third");
                    worker.args(List.of("fourth"));
                    worker.workingDirectory(Path.of("work"));
                    worker.putEnvironment("MODE", "test");
                    worker.inheritEnvironment();
                    worker.cleanEnvironment();
                    worker.shutdown(shutdownPolicy);
                    worker.idleTimeout(Duration.ofSeconds(3));
                    worker.terminal(TerminalPolicy.REQUIRED);
                    worker.readiness(readinessProbe);
                    worker.readinessTimeout(Duration.ofSeconds(4));
                    worker.requestTimeout(Duration.ofSeconds(5));
                    worker.transcriptLimit(128);
                    worker.outputBacklogLimit(256);
                    worker.maxRequestBytes(512);
                    worker.maxRequestChars(32);
                    worker.maxResponseBytes(1024);
                    worker.maxResponseChars(64);
                    worker.charsetPolicy(charsetPolicy);
                });

        ProtocolSessionInvocation.Builder<String, String> singleBuilder =
                ProtocolSessionInvocation.builder(ProtocolSessionOptions.defaults());
        configuration.forSingleWorker().accept(singleBuilder);
        ProtocolSessionInvocation<String, String> singleInvocation = singleBuilder.build();

        PooledProtocolSessionInvocation.Builder<String, String> pooledBuilder = PooledProtocolSessionInvocation.builder(
                ProtocolSessionOptions.defaults(), PooledProtocolSessionOptions.defaults());
        configuration.forPooledWorker().accept(pooledBuilder);
        ProtocolSessionInvocation<String, String> pooledInvocation =
                pooledBuilder.build().protocolSessionInvocation();

        assertConfigured(singleInvocation, shutdownPolicy, charsetPolicy, readinessProbe);
        assertConfigured(pooledInvocation, shutdownPolicy, charsetPolicy, readinessProbe);
    }

    @Test
    void rejectsNullContinuation() {
        ProtocolWorkerConfiguration<String, String> configuration = ProtocolWorkerConfiguration.empty();

        assertThrows(NullPointerException.class, () -> configuration.andThen(null));
    }

    private static void assertConfigured(
            ProtocolSessionInvocation<String, String> invocation,
            ShutdownPolicy shutdownPolicy,
            CharsetPolicy charsetPolicy,
            Consumer<ProtocolSession<String, String>> readinessProbe) {
        assertEquals(List.of("first", "second", "third", "fourth"), invocation.arguments());
        assertEquals(Path.of("work"), invocation.workingDirectory().orElseThrow());
        assertEquals("test", invocation.environment().get("MODE"));
        assertEquals(EnvironmentPolicy.CLEAN, invocation.environmentPolicy().orElseThrow());
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
        assertSame(readinessProbe, invocation.readinessProbe().orElseThrow());
        assertEquals(Duration.ofSeconds(4), invocation.readinessTimeout().orElseThrow());
        assertEquals(Duration.ofSeconds(5), invocation.options().requestTimeout());
        assertEquals(128, invocation.options().transcriptLimit());
        assertEquals(256, invocation.options().outputBacklogLimit());
        assertEquals(512, invocation.options().maxRequestBytes());
        assertEquals(32, invocation.options().maxRequestChars());
        assertEquals(1024, invocation.options().maxResponseBytes());
        assertEquals(64, invocation.options().maxResponseChars());
        assertEquals(charsetPolicy, invocation.options().charsetPolicy());
    }
}
