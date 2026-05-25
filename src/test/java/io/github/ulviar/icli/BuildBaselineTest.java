package io.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class BuildBaselineTest {

    @Test
    void productionClassesTargetConfiguredJavaBytecode() throws IOException {
        try (InputStream stream = CommandService.class.getResourceAsStream("CommandService.class")) {
            assertNotNull(stream);

            byte[] header = stream.readNBytes(8);

            int majorVersion = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);

            assertEquals(expectedClassFileMajorVersion(), majorVersion);
        }
    }

    private static int expectedClassFileMajorVersion() {
        return switch (Integer.getInteger("icli.javaRelease", 25)) {
            case 17 -> 61;
            case 21 -> 65;
            case 25 -> 69;
            default -> throw new AssertionError("Unsupported icli.javaRelease");
        };
    }
}
