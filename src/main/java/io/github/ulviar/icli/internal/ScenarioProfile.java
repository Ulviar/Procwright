package io.github.ulviar.icli.internal;

import io.github.ulviar.icli.command.CapturePolicy;
import io.github.ulviar.icli.command.CharsetPolicy;
import io.github.ulviar.icli.command.OutputMode;
import io.github.ulviar.icli.command.RunOptions;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.session.SessionOptions;
import io.github.ulviar.icli.session.StreamOptions;
import io.github.ulviar.icli.terminal.PtyProvider;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import io.github.ulviar.icli.terminal.TerminalSize;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;

public sealed interface ScenarioProfile
        permits ScenarioProfile.Interactive, ScenarioProfile.Run, ScenarioProfile.Stream {

    String name();

    TerminalPolicy terminalPolicy();

    static Run run(RunOptions options) {
        Objects.requireNonNull(options, "options");
        return new Run(
                StdinPolicy.closed(),
                bounded(options.capturePolicy()),
                options.shutdownPolicy(),
                options.timeout(),
                options.charsetPolicy(),
                options.outputMode(),
                TerminalPolicy.DISABLED);
    }

    static Interactive interactive(SessionOptions options) {
        Objects.requireNonNull(options, "options");
        return new Interactive(
                options.shutdownPolicy(),
                options.idleTimeout(),
                options.charset(),
                options.terminalPolicy(),
                options.ptyProvider(),
                options.terminalSize());
    }

    static Stream stream(StreamOptions options) {
        Objects.requireNonNull(options, "options");
        return new Stream(
                options.shutdownPolicy(),
                options.timeout(),
                options.charset(),
                options.diagnosticLimit(),
                TerminalPolicy.DISABLED);
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
            CharsetPolicy charsetPolicy,
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
            Objects.requireNonNull(charsetPolicy, "charsetPolicy");
            Objects.requireNonNull(outputMode, "outputMode");
            Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        }

        public Charset charset() {
            return charsetPolicy.charset();
        }

        @Override
        public String name() {
            return "run";
        }
    }

    record Interactive(
            ShutdownPolicy shutdownPolicy,
            Duration idleTimeout,
            Charset charset,
            TerminalPolicy terminalPolicy,
            PtyProvider ptyProvider,
            TerminalSize terminalSize)
            implements ScenarioProfile {

        public Interactive {
            Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            Objects.requireNonNull(idleTimeout, "idleTimeout");
            if (idleTimeout.isNegative()) {
                throw new IllegalArgumentException("idleTimeout must not be negative");
            }
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(terminalPolicy, "terminalPolicy");
            Objects.requireNonNull(ptyProvider, "ptyProvider");
            Objects.requireNonNull(terminalSize, "terminalSize");
        }

        @Override
        public String name() {
            return "interactive";
        }
    }

    record Stream(
            ShutdownPolicy shutdownPolicy,
            Duration timeout,
            Charset charset,
            int diagnosticLimit,
            TerminalPolicy terminalPolicy)
            implements ScenarioProfile {

        public Stream {
            Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must not be negative");
            }
            Objects.requireNonNull(charset, "charset");
            if (diagnosticLimit <= 0) {
                throw new IllegalArgumentException("diagnosticLimit must be positive");
            }
            Objects.requireNonNull(terminalPolicy, "terminalPolicy");
        }

        @Override
        public String name() {
            return "stream";
        }
    }
}
