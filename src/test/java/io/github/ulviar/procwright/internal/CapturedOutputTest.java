package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import java.io.IOException;
import java.io.InputStream;
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
