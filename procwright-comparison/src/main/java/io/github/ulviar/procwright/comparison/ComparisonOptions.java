package io.github.ulviar.procwright.comparison;

import java.time.Duration;

record ComparisonOptions(int iterations, int largeBytes, int timeoutParallelism, Duration timeout) {

    static ComparisonOptions fromSystemProperties() {
        return new ComparisonOptions(
                intProperty("procwright.comparison.iterations", 12),
                intProperty("procwright.comparison.largeBytes", 4 * 1024 * 1024),
                intProperty("procwright.comparison.timeoutParallelism", 8),
                Duration.ofMillis(intProperty("procwright.comparison.timeoutMillis", 80)));
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
