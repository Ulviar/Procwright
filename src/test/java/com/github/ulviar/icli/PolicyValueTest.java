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
}
