package com.github.ulviar.icli.testing.fixture;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Deterministic result returned by {@link ProcessFixture}.
 */
public record FixtureRunResult(OptionalInt exitCode, String stdout, String stderr, boolean timedOut, Duration elapsed) {

    public FixtureRunResult {
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
