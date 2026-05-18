package com.github.ulviar.icli.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
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
}
