package io.github.ulviar.icli.integration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
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
        try {
            return DEFAULT_MAPPER.writeValueAsString(toJsonNode(Objects.requireNonNull(value, "value"), 0, maxDepth));
        } catch (JsonProcessingException exception) {
            throw new JsonParseException("Could not write JSON: " + exception.getOriginalMessage(), exception);
        }
    }

    private static ObjectMapper mapper(int maxDepth) {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
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
}
