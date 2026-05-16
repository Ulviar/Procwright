package com.github.ulviar.icli.testing.fixture;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Deterministic process model used by tests before the real process kernel exists.
 */
public final class ProcessFixture {

    private final String stdout;
    private final String stderr;
    private final int exitCode;
    private final boolean hangs;

    private ProcessFixture(Builder builder) {
        stdout = builder.stdout;
        stderr = builder.stderr;
        exitCode = builder.exitCode;
        hangs = builder.hangs;
    }

    public static Builder singleRun() {
        return new Builder();
    }

    public FixtureRunResult run(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (hangs) {
            return new FixtureRunResult(OptionalInt.empty(), stdout, stderr, true, timeout);
        }
        return new FixtureRunResult(OptionalInt.of(exitCode), stdout, stderr, false, Duration.ZERO);
    }

    /**
     * Mutable builder for a single deterministic fixture run.
     */
    public static final class Builder {

        private String stdout = "";
        private String stderr = "";
        private int exitCode;
        private boolean hangs;

        private Builder() {}

        public Builder stdout(String stdout) {
            this.stdout = Objects.requireNonNull(stdout, "stdout");
            return this;
        }

        public Builder stderr(String stderr) {
            this.stderr = Objects.requireNonNull(stderr, "stderr");
            return this;
        }

        public Builder stdoutRepeated(char character, int count) {
            stdout = repeated(character, count);
            return this;
        }

        public Builder stderrRepeated(char character, int count) {
            stderr = repeated(character, count);
            return this;
        }

        public Builder exitCode(int exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        public Builder hangs() {
            hangs = true;
            return this;
        }

        public ProcessFixture build() {
            return new ProcessFixture(this);
        }

        private static String repeated(char character, int count) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            return String.valueOf(character).repeat(count);
        }
    }
}
