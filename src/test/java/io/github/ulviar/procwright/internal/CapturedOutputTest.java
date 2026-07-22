/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class CapturedOutputTest {

    @Test
    void boundedCaptureDrainsInputButRetainsOnlyConfiguredBytes() throws IOException {
        CountingRepeatingInputStream input = new CountingRepeatingInputStream(1024 * 1024, (byte) 'x');

        CapturedOutput output = CapturedOutput.capture(input, CapturePolicy.bounded(32));

        assertEquals(32, output.bytes().length);
        assertTrue(output.truncated());
        assertEquals(1024 * 1024, input.bytesRead());
    }

    @Test
    void smallCaptureAllocatesOneSmallChunkInsteadOfTheConfiguredLimit() throws IOException {
        AllocationCounter allocations = new AllocationCounter();

        CapturedOutput output = CapturedOutput.capture(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)),
                CapturePolicy.bounded(1024 * 1024),
                allocations);

        assertEquals("abc", new String(output.bytes(), StandardCharsets.US_ASCII));
        assertFalse(output.truncated());
        assertEquals(1, allocations.accumulators());
        assertEquals(List.of(CapturedOutput.INITIAL_CHUNK_BYTES), allocations.chunkSizes());
        assertTrue(allocations.chunkBytes() < 1024 * 1024);
        allocations.assertSingleFlatten(3, 1, true);
    }

    @Test
    void exactInitialChunkDoesNotGrowBeforeFlattening() throws IOException {
        AllocationCounter allocations = new AllocationCounter();
        byte[] bytes = repeated(CapturedOutput.INITIAL_CHUNK_BYTES, (byte) 'x');

        CapturedOutput output = CapturedOutput.capture(
                new ByteArrayInputStream(bytes), CapturePolicy.bounded(bytes.length), allocations);

        assertEquals(bytes.length, output.bytes().length);
        assertFalse(output.truncated());
        assertEquals(List.of(CapturedOutput.INITIAL_CHUNK_BYTES), allocations.chunkSizes());
        allocations.assertSingleFlatten(bytes.length, 1, true);
    }

    @Test
    void growthAddsChunksWithoutCopyingPreviouslyRetainedChunks() throws IOException {
        AllocationCounter allocations = new AllocationCounter();
        int retainedBytes = CapturedOutput.INITIAL_CHUNK_BYTES + 1;
        byte[] bytes = repeated(retainedBytes, (byte) 'g');

        CapturedOutput output = CapturedOutput.capture(
                new ByteArrayInputStream(bytes), CapturePolicy.bounded(retainedBytes * 4), allocations);

        assertEquals(bytes.length, output.bytes().length);
        assertEquals(
                List.of(CapturedOutput.INITIAL_CHUNK_BYTES, CapturedOutput.INITIAL_CHUNK_BYTES),
                allocations.chunkSizes());
        assertEquals(2, allocations.chunkAllocations());
        allocations.assertSingleFlatten(retainedBytes, 2, true);
    }

    @Test
    void truncationRetainsTheExactPrefixAndDoesNotAllocateBeyondTheLimit() throws IOException {
        AllocationCounter allocations = new AllocationCounter();
        int limit = CapturedOutput.INITIAL_CHUNK_BYTES + 17;
        byte[] bytes = new byte[limit + 2048];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) index;
        }
        CountingInputStream input = new CountingInputStream(bytes);

        CapturedOutput output = CapturedOutput.capture(input, CapturePolicy.bounded(limit), allocations);

        assertEquals(limit, output.bytes().length);
        assertArrayEquals(Arrays.copyOf(bytes, limit), output.bytes());
        assertTrue(output.truncated());
        assertEquals(bytes.length, input.bytesRead());
        assertEquals(limit, allocations.chunkBytes());
        allocations.assertSingleFlatten(limit, 2, true);
    }

    @Test
    void partialFailureFlattensItsExactSnapshotOnce() {
        AllocationCounter allocations = new AllocationCounter();
        byte[] bytes = repeated(CapturedOutput.INITIAL_CHUNK_BYTES + 9, (byte) 'p');

        IOException failure = assertThrows(
                IOException.class,
                () -> CapturedOutput.capture(
                        new StreamClosedAfterBytes(bytes, "controlled failure"),
                        CapturePolicy.bounded(bytes.length * 2),
                        allocations));

        CapturedOutput.PartialCaptureException partial =
                assertInstanceOf(CapturedOutput.PartialCaptureException.class, failure);
        assertArrayEquals(bytes, partial.output().bytes());
        assertFalse(partial.output().truncated());
        assertEquals("controlled failure", partial.getCause().getMessage());
        allocations.assertSingleFlatten(bytes.length, 2, true);
    }

    @Test
    void partialFailureAfterOverflowPreservesTheTruncatedSnapshot() {
        AllocationCounter allocations = new AllocationCounter();
        int limit = CapturedOutput.INITIAL_CHUNK_BYTES;
        byte[] bytes = repeated(limit + 1, (byte) 't');

        CapturedOutput.PartialCaptureException failure = assertThrows(
                CapturedOutput.PartialCaptureException.class,
                () -> CapturedOutput.capture(
                        new StreamClosedAfterBytes(bytes, "controlled failure"),
                        CapturePolicy.bounded(limit),
                        allocations));

        assertEquals(limit, failure.output().bytes().length);
        assertTrue(failure.output().truncated());
        allocations.assertSingleFlatten(limit, 1, true);
    }

    @Test
    void largestValidInternalLimitDoesNotOverflowOrPreallocateTheLimit() throws IOException {
        AllocationCounter allocations = new AllocationCounter();

        CapturedOutput output = CapturedOutput.capture(
                new ByteArrayInputStream("large-limit".getBytes(StandardCharsets.US_ASCII)),
                Integer.MAX_VALUE,
                allocations);

        assertEquals("large-limit", new String(output.bytes(), StandardCharsets.US_ASCII));
        assertFalse(output.truncated());
        assertEquals(List.of(CapturedOutput.INITIAL_CHUNK_BYTES), allocations.chunkSizes());
        allocations.assertSingleFlatten("large-limit".length(), 1, true);
    }

    @Test
    void zeroLimitAndEmptyInputAllocateNoChunksOrResultArray() throws IOException {
        AllocationCounter allocations = new AllocationCounter();

        CapturedOutput output = CapturedOutput.capture(new ByteArrayInputStream(new byte[0]), 0, allocations);

        assertEquals(0, output.bytes().length);
        assertFalse(output.truncated());
        assertEquals(1, allocations.accumulators());
        assertEquals(0, allocations.chunkAllocations());
        allocations.assertSingleFlatten(0, 0, false);
    }

    @Test
    void zeroLimitStillDrainsAndMarksNonEmptyInputAsTruncated() throws IOException {
        AllocationCounter allocations = new AllocationCounter();
        CountingInputStream input = new CountingInputStream("discarded".getBytes(StandardCharsets.US_ASCII));

        CapturedOutput output = CapturedOutput.capture(input, 0, allocations);

        assertEquals(0, output.bytes().length);
        assertTrue(output.truncated());
        assertEquals("discarded".length(), input.bytesRead());
        assertEquals(0, allocations.chunkAllocations());
        allocations.assertSingleFlatten(0, 0, false);
    }

    @Test
    void streamClosedAfterPartialReadStillFailsWithoutLifecycleContext() {
        assertThrows(
                IOException.class,
                () -> CapturedOutput.capture(new StreamClosedAfterBytes("abc".getBytes()), CapturePolicy.bounded(8)));
    }

    @Test
    void streamClosedJdkMessageStillFailsWithoutLifecycleContext() {
        assertThrows(
                IOException.class,
                () -> CapturedOutput.capture(
                        new StreamClosedAfterBytes("abc".getBytes(), "Stream closed"), CapturePolicy.bounded(8)));
    }

    @Test
    void otherIoFailuresStillFailCapture() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk failed");
            }
        };

        assertThrows(IOException.class, () -> CapturedOutput.capture(failing, CapturePolicy.bounded(8)));
    }

    private static byte[] repeated(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static final class AllocationCounter implements CapturedOutput.AllocationProbe {

        private int accumulators;
        private final ArrayList<Integer> chunkSizes = new ArrayList<>();
        private int flattenCalls;
        private int flattenedBytes = -1;
        private int flattenedChunks = -1;
        private int resultAllocations;

        @Override
        public void accumulatorCreated(int limit) {
            accumulators++;
        }

        @Override
        public void chunkAllocated(int capacity) {
            chunkSizes.add(capacity);
        }

        @Override
        public void flattened(int retainedBytes, int chunkCount, boolean resultAllocated) {
            flattenCalls++;
            flattenedBytes = retainedBytes;
            flattenedChunks = chunkCount;
            if (resultAllocated) {
                resultAllocations++;
            }
        }

        private int accumulators() {
            return accumulators;
        }

        private List<Integer> chunkSizes() {
            return List.copyOf(chunkSizes);
        }

        private int chunkAllocations() {
            return chunkSizes.size();
        }

        private int chunkBytes() {
            return chunkSizes.stream().mapToInt(Integer::intValue).sum();
        }

        private void assertSingleFlatten(int expectedBytes, int expectedChunks, boolean expectedResultAllocation) {
            assertEquals(1, flattenCalls);
            assertEquals(expectedBytes, flattenedBytes);
            assertEquals(expectedChunks, flattenedChunks);
            assertEquals(expectedResultAllocation ? 1 : 0, resultAllocations);
        }
    }

    private static final class CountingInputStream extends ByteArrayInputStream {

        private int bytesRead;

        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        private int bytesRead() {
            return bytesRead;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }
    }

    private static final class StreamClosedAfterBytes extends InputStream {

        private final byte[] bytes;
        private final String closedMessage;
        private int index;

        private StreamClosedAfterBytes(byte[] bytes) {
            this(bytes, "Stream Closed");
        }

        private StreamClosedAfterBytes(byte[] bytes, String closedMessage) {
            this.bytes = bytes.clone();
            this.closedMessage = closedMessage;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (index >= bytes.length) {
                throw new IOException(closedMessage);
            }
            int count = Math.min(length, bytes.length - index);
            System.arraycopy(bytes, index, buffer, offset, count);
            index += count;
            return count;
        }

        @Override
        public int read() throws IOException {
            if (index >= bytes.length) {
                throw new IOException(closedMessage);
            }
            return bytes[index++];
        }
    }

    private static final class CountingRepeatingInputStream extends InputStream {

        private final int totalBytes;
        private final byte value;
        private int bytesRead;

        private CountingRepeatingInputStream(int totalBytes, byte value) {
            this.totalBytes = totalBytes;
            this.value = value;
        }

        private int bytesRead() {
            return bytesRead;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (bytesRead >= totalBytes) {
                return -1;
            }
            int count = Math.min(length, totalBytes - bytesRead);
            java.util.Arrays.fill(buffer, offset, offset + count, value);
            bytesRead += count;
            return count;
        }

        @Override
        public int read() {
            if (bytesRead >= totalBytes) {
                return -1;
            }
            bytesRead++;
            return value & 0xff;
        }
    }
}
