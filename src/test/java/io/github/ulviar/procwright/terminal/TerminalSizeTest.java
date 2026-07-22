/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TerminalSizeTest {

    @Test
    void dimensionsUseTheUnsignedTerminalIoctlRange() {
        TerminalSize maximum = new TerminalSize(65_535, 65_535);

        assertEquals(65_535, maximum.columns());
        assertEquals(65_535, maximum.rows());
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(65_536, 24));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSize(80, 65_536));
    }
}
