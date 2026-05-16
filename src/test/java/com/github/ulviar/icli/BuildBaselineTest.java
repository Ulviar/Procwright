package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class BuildBaselineTest {

    private static final int JAVA_25_CLASS_FILE_MAJOR_VERSION = 69;

    @Test
    void productionClassesTargetJava25Bytecode() throws IOException {
        try (InputStream stream = CommandService.class.getResourceAsStream("CommandService.class")) {
            assertNotNull(stream);

            byte[] header = stream.readNBytes(8);

            int majorVersion = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);

            assertEquals(JAVA_25_CLASS_FILE_MAJOR_VERSION, majorVersion);
        }
    }
}
