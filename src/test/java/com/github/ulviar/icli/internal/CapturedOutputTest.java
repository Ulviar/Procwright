package com.github.ulviar.icli.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.ulviar.icli.command.CapturePolicy;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class CapturedOutputTest {

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
}
