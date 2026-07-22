/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public record CapturedOutput(byte[] bytes, boolean truncated) {

    static final int INITIAL_CHUNK_BYTES = 1024;
    private static final int READ_BUFFER_BYTES = 8192;
    private static final byte[] EMPTY_BYTES = new byte[0];

    static CapturedOutput empty() {
        return new CapturedOutput(EMPTY_BYTES, false);
    }

    static CapturedOutput capture(InputStream input, CapturePolicy.Bounded policy) throws IOException {
        return capture(input, Objects.requireNonNull(policy, "policy").byteLimit(), AllocationProbe.NONE);
    }

    static CapturedOutput capture(InputStream input, CapturePolicy.Bounded policy, AllocationProbe allocations)
            throws IOException {
        return capture(input, Objects.requireNonNull(policy, "policy").byteLimit(), allocations);
    }

    static CapturedOutput capture(InputStream input, int limit, AllocationProbe allocations) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(allocations, "allocations");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }

        RetainedByteAccumulator retained = new RetainedByteAccumulator(limit, allocations);
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        boolean truncated = false;

        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                int appended = retained.append(buffer, 0, read);
                if (appended < read) {
                    truncated = true;
                }
            }
        } catch (IOException exception) {
            throw new PartialCaptureException(new CapturedOutput(retained.flatten(), truncated), exception);
        }

        return new CapturedOutput(retained.flatten(), truncated);
    }

    interface AllocationProbe {

        AllocationProbe NONE = new AllocationProbe() {};

        default void accumulatorCreated(int limit) {}

        default void chunkAllocated(int capacity) {}

        default void flattened(int retainedBytes, int chunkCount, boolean resultAllocated) {}
    }

    @SuppressWarnings("serial")
    static final class PartialCaptureException extends IOException {

        private final CapturedOutput output;

        private PartialCaptureException(CapturedOutput output, IOException cause) {
            super(cause);
            this.output = output;
        }

        CapturedOutput output() {
            return output;
        }
    }
}
