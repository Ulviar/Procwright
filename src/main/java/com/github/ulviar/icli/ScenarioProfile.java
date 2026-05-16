package com.github.ulviar.icli;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;

sealed interface ScenarioProfile permits ScenarioProfile.Interactive, ScenarioProfile.Run {

    String name();

    TerminalPolicy terminalPolicy();

    static Run run(RunOptions options) {
        Objects.requireNonNull(options, "options");
        return new Run(
                StdinPolicy.closed(),
                bounded(options.capturePolicy()),
                options.shutdownPolicy(),
                options.timeout(),
                options.charset(),
                options.outputMode(),
                TerminalPolicy.DISABLED);
    }

    static Interactive interactive(SessionOptions options) {
        Objects.requireNonNull(options, "options");
        return new Interactive(
                options.shutdownPolicy(), options.idleTimeout(), options.charset(), TerminalPolicy.DISABLED);
    }

    private static CapturePolicy.Bounded bounded(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Bounded bounded) {
            return bounded;
        }
        throw new IllegalArgumentException("run scenario currently requires bounded capture");
    }

    record Run(
            StdinPolicy stdin,
            CapturePolicy.Bounded capturePolicy,
            ShutdownPolicy shutdownPolicy,
            Duration timeout,
            Charset charset,
            OutputMode outputMode,
            TerminalPolicy terminalPolicy)
            implements ScenarioProfile {

        public Run {
            Objects.requireNonNull(stdin, "stdin");
            Objects.requireNonNull(capturePolicy, "capturePolicy");
            Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(outputMode, "outputMode");
            Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        }

        @Override
        public String name() {
            return "run";
        }
    }

    record Interactive(
            ShutdownPolicy shutdownPolicy, Duration idleTimeout, Charset charset, TerminalPolicy terminalPolicy)
            implements ScenarioProfile {

        public Interactive {
            Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            Objects.requireNonNull(idleTimeout, "idleTimeout");
            if (idleTimeout.isNegative()) {
                throw new IllegalArgumentException("idleTimeout must not be negative");
            }
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        }

        @Override
        public String name() {
            return "interactive";
        }
    }
}
