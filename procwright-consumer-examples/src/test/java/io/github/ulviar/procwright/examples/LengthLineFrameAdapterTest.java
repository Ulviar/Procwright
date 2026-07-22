/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class LengthLineFrameAdapterTest {

    @Test
    void bodyLengthHeaderAcceptsDocumentedBounds() {
        assertEquals(0, LengthLineFrameAdapter.parseBodyLength("len:0"));
        assertEquals(8192, LengthLineFrameAdapter.parseBodyLength("len:8192"));
    }

    @Test
    void bodyLengthHeaderRejectsNonCanonicalOrOversizedValues() {
        for (String header : List.of(
                "",
                "length:1",
                "len:",
                "len:-1",
                "len:+1",
                "len: 1",
                "len:1 ",
                "len:00",
                "len:01",
                "len:8193",
                "len:2147483648",
                "len:\u0661")) {
            assertThrows(IllegalStateException.class, () -> LengthLineFrameAdapter.parseBodyLength(header), header);
        }
    }
}
