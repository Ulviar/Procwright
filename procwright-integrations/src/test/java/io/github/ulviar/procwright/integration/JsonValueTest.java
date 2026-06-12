/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class JsonValueTest {

    @Test
    void objectSnapshotsOrderedMembersAndSupportsLookup() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("first", JsonValue.string("one"));
        members.put("second", JsonValue.number(2));

        JsonValue.JsonObject object = JsonValue.object(members);
        members.put("ignored", JsonValue.bool(true));

        assertEquals(List.of("first", "second"), List.copyOf(object.members().keySet()));
        assertEquals(Optional.of(JsonValue.string("one")), object.member("first"));
        assertEquals(Optional.empty(), object.member("missing"));
        assertThrows(UnsupportedOperationException.class, () -> object.members().put("third", JsonValue.nullValue()));
    }

    @Test
    void objectRejectsNullMemberParts() {
        assertThrows(NullPointerException.class, () -> JsonValue.object(null));
        assertThrows(NullPointerException.class, () -> JsonValue.object(membersWithNullName()));
        assertThrows(NullPointerException.class, () -> JsonValue.object(membersWithNullValue()));

        JsonValue.JsonObject object = JsonValue.object(Map.of());
        assertThrows(NullPointerException.class, () -> object.member(null));
    }

    @Test
    void arraySnapshotsValues() {
        ArrayList<JsonValue> values = new ArrayList<>(List.of(JsonValue.string("one"), JsonValue.number(2)));

        JsonValue.JsonArray array = JsonValue.array(values);
        values.add(JsonValue.bool(true));

        assertEquals(List.of(JsonValue.string("one"), JsonValue.number(2)), array.values());
        assertThrows(UnsupportedOperationException.class, () -> array.values().add(JsonValue.nullValue()));
    }

    @Test
    void arrayRejectsNullValues() {
        assertThrows(NullPointerException.class, () -> JsonValue.array(null));
        assertThrows(NullPointerException.class, () -> JsonValue.array(valuesWithNull()));
    }

    @Test
    void scalarFactoriesRejectNulls() {
        assertThrows(NullPointerException.class, () -> JsonValue.string(null));
        assertThrows(NullPointerException.class, () -> JsonValue.number((String) null));
        assertThrows(NullPointerException.class, () -> JsonValue.number((BigDecimal) null));
    }

    private static Map<String, JsonValue> membersWithNullName() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put(null, JsonValue.nullValue());
        return members;
    }

    private static Map<String, JsonValue> membersWithNullValue() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("name", null);
        return members;
    }

    private static List<JsonValue> valuesWithNull() {
        ArrayList<JsonValue> values = new ArrayList<>();
        values.add(null);
        return values;
    }
}
