/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JSON codec for integration payloads represented by {@link JsonValue}.
 */
public final class JsonCodec {

    private static final int DEFAULT_MAX_DEPTH = 256;
    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;
    private static final ObjectMapper DEFAULT_MAPPER = mapper(DEFAULT_MAX_DEPTH);

    private JsonCodec() {}

    /**
     * Parses one complete JSON value.
     *
     * @param text JSON text
     * @return parsed value
     * @throws JsonParseException when the input is not one complete JSON value
     */
    public static JsonValue parse(String text) {
        return parse(text, DEFAULT_MAX_DEPTH);
    }

    /**
     * Parses one complete JSON value with an explicit nesting limit.
     *
     * @param text JSON text
     * @param maxDepth maximum object/array nesting depth
     * @return parsed value
     * @throws JsonParseException when the input is not one complete JSON value
     */
    public static JsonValue parse(String text, int maxDepth) {
        Objects.requireNonNull(text, "text");
        requirePositive(maxDepth, "maxDepth");
        try {
            JsonNode node = mapperFor(maxDepth).readValue(text, JsonNode.class);
            return toJsonValue(node, 0, maxDepth);
        } catch (JsonProcessingException exception) {
            throw new JsonParseException(
                    "Invalid JSON" + locationSuffix(exception.getLocation()) + ": " + exception.getOriginalMessage(),
                    exception);
        }
    }

    /**
     * Serializes one JSON value.
     *
     * @param value JSON value
     * @return compact JSON text
     */
    public static String write(JsonValue value) {
        return write(value, DEFAULT_MAX_DEPTH);
    }

    /**
     * Serializes one JSON value with an explicit nesting limit.
     *
     * @param value JSON value
     * @param maxDepth maximum object/array nesting depth
     * @return compact JSON text
     */
    public static String write(JsonValue value, int maxDepth) {
        requirePositive(maxDepth, "maxDepth");
        StringWriter output = new StringWriter();
        try (JsonGenerator generator = DEFAULT_MAPPER.createGenerator(output)) {
            writeJsonValue(generator, Objects.requireNonNull(value, "value"), 0, maxDepth);
        } catch (IOException exception) {
            throw writeFailure(exception);
        }
        return output.toString();
    }

    static void writeUtf8(JsonValue value, OutputStream output) throws IOException {
        writeUtf8(value, output, DEFAULT_MAX_DEPTH);
    }

    static long utf8Length(JsonValue value, long maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        BoundedCountingOutputStream output = new BoundedCountingOutputStream(maxBytes);
        writeUtf8(value, output, DEFAULT_MAX_DEPTH);
        return output.count();
    }

    private static void writeUtf8(JsonValue value, OutputStream output, int maxDepth) throws IOException {
        Objects.requireNonNull(output, "output");
        try (JsonGenerator generator = DEFAULT_MAPPER.createGenerator(output)) {
            writeJsonValue(generator, Objects.requireNonNull(value, "value"), 0, maxDepth);
        }
    }

    /**
     * Converts one {@link JsonValue} into a Jackson tree node.
     *
     * <p>This is the bridge for callers that already use Jackson {@code ObjectMapper} pipelines. Numbers become
     * {@code DecimalNode} values backed by the original {@link java.math.BigDecimal}, so precision and scale are
     * preserved exactly. Object member insertion order is preserved. The default nesting limit of {@link #parse(String)}
     * applies.
     *
     * @param value JSON value
     * @return equivalent Jackson tree node
     * @throws JsonParseException when nesting exceeds the default depth limit
     */
    public static JsonNode toJackson(JsonValue value) {
        return toJsonNode(Objects.requireNonNull(value, "value"), 0, DEFAULT_MAX_DEPTH);
    }

    /**
     * Converts one Jackson tree node into a {@link JsonValue}.
     *
     * <p>This is the bridge for callers that build payloads with their own Jackson {@code ObjectMapper}. All numeric
     * node types convert to {@link java.math.BigDecimal}-backed numbers without loss for integral and decimal values;
     * floating-point nodes use their shortest decimal representation. Object member order is preserved. The default
     * nesting limit of {@link #parse(String)} applies.
     *
     * @param node Jackson tree node
     * @return equivalent JSON value
     * @throws JsonParseException when the node contains non-finite numbers, binary, POJO, or missing nodes, or when
     *     nesting exceeds the default depth limit
     */
    public static JsonValue fromJackson(JsonNode node) {
        return toJsonValue(Objects.requireNonNull(node, "node"), 0, DEFAULT_MAX_DEPTH);
    }

    private static ObjectMapper mapper(int maxDepth) {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(maxDepth)
                        .build())
                .build();
        return JsonMapper.builder(factory)
                .nodeFactory(NODE_FACTORY)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
    }

    private static ObjectMapper mapperFor(int maxDepth) {
        return maxDepth == DEFAULT_MAX_DEPTH ? DEFAULT_MAPPER : mapper(maxDepth);
    }

    private static String locationSuffix(JsonLocation location) {
        if (location == null) {
            return "";
        }
        long offset = location.getCharOffset();
        if (offset >= 0) {
            return " at offset " + offset;
        }
        return " at line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    private static JsonValue toJsonValue(JsonNode node, int depth, int maxDepth) {
        Objects.requireNonNull(node, "node");
        if (node.isObject()) {
            requireDepth(depth, maxDepth);
            LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
            node.properties()
                    .forEach(entry -> members.put(entry.getKey(), toJsonValue(entry.getValue(), depth + 1, maxDepth)));
            return JsonValue.object(members);
        }
        if (node.isArray()) {
            requireDepth(depth, maxDepth);
            java.util.ArrayList<JsonValue> values = new java.util.ArrayList<>();
            node.elements().forEachRemaining(element -> values.add(toJsonValue(element, depth + 1, maxDepth)));
            return JsonValue.array(values);
        }
        if (node.isTextual()) {
            return JsonValue.string(node.textValue());
        }
        if (node.isNumber()) {
            if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
                throw new JsonParseException("Non-finite JSON number is not representable: " + node.asText());
            }
            return JsonValue.number(node.decimalValue());
        }
        if (node.isBoolean()) {
            return JsonValue.bool(node.booleanValue());
        }
        if (node.isNull()) {
            return JsonValue.nullValue();
        }
        throw new JsonParseException("Unsupported JSON node type: " + node.getNodeType());
    }

    private static JsonNode toJsonNode(JsonValue value, int depth, int maxDepth) {
        if (value instanceof JsonValue.JsonObject object) {
            requireDepth(depth, maxDepth);
            ObjectNode node = NODE_FACTORY.objectNode();
            object.members().forEach((name, child) -> node.set(name, toJsonNode(child, depth + 1, maxDepth)));
            return node;
        }
        if (value instanceof JsonValue.JsonArray array) {
            requireDepth(depth, maxDepth);
            ArrayNode node = NODE_FACTORY.arrayNode();
            array.values().forEach(child -> node.add(toJsonNode(child, depth + 1, maxDepth)));
            return node;
        }
        if (value instanceof JsonValue.JsonString string) {
            return NODE_FACTORY.textNode(string.value());
        }
        if (value instanceof JsonValue.JsonNumber number) {
            return DecimalNode.valueOf(number.value());
        }
        if (value instanceof JsonValue.JsonBoolean bool) {
            return NODE_FACTORY.booleanNode(bool.value());
        }
        if (value instanceof JsonValue.JsonNull) {
            return NODE_FACTORY.nullNode();
        }
        throw new JsonParseException(
                "Unsupported JSON value type: " + value.getClass().getName());
    }

    private static void writeJsonValue(JsonGenerator generator, JsonValue value, int depth, int maxDepth)
            throws IOException {
        if (value instanceof JsonValue.JsonObject object) {
            requireDepth(depth, maxDepth);
            generator.writeStartObject();
            for (Map.Entry<String, JsonValue> entry : object.members().entrySet()) {
                generator.writeFieldName(entry.getKey());
                writeJsonValue(generator, entry.getValue(), depth + 1, maxDepth);
            }
            generator.writeEndObject();
            return;
        }
        if (value instanceof JsonValue.JsonArray array) {
            requireDepth(depth, maxDepth);
            generator.writeStartArray();
            for (JsonValue child : array.values()) {
                writeJsonValue(generator, child, depth + 1, maxDepth);
            }
            generator.writeEndArray();
            return;
        }
        if (value instanceof JsonValue.JsonString string) {
            generator.writeString(string.value());
            return;
        }
        if (value instanceof JsonValue.JsonNumber number) {
            generator.writeNumber(number.value());
            return;
        }
        if (value instanceof JsonValue.JsonBoolean bool) {
            generator.writeBoolean(bool.value());
            return;
        }
        if (value instanceof JsonValue.JsonNull) {
            generator.writeNull();
            return;
        }
        throw new JsonParseException(
                "Unsupported JSON value type: " + value.getClass().getName());
    }

    private static JsonParseException writeFailure(IOException exception) {
        String message = exception instanceof JsonProcessingException processing
                ? processing.getOriginalMessage()
                : exception.getMessage();
        return new JsonParseException("Could not write JSON: " + message, exception);
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void requireDepth(int depth, int maxDepth) {
        if (depth >= maxDepth) {
            throw new JsonParseException("JSON nesting exceeds maxDepth");
        }
    }

    static final class OutputLimitExceededException extends IOException {

        private static final long serialVersionUID = 1L;

        private OutputLimitExceededException(long limit) {
            super("JSON output exceeds " + limit + " bytes");
        }
    }

    private static final class BoundedCountingOutputStream extends OutputStream {

        private final long limit;
        private long count;

        private BoundedCountingOutputStream(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws OutputLimitExceededException {
            add(1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws OutputLimitExceededException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            add(length);
        }

        private void add(int length) throws OutputLimitExceededException {
            if (length > limit - count) {
                throw new OutputLimitExceededException(limit);
            }
            count += length;
        }

        private long count() {
            return count;
        }
    }
}
