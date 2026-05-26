package io.github.ulviar.icli.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.icli.command.CharsetPolicy;
import io.github.ulviar.icli.command.EnvironmentPolicy;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ProtocolSessionInvocationTest {

    @Test
    void builderCapturesImmutableArgumentsAndOverrides() {
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add("worker");
        ShutdownPolicy shutdownPolicy = ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofSeconds(1));
        CharsetPolicy charsetPolicy = CharsetPolicy.report(StandardCharsets.UTF_16LE);
        Consumer<ProtocolSession<String, String>> readinessProbe = session -> {};

        ProtocolSessionInvocation<String, String> invocation = ProtocolSessionInvocation.<String, String>builder()
                .args(arguments)
                .arg("--mode=test")
                .workingDirectory(Path.of("work"))
                .putEnvironment("MODE", "test")
                .cleanEnvironment()
                .shutdown(shutdownPolicy)
                .idleTimeout(Duration.ofSeconds(3))
                .terminal(TerminalPolicy.REQUIRED)
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
                .build();

        arguments.add("--ignored");

        assertEquals(java.util.List.of("worker", "--mode=test"), invocation.arguments());
        assertEquals(Path.of("work"), invocation.workingDirectory().orElseThrow());
        assertEquals("test", invocation.environment().get("MODE"));
        assertEquals(EnvironmentPolicy.CLEAN, invocation.environmentPolicy().orElseThrow());
        assertEquals(shutdownPolicy, invocation.shutdownPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(3), invocation.idleTimeout().orElseThrow());
        assertEquals(TerminalPolicy.REQUIRED, invocation.terminalPolicy().orElseThrow());
        assertEquals(Duration.ofSeconds(4), invocation.options().requestTimeout());
        assertEquals(128, invocation.options().transcriptLimit());
        assertEquals(256, invocation.options().outputBacklogLimit());
        assertEquals(512, invocation.options().maxRequestBytes());
        assertEquals(32, invocation.options().maxRequestChars());
        assertEquals(1024, invocation.options().maxResponseBytes());
        assertEquals(64, invocation.options().maxResponseChars());
        assertEquals(charsetPolicy, invocation.options().charsetPolicy());
        assertSame(readinessProbe, invocation.readinessProbe().orElseThrow());
        assertEquals(Duration.ofSeconds(5), invocation.readinessTimeout().orElseThrow());
    }

    @Test
    void builderStartsFromProvidedDefaults() {
        CharsetPolicy charsetPolicy = CharsetPolicy.report(StandardCharsets.UTF_8);
        ProtocolSessionOptions defaults = ProtocolSessionOptions.defaults()
                .withRequestTimeout(Duration.ofSeconds(2))
                .withTranscriptLimit(100)
                .withOutputBacklogLimit(200)
                .withMaxRequestBytes(300)
                .withMaxRequestChars(30)
                .withMaxResponseBytes(400)
                .withMaxResponseChars(40)
                .withCharsetPolicy(charsetPolicy);

        ProtocolSessionInvocation<String, String> invocation =
                ProtocolSessionInvocation.<String, String>builder(defaults).build();

        assertEquals(defaults, invocation.options());
        assertEquals(charsetPolicy, invocation.options().charsetPolicy());
    }

    @Test
    void builderRejectsNullAndInvalidInputs() {
        ProtocolSessionInvocation.Builder<String, String> builder = ProtocolSessionInvocation.builder();

        assertThrows(NullPointerException.class, () -> ProtocolSessionInvocation.builder(null));
        assertThrows(NullPointerException.class, () -> builder.arg(null));
        assertThrows(NullPointerException.class, () -> builder.args((String[]) null));
        assertThrows(NullPointerException.class, () -> builder.args((java.util.Collection<String>) null));
        assertThrows(NullPointerException.class, () -> builder.workingDirectory(null));
        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("", "value"));
        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("BAD=NAME", "value"));
        assertThrows(IllegalArgumentException.class, () -> builder.putEnvironment("NAME", "bad\0value"));
        assertThrows(NullPointerException.class, () -> builder.putEnvironment("NAME", null));
        assertThrows(NullPointerException.class, () -> builder.shutdown(null));
        assertThrows(NullPointerException.class, () -> builder.idleTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.idleTimeout(Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> builder.terminal(null));
        assertThrows(NullPointerException.class, () -> builder.requestTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.transcriptLimit(0));
        assertThrows(IllegalArgumentException.class, () -> builder.outputBacklogLimit(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxRequestBytes(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxRequestChars(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxResponseBytes(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxResponseChars(0));
        assertThrows(NullPointerException.class, () -> builder.charsetPolicy(null));
        assertThrows(NullPointerException.class, () -> builder.readiness(null));
        assertThrows(NullPointerException.class, () -> builder.readinessTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> builder.readinessTimeout(Duration.ZERO));
    }
}
