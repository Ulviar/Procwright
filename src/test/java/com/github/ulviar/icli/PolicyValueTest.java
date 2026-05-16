package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class PolicyValueTest {

    @Test
    void boundedCaptureRequiresPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> CapturePolicy.bounded(0));
        assertThrows(IllegalArgumentException.class, () -> CapturePolicy.bounded(-1));
    }

    @Test
    void boundedCaptureExposesByteLimit() {
        CapturePolicy.Bounded bounded = CapturePolicy.bounded(512);

        assertEquals(512, bounded.byteLimit());
    }

    @Test
    void shutdownPolicyRejectsNegativeGracePeriods() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ShutdownPolicy.interruptThenKill(Duration.ofMillis(-1), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ofMillis(-1)));
    }

    @Test
    void runOptionsRejectNegativeTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunOptions(
                        CapturePolicy.bounded(512),
                        ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                        Duration.ofMillis(-1),
                        java.nio.charset.StandardCharsets.UTF_8,
                        OutputMode.SEPARATE));
    }

    @Test
    void lineSessionOptionsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withTranscriptLimit(0));
        assertThrows(IllegalArgumentException.class, () -> LineSessionOptions.defaults()
                .withStdoutBacklogLimit(0));
    }

    @Test
    void expectOptionsRejectInvalidTimeoutAndTranscriptLimit() {
        assertThrows(
                IllegalArgumentException.class, () -> ExpectOptions.defaults().withTimeout(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectOptions.defaults().withTranscriptLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> ExpectOptions.defaults().withMatchBufferLimit(0));
    }

    @Test
    void terminalSizeRejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 0));
    }

    @Test
    void sessionOptionsCarryTerminalDefaults() {
        PtyProvider provider = PtyProvider.unavailable("no test provider");
        SessionOptions options = SessionOptions.defaults()
                .withTerminalPolicy(TerminalPolicy.REQUIRED)
                .withPtyProvider(provider)
                .withTerminalSize(new TerminalSize(100, 40));

        assertEquals(TerminalPolicy.REQUIRED, options.terminalPolicy());
        assertEquals(provider, options.ptyProvider());
        assertEquals(new TerminalSize(100, 40), options.terminalSize());
    }

    @Test
    void streamOptionsRejectInvalidDiagnosticLimitAndNegativeTimeout() {
        assertThrows(
                IllegalArgumentException.class, () -> StreamOptions.defaults().withDiagnosticLimit(0));
        assertThrows(
                IllegalArgumentException.class, () -> StreamOptions.defaults().withTimeout(Duration.ofMillis(-1)));
    }

    @Test
    void pooledLineSessionOptionsRejectInvalidPoolPolicies() {
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withWarmupSize(-1));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withWarmupSize(2));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withAcquireTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMaxRequestsPerWorker(0));
        assertThrows(IllegalArgumentException.class, () -> PooledLineSessionOptions.defaults()
                .withMaxWorkerAge(Duration.ofMillis(-1)));
    }
}
