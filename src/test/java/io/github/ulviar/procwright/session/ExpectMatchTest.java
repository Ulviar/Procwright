/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExpectMatchTest {

    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new ExpectMatch(null, List.of(), ""));
        assertThrows(NullPointerException.class, () -> new ExpectMatch("m", null, ""));
        assertThrows(NullPointerException.class, () -> new ExpectMatch("m", List.of(), null));
    }

    @Test
    void groupsAreImmutableCopies() {
        ArrayList<String> groups = new ArrayList<>(List.of("a"));
        ExpectMatch match = new ExpectMatch("match", groups, "before");

        groups.add("b");

        assertEquals(List.of("a"), match.groups());
        assertThrows(UnsupportedOperationException.class, () -> match.groups().add("c"));
    }
}
