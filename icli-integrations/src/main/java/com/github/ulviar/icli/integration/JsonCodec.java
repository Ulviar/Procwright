package com.github.ulviar.icli.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small JSON codec for integration payloads.
 *
 * <p>The codec is intentionally limited to JSON values represented by {@link JsonValue}. It does not bind iCLI to an
 * external JSON library or protocol stack.
 */
public final class JsonCodec {

    private static final int DEFAULT_MAX_DEPTH = 256;

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
        Parser parser = new Parser(text, maxDepth);
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.exhausted()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
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
        StringBuilder builder = new StringBuilder();
        append(builder, Objects.requireNonNull(value, "value"), 0, maxDepth);
        return builder.toString();
    }

    private static void append(StringBuilder builder, JsonValue value, int depth, int maxDepth) {
        switch (value) {
            case JsonValue.JsonObject object -> appendObject(builder, object, depth, maxDepth);
            case JsonValue.JsonArray array -> appendArray(builder, array, depth, maxDepth);
            case JsonValue.JsonString string -> appendString(builder, string.value());
            case JsonValue.JsonNumber number -> builder.append(number.value().toString());
            case JsonValue.JsonBoolean bool -> builder.append(bool.value());
            case JsonValue.JsonNull ignored -> builder.append("null");
        }
    }

    private static void appendObject(StringBuilder builder, JsonValue.JsonObject object, int depth, int maxDepth) {
        requireDepth(depth, maxDepth);
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> entry : object.members().entrySet()) {
            if (!first) {
                builder.append(',');
            }
            appendString(builder, entry.getKey());
            builder.append(':');
            append(builder, entry.getValue(), depth + 1, maxDepth);
            first = false;
        }
        builder.append('}');
    }

    private static void appendArray(StringBuilder builder, JsonValue.JsonArray array, int depth, int maxDepth) {
        requireDepth(depth, maxDepth);
        builder.append('[');
        boolean first = true;
        for (JsonValue value : array.values()) {
            if (!first) {
                builder.append(',');
            }
            append(builder, value, depth + 1, maxDepth);
            first = false;
        }
        builder.append(']');
    }

    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(ch);
                        builder.append("0".repeat(4 - hex.length()));
                        builder.append(hex);
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
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

    private static final class Parser {

        private final String text;
        private final int maxDepth;
        private int index;

        private Parser(String text, int maxDepth) {
            this.text = Objects.requireNonNull(text, "text");
            this.maxDepth = requirePositive(maxDepth, "maxDepth");
        }

        private JsonValue parseValue() {
            return parseValue(0);
        }

        private JsonValue parseValue(int depth) {
            skipWhitespace();
            if (exhausted()) {
                throw error("Expected JSON value");
            }
            return switch (text.charAt(index)) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> JsonValue.string(parseString());
                case 't' -> parseLiteral("true", JsonValue.bool(true));
                case 'f' -> parseLiteral("false", JsonValue.bool(false));
                case 'n' -> parseLiteral("null", JsonValue.nullValue());
                default -> parseNumber();
            };
        }

        private JsonValue.JsonObject parseObject(int depth) {
            requireDepth(depth, maxDepth);
            expect('{');
            skipWhitespace();
            LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
            if (peek('}')) {
                index++;
                return JsonValue.object(members);
            }
            while (true) {
                skipWhitespace();
                if (exhausted() || text.charAt(index) != '"') {
                    throw error("Expected object member name");
                }
                String name = parseString();
                skipWhitespace();
                expect(':');
                JsonValue value = parseValue(depth + 1);
                if (members.putIfAbsent(name, value) != null) {
                    throw error("Duplicate object member");
                }
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return JsonValue.object(members);
                }
                expect(',');
            }
        }

        private JsonValue.JsonArray parseArray(int depth) {
            requireDepth(depth, maxDepth);
            expect('[');
            skipWhitespace();
            ArrayList<JsonValue> values = new ArrayList<>();
            if (peek(']')) {
                index++;
                return JsonValue.array(values);
            }
            while (true) {
                values.add(parseValue(depth + 1));
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return JsonValue.array(values);
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!exhausted()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch < 0x20) {
                    throw error("String must not contain unescaped control characters");
                }
                if (ch != '\\') {
                    builder.append(ch);
                    continue;
                }
                if (exhausted()) {
                    throw error("Incomplete escape sequence");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> builder.append(parseUnicodeEscape());
                    default -> throw error("Invalid escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("Incomplete unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                char ch = text.charAt(index++);
                int digit = Character.digit(ch, 16);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = (value << 4) + digit;
            }
            return (char) value;
        }

        private JsonValue parseLiteral(String literal, JsonValue value) {
            if (!text.startsWith(literal, index)) {
                throw error("Invalid literal");
            }
            index += literal.length();
            return value;
        }

        private JsonValue.JsonNumber parseNumber() {
            int start = index;
            if (index < text.length() && text.charAt(index) == '-') {
                index++;
            }
            readInteger();
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                readDigits("Expected fraction digits");
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                readDigits("Expected exponent digits");
            }
            String token = text.substring(start, index);
            if (!JsonNumbers.isJsonNumber(token)) {
                throw error("Invalid number");
            }
            return JsonValue.number(new BigDecimal(token));
        }

        private void readInteger() {
            if (index >= text.length()) {
                throw error("Expected number");
            }
            char first = text.charAt(index);
            if (first == '0') {
                index++;
                if (index < text.length() && Character.isDigit(text.charAt(index))) {
                    throw error("Invalid leading zero");
                }
                return;
            }
            if (first >= '1' && first <= '9') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
                return;
            }
            throw error("Expected number");
        }

        private void readDigits(String message) {
            int start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw error(message);
            }
        }

        private void expect(char expected) {
            if (exhausted() || text.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return !exhausted() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (!exhausted()) {
                char ch = text.charAt(index);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    index++;
                    continue;
                }
                return;
            }
        }

        private boolean exhausted() {
            return index >= text.length();
        }

        private JsonParseException error(String message) {
            return new JsonParseException(message + " at offset " + index);
        }
    }
}
