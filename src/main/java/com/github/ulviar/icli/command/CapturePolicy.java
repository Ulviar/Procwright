package com.github.ulviar.icli.command;

/**
 * Defines how command output is captured for diagnostics and results.
 */
public sealed interface CapturePolicy permits CapturePolicy.Bounded {

    /**
     * Captures output up to a fixed byte limit.
     *
     * @param byteLimit maximum number of bytes retained per stream
     * @return a bounded capture policy
     */
    static Bounded bounded(int byteLimit) {
        return new Bounded(byteLimit);
    }

    /**
     * Capture policy that retains at most {@code byteLimit} bytes.
     *
     * @param byteLimit maximum number of bytes retained per stream
     */
    record Bounded(int byteLimit) implements CapturePolicy {

        /**
         * Creates a bounded capture policy.
         *
         * @param byteLimit maximum number of bytes retained per stream
         */
        public Bounded {
            if (byteLimit <= 0) {
                throw new IllegalArgumentException("byteLimit must be positive");
            }
        }
    }
}
