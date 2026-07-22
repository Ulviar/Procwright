/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;

/** Retains a bounded byte prefix without copying earlier bytes when storage grows. */
final class RetainedByteAccumulator {

    private static final int MAX_CHUNK_BYTES = 8192;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final int limit;
    private final CapturedOutput.AllocationProbe allocations;
    private Chunk first;
    private Chunk last;
    private int retainedBytes;
    private int chunkCount;
    private boolean flattened;

    RetainedByteAccumulator(int limit, CapturedOutput.AllocationProbe allocations) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        this.limit = limit;
        this.allocations = Objects.requireNonNull(allocations, "allocations");
        allocations.accumulatorCreated(limit);
    }

    int append(byte[] source, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, source.length);
        if (flattened) {
            throw new IllegalStateException("capture has already been flattened");
        }

        int appendBytes = Math.min(length, limit - retainedBytes);
        int remaining = appendBytes;
        int sourceOffset = offset;
        while (remaining > 0) {
            if (last == null || last.full()) {
                allocateChunk(remaining);
            }
            int copied = last.append(source, sourceOffset, remaining);
            sourceOffset += copied;
            remaining -= copied;
            retainedBytes += copied;
        }
        return appendBytes;
    }

    byte[] flatten() {
        if (flattened) {
            throw new IllegalStateException("capture has already been flattened");
        }
        flattened = true;

        if (retainedBytes == 0) {
            allocations.flattened(0, chunkCount, false);
            return EMPTY_BYTES;
        }

        byte[] result = new byte[retainedBytes];
        int offset = 0;
        for (Chunk chunk = first; chunk != null; chunk = chunk.next) {
            System.arraycopy(chunk.bytes, 0, result, offset, chunk.used);
            offset += chunk.used;
        }
        if (offset != retainedBytes) {
            throw new AssertionError("retained chunk size does not match the capture size");
        }
        allocations.flattened(retainedBytes, chunkCount, true);
        return result;
    }

    private void allocateChunk(int requestedBytes) {
        int remainingCapacity = limit - retainedBytes;
        if (remainingCapacity <= 0) {
            throw new AssertionError("capture chunk requested after reaching the byte limit");
        }
        int usefulCapacity = first == null
                ? CapturedOutput.INITIAL_CHUNK_BYTES
                : Math.max(CapturedOutput.INITIAL_CHUNK_BYTES, Math.min(requestedBytes, MAX_CHUNK_BYTES));
        int capacity = Math.min(usefulCapacity, remainingCapacity);
        Chunk chunk = new Chunk(new byte[capacity]);
        allocations.chunkAllocated(capacity);
        if (last == null) {
            first = chunk;
        } else {
            last.next = chunk;
        }
        last = chunk;
        chunkCount++;
    }

    private static final class Chunk {

        private final byte[] bytes;
        private int used;
        private Chunk next;

        private Chunk(byte[] bytes) {
            this.bytes = bytes;
        }

        private boolean full() {
            return used == bytes.length;
        }

        private int append(byte[] source, int offset, int length) {
            int copied = Math.min(length, bytes.length - used);
            System.arraycopy(source, offset, bytes, used, copied);
            used += copied;
            return copied;
        }
    }
}
