package io.github.ulviar.icli.comparison;

import java.time.Duration;

record ComparisonOptions(int iterations, int largeBytes, int timeoutParallelism, Duration timeout) {

    static ComparisonOptions fromSystemProperties() {
        return new ComparisonOptions(
                intProperty("icli.comparison.iterations", 12),
                intProperty("icli.comparison.largeBytes", 4 * 1024 * 1024),
                intProperty("icli.comparison.timeoutParallelism", 8),
                Duration.ofMillis(intProperty("icli.comparison.timeoutMillis", 80)));
    }

    private static int intProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }
}
