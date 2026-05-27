package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProtocolAdaptersTest {

    @Test
    void jsonLinesAdapterWritesAndReadsJsonValues() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.jsonLines(1024);

        adapter.writeRequest(JsonValue.object(Map.of("request", JsonValue.string("ok"))), writer);
        JsonValue response = adapter.readResponse(readers("{\"response\":\"ok\"}\n"));

        assertEquals("{\"request\":\"ok\"}\n", writer.text());
        assertEquals(JsonValue.object(Map.of("response", JsonValue.string("ok"))), response);
    }

    @Test
    void delimiterAdapterWritesAndReadsDelimitedFrames() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.delimiter((byte) 0, 1024);

        adapter.writeRequest(new byte[] {1, 2}, writer);
        byte[] response = adapter.readResponse(readers(new byte[] {3, 4, 0}));

        assertArrayEquals(new byte[] {1, 2, 0}, writer.bytes());
        assertArrayEquals(new byte[] {3, 4}, response);
    }

    @Test
    void delimiterAdapterRejectsAmbiguousRequestFrames() {
        var adapter = ProtocolAdapters.delimiter((byte) 0, 1024);

        IntegrationProtocolException exception = assertThrows(
                IntegrationProtocolException.class,
                () -> adapter.writeRequest(new byte[] {1, 0}, new RecordingWriter()));

        assertEquals(IntegrationProtocolException.Reason.BAD_FRAME, exception.reason());
    }

    @Test
    void contentLengthAdapterWritesAndReadsJsonFrames() {
        RecordingWriter writer = new RecordingWriter();
        var adapter = ProtocolAdapters.contentLengthJson(1024);
        JsonValue request = JsonValue.object(Map.of("id", JsonValue.number(1)));
        JsonValue response = JsonValue.object(Map.of("ok", JsonValue.bool(true)));

        adapter.writeRequest(request, writer);
        JsonValue decoded = adapter.readResponse(readers(ContentLengthJsonFrames.frame(response)));

        assertEquals(request, ContentLengthJsonFrames.read(new java.io.ByteArrayInputStream(writer.bytes()), 1024));
        assertEquals(response, decoded);
    }

    private static ProtocolReaders readers(String stdout) {
        return readers(stdout.getBytes(StandardCharsets.UTF_8));
    }

    private static ProtocolReaders readers(byte[] stdout) {
        ByteReader stdoutReader = new ByteReader(stdout);
        ByteReader stderrReader = new ByteReader(new byte[0]);
        return new ProtocolReaders() {
            @Override
            public ProtocolReader stdout() {
                return stdoutReader;
            }

            @Override
            public ProtocolReader stderr() {
                return stderrReader;
            }
        };
    }

    private static final class RecordingWriter implements ProtocolWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public void write(byte[] bytes) {
            output.writeBytes(bytes);
        }

        @Override
        public void write(String text) {
            write(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void writeLine(String line) {
            write(line + "\n");
        }

        @Override
        public void flush() {}

        private byte[] bytes() {
            return output.toByteArray();
        }

        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class ByteReader implements ProtocolReader {
        private final byte[] bytes;
        private int offset;

        private ByteReader(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public byte readByte() {
            return bytes[offset++];
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int count = Math.min(length, bytes.length - this.offset);
            System.arraycopy(bytes, this.offset, buffer, offset, count);
            this.offset += count;
            return count;
        }

        @Override
        public byte[] readExactly(int length) {
            byte[] result = new byte[length];
            read(result, 0, length);
            return result;
        }

        @Override
        public byte[] readUntil(byte delimiter, int maxBytes) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (result.size() < maxBytes) {
                byte value = readByte();
                result.write(value);
                if (value == delimiter) {
                    return result.toByteArray();
                }
            }
            throw new AssertionError("delimiter not found");
        }

        @Override
        public String readLine(int maxChars) {
            String line = readTextUntil((byte) '\n', maxChars + 1);
            return line.substring(0, line.length() - 1);
        }

        @Override
        public String readTextUntil(byte delimiter, int maxChars) {
            return new String(readUntil(delimiter, maxChars), StandardCharsets.UTF_8);
        }
    }
}
