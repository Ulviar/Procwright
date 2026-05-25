package io.github.ulviar.icli.comparison;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;

record CommandOutcome(
        OutcomeStatus status,
        OptionalInt exitCode,
        boolean timedOut,
        byte[] stdout,
        boolean stdoutTruncated,
        byte[] stderr,
        boolean stderrTruncated,
        Duration elapsed,
        String note) {

    static CommandOutcome unsupported(String note) {
        return new CommandOutcome(
                OutcomeStatus.UNSUPPORTED,
                OptionalInt.empty(),
                false,
                new byte[0],
                false,
                new byte[0],
                false,
                Duration.ZERO,
                note);
    }

    String stdoutText() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    String stderrText() {
        return new String(stderr, StandardCharsets.UTF_8);
    }
}
