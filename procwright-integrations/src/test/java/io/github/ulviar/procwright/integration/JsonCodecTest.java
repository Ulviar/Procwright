/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonCodecTest {

    @Test
    void writesAndParsesJsonValues() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("text", JsonValue.string("hello\nworld"));
        members.put("number", JsonValue.number("12.5"));
        members.put("array", JsonValue.array(List.of(JsonValue.bool(true), JsonValue.nullValue())));
        JsonValue value = JsonValue.object(members);

        String json = JsonCodec.write(value);
        JsonValue parsed = JsonCodec.parse(json);

        assertEquals(value, parsed);
        assertEquals("{\"text\":\"hello\\nworld\",\"number\":12.5,\"array\":[true,null]}", json);
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("{} {}"));
    }

    @Test
    void rejectsDuplicateObjectMembers() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("{\"id\":1,\"id\":2}"));
    }

    @Test
    void rejectsInvalidNumber() {
        assertThrows(JsonParseException.class, () -> JsonValue.number("01"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("01"));
    }

    @Test
    void rejectsUnescapedControlCharacters() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("\"\n\""));
    }

    @Test
    void rejectsInvalidAndIncompleteStringEscapes() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("\"\\x\""));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("\"\\"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("\"\\u12\""));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("\"\\u12xz\""));
    }

    @Test
    void rejectsInvalidNumberEdges() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("-"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("1."));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("1e"));
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("1e+"));
    }

    @Test
    void rejectsJsonNestingBeyondConfiguredDepth() {
        assertThrows(JsonParseException.class, () -> JsonCodec.parse("[[0]]", 1));
        assertEquals(
                JsonValue.array(List.of(JsonValue.array(List.of(JsonValue.number(0))))), JsonCodec.parse("[[0]]", 2));
    }

    @Test
    void rejectsJsonWritingBeyondConfiguredDepth() {
        JsonValue value = JsonValue.array(List.of(JsonValue.array(List.of(JsonValue.number(0)))));

        assertThrows(JsonParseException.class, () -> JsonCodec.write(value, 1));
        assertEquals("[[0]]", JsonCodec.write(value, 2));
    }

    @Test
    void jsonLineEscapesEmbeddedLineSeparators() {
        String line = JsonLines.line(JsonValue.string("a\nb"));

        JsonValue parsed = JsonLines.parseLine(line);

        assertEquals(JsonValue.string("a\nb"), parsed);
        assertEquals(-1, line.indexOf('\n'));
    }

    @Test
    void jsonLineRejectsRawLineSeparators() {
        assertThrows(IllegalArgumentException.class, () -> JsonLines.parseLine("{}\n{}"));
    }

    @Test
    void objectMemberLookupReturnsTypedValue() {
        JsonValue.JsonObject object =
                assertInstanceOf(JsonValue.JsonObject.class, JsonCodec.parse("{\"name\":\"tool\"}"));

        assertEquals(JsonValue.string("tool"), object.member("name").orElseThrow());
    }

    @Test
    void jacksonRoundTripPreservesObjectsArraysAndScalars() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("text", JsonValue.string("hello"));
        members.put("flag", JsonValue.bool(true));
        members.put("nothing", JsonValue.nullValue());
        members.put(
                "nested",
                JsonValue.array(List.of(
                        JsonValue.array(List.of(JsonValue.number(1))),
                        JsonValue.object(Map.of("deep", JsonValue.string("value"))))));
        JsonValue value = JsonValue.object(members);

        JsonNode node = JsonCodec.toJackson(value);

        assertEquals(value, JsonCodec.fromJackson(node));
        assertEquals("hello", node.get("text").textValue());
        assertTrue(node.get("nothing").isNull());
        assertTrue(node.get("nested").isArray());
    }

    @Test
    void jacksonBridgePreservesObjectMemberOrder() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("zulu", JsonValue.number(1));
        members.put("alpha", JsonValue.number(2));

        JsonValue.JsonObject roundTripped = assertInstanceOf(
                JsonValue.JsonObject.class, JsonCodec.fromJackson(JsonCodec.toJackson(JsonValue.object(members))));

        assertEquals(
                List.of("zulu", "alpha"), List.copyOf(roundTripped.members().keySet()));
    }

    @Test
    void jacksonRoundTripPreservesNumberPrecision() {
        JsonValue value = JsonValue.array(List.of(
                JsonValue.number(Long.MAX_VALUE),
                JsonValue.number(new BigDecimal("3.14159265358979323846264338327950288")),
                JsonValue.number(new BigDecimal("1.500")),
                JsonValue.number(new BigDecimal("-2.5E+100"))));

        assertEquals(value, JsonCodec.fromJackson(JsonCodec.toJackson(value)));
    }

    @Test
    void jacksonRoundTripPreservesUnicodeStrings() {
        JsonValue value = JsonValue.object(Map.of("emoji", JsonValue.string("snowman ☃, astronaut 🚀, línea")));

        assertEquals(value, JsonCodec.fromJackson(JsonCodec.toJackson(value)));
    }

    @Test
    void fromJacksonConvertsNativeNumberNodesExactly() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode node = factory.objectNode();
        node.set("int", factory.numberNode(42));
        node.set("long", factory.numberNode(Long.MAX_VALUE));
        node.set("bigInteger", factory.numberNode(new BigInteger("123456789012345678901234567890")));
        node.set("double", factory.numberNode(0.5d));
        node.set("decimal", factory.numberNode(new BigDecimal("10.25")));

        JsonValue.JsonObject value = assertInstanceOf(JsonValue.JsonObject.class, JsonCodec.fromJackson(node));

        assertEquals(JsonValue.number(42), value.member("int").orElseThrow());
        assertEquals(JsonValue.number(Long.MAX_VALUE), value.member("long").orElseThrow());
        assertEquals(
                JsonValue.number(new BigDecimal("123456789012345678901234567890")),
                value.member("bigInteger").orElseThrow());
        assertEquals(
                JsonValue.number(new BigDecimal("0.5")), value.member("double").orElseThrow());
        assertEquals(
                JsonValue.number(new BigDecimal("10.25")),
                value.member("decimal").orElseThrow());
    }

    @Test
    void fromJacksonRejectsNonJsonNodeTypes() {
        JsonNodeFactory factory = JsonNodeFactory.instance;

        assertThrows(JsonParseException.class, () -> JsonCodec.fromJackson(factory.binaryNode(new byte[] {1})));
        assertThrows(JsonParseException.class, () -> JsonCodec.fromJackson(factory.pojoNode(new Object())));
        assertThrows(JsonParseException.class, () -> JsonCodec.fromJackson(MissingNode.getInstance()));
    }

    @Test
    void fromJacksonRejectsNonFiniteNumbers() {
        JsonNodeFactory factory = JsonNodeFactory.instance;

        assertThrows(JsonParseException.class, () -> JsonCodec.fromJackson(factory.numberNode(Double.NaN)));
        assertThrows(
                JsonParseException.class, () -> JsonCodec.fromJackson(factory.numberNode(Double.POSITIVE_INFINITY)));
        assertThrows(JsonParseException.class, () -> JsonCodec.fromJackson(factory.numberNode(Float.NaN)));
    }

    @Test
    void jacksonBridgeRejectsNestingBeyondDefaultDepth() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ArrayNode rootNode = factory.arrayNode();
        ArrayNode currentNode = rootNode;
        JsonValue deepValue = JsonValue.array(List.of());
        for (int i = 0; i < 300; i++) {
            ArrayNode child = factory.arrayNode();
            currentNode.add(child);
            currentNode = child;
            deepValue = JsonValue.array(List.of(deepValue));
        }
        JsonValue value = deepValue;

        assertThrows(JsonParseException.class, () -> JsonCodec.fromJackson(rootNode));
        assertThrows(JsonParseException.class, () -> JsonCodec.toJackson(value));
    }
}
