package com.github.ulviar.icli;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;

record ScenarioProfile(
        String name,
        CommandInput stdin,
        CapturePolicy.Bounded capturePolicy,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        Charset charset,
        OutputMode outputMode,
        TerminalPolicy terminalPolicy) {

    ScenarioProfile {
        CommandSpec.requireText(name, "name");
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

    static ScenarioProfile run(RunOptions options) {
        Objects.requireNonNull(options, "options");
        return new ScenarioProfile(
                "run",
                CommandInput.closed(),
                bounded(options.capturePolicy()),
                options.shutdownPolicy(),
                options.timeout(),
                options.charset(),
                options.outputMode(),
                TerminalPolicy.DISABLED);
    }

    private static CapturePolicy.Bounded bounded(CapturePolicy capturePolicy) {
        if (capturePolicy instanceof CapturePolicy.Bounded bounded) {
            return bounded;
        }
        throw new IllegalArgumentException("run scenario currently requires bounded capture");
    }
}
