/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal immutable JSON value model used by CLI-backed integration helpers.
 */
public sealed interface JsonValue
        permits JsonValue.JsonObject,
                JsonValue.JsonArray,
                JsonValue.JsonString,
                JsonValue.JsonNumber,
                JsonValue.JsonBoolean,
                JsonValue.JsonNull {

    /**
     * Creates an immutable JSON object.
     *
     * @param members object members
     * @return JSON object
     * @throws JsonParseException when a member name contains an unpaired UTF-16 surrogate
     */
    static JsonObject object(Map<String, JsonValue> members) {
        return new JsonObject(members);
    }

    /**
     * Creates an immutable JSON array.
     *
     * @param values array values
     * @return JSON array
     */
    static JsonArray array(List<JsonValue> values) {
        return new JsonArray(values);
    }

    /**
     * Creates a JSON string.
     *
     * @param value string value
     * @return JSON string
     * @throws JsonParseException when the value contains an unpaired UTF-16 surrogate
     */
    static JsonString string(String value) {
        return new JsonString(value);
    }

    /**
     * Creates a JSON number from a validated JSON-number token.
     *
     * @param value JSON number token
     * @return JSON number
     */
    static JsonNumber number(String value) {
        return new JsonNumber(JsonNumbers.parse(value));
    }

    /**
     * Creates a JSON number from a long.
     *
     * @param value number value
     * @return JSON number
     */
    static JsonNumber number(long value) {
        return new JsonNumber(BigDecimal.valueOf(value));
    }

    /**
     * Creates a JSON number from a decimal value.
     *
     * @param value number value
     * @return JSON number
     */
    static JsonNumber number(BigDecimal value) {
        return new JsonNumber(value);
    }

    /**
     * Creates a JSON boolean.
     *
     * @param value boolean value
     * @return JSON boolean
     */
    static JsonBoolean bool(boolean value) {
        return new JsonBoolean(value);
    }

    /**
     * Returns the singleton JSON null value.
     *
     * @return JSON null
     */
    static JsonNull nullValue() {
        return JsonNull.INSTANCE;
    }

    /**
     * JSON object with insertion-ordered members.
     *
     * @param members object members
     */
    record JsonObject(Map<String, JsonValue> members) implements JsonValue {

        /**
         * Creates an object.
         *
         * @param members object members
         * @throws JsonParseException when a member name contains an unpaired UTF-16 surrogate
         */
        public JsonObject {
            Objects.requireNonNull(members, "members");
            LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> entry : members.entrySet()) {
                String name = JsonStrings.requireWellFormedUtf16(entry.getKey(), "member name", "JSON member name");
                copy.put(name, Objects.requireNonNull(entry.getValue(), "member value"));
            }
            members = Collections.unmodifiableMap(copy);
        }

        /**
         * Returns one member by name.
         *
         * @param name member name
         * @return member value when present
         */
        public Optional<JsonValue> member(String name) {
            return Optional.ofNullable(members.get(Objects.requireNonNull(name, "name")));
        }
    }

    /**
     * JSON array.
     *
     * @param values array values
     */
    record JsonArray(List<JsonValue> values) implements JsonValue {

        /**
         * Creates an array.
         *
         * @param values array values
         */
        public JsonArray {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(new ArrayList<>(values));
            for (JsonValue value : values) {
                Objects.requireNonNull(value, "value");
            }
        }
    }

    /**
     * JSON string.
     *
     * @param value string value
     */
    record JsonString(String value) implements JsonValue {

        /**
         * Creates a string.
         *
         * @param value string value
         * @throws JsonParseException when the value contains an unpaired UTF-16 surrogate
         */
        public JsonString {
            value = JsonStrings.requireWellFormedUtf16(value, "value", "JSON string");
        }
    }

    /**
     * JSON number.
     *
     * @param value decimal value
     */
    record JsonNumber(BigDecimal value) implements JsonValue {

        /**
         * Creates a number.
         *
         * @param value decimal value
         */
        public JsonNumber {
            value = JsonNumbers.canonicalize(value);
        }
    }

    /**
     * JSON boolean.
     *
     * @param value boolean value
     */
    record JsonBoolean(boolean value) implements JsonValue {}

    /**
     * JSON null singleton.
     */
    enum JsonNull implements JsonValue {
        /** Singleton null value. */
        INSTANCE
    }
}
