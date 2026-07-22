/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class JacksonJson {

    private static final int MAX_NESTING_DEPTH = 256;
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .build())
            .streamWriteConstraints(StreamWriteConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .build())
            .build();
    private static final ObjectMapper MAPPER = JsonMapper.builder(FACTORY).build();
    private static final ObjectReader READER =
            MAPPER.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectWriter WRITER = MAPPER.writerFor(JsonNode.class);

    private JacksonJson() {}

    static byte[] writeBytes(JsonNode value) {
        byte[] encoded = writeBytes(value, Integer.MAX_VALUE - 8L);
        if (encoded == null) {
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.BAD_FRAME, "JSON request exceeds the supported size");
        }
        return encoded;
    }

    static byte[] writeBytes(JsonNode value, long maxBytes) {
        Objects.requireNonNull(value, "value");
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        BoundedByteArrayOutputStream output =
                new BoundedByteArrayOutputStream((int) Math.min(maxBytes, Integer.MAX_VALUE - 8L));
        try {
            WRITER.writeValue(output, value);
            return output.toByteArray();
        } catch (OutputLimitExceededException exception) {
            return null;
        } catch (IOException exception) {
            if (exception.getCause() instanceof OutputLimitExceededException) {
                return null;
            }
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.BAD_FRAME, "Could not encode JSON request", exception);
        }
    }

    static JsonNode read(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return read(text);
        } catch (CharacterCodingException exception) {
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.INVALID_ENCODING, "JSON frame must be valid UTF-8", exception);
        }
    }

    static JsonNode read(String text) {
        Objects.requireNonNull(text, "text");
        try {
            return READER.readValue(text);
        } catch (JsonProcessingException exception) {
            throw new IntegrationProtocolException(
                    IntegrationProtocolException.Reason.MALFORMED_JSON,
                    "Frame must contain exactly one JSON value",
                    exception);
        }
    }

    private static final class BoundedByteArrayOutputStream extends OutputStream {

        private final int limit;
        private final ByteArrayOutputStream output;

        private BoundedByteArrayOutputStream(int limit) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            output.write(bytes, offset, length);
        }

        private void requireCapacity(int length) throws OutputLimitExceededException {
            if (length > limit - output.size()) {
                throw new OutputLimitExceededException();
            }
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    private static final class OutputLimitExceededException extends IOException {

        private static final long serialVersionUID = 1L;

        private OutputLimitExceededException() {
            super("JSON output exceeds the request byte budget");
        }
    }
}
