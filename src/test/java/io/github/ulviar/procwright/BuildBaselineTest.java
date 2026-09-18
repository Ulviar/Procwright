/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class BuildBaselineTest {

    @Test
    void testsUseJava25Toolchain() {
        assertEquals(25, Runtime.version().feature());
    }

    @Test
    void productionClassesTargetJava25Bytecode() throws IOException {
        try (InputStream stream = CommandService.class.getResourceAsStream("CommandService.class")) {
            assertNotNull(stream);

            byte[] header = stream.readNBytes(8);

            int majorVersion = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);

            assertEquals(69, majorVersion);
        }
    }
}
