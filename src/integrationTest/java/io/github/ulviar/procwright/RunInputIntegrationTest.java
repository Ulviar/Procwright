/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.assertStdoutEquals;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunInputIntegrationTest {

    @Test
    void stdinFromPathStreamsLargeFileWithoutBufferingInMemory(@TempDir Path directory) throws IOException {
        Path stdinFile = directory.resolve("stdin.bin");
        byte[] payload = new byte[4 * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        java.nio.file.Files.write(stdinFile, payload);

        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo", "--mode=bytes-count")
                .withInput(io.github.ulviar.procwright.command.CommandInput.fromPath(stdinFile))
                .withTimeout(Duration.ofSeconds(30))
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("bytes:" + payload.length + "\n", result);
    }

    @Test
    void stdinFromMissingFileFailsWithTypedLaunchFailure(@TempDir Path directory) {
        Path missing = directory.resolve("missing-input.bin");

        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class,
                () -> fixtureService()
                        .run()
                        .withArgs("stdin-echo")
                        .withInput(io.github.ulviar.procwright.command.CommandInput.fromPath(missing))
                        .execute());

        assertEquals(CommandExecutionException.Reason.LAUNCH_FAILED, exception.reason());
    }

    @Test
    void runScenarioClosesStdinByDefault() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo", "--mode=bytes-count")
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("bytes:0\n", result);
    }

    @Test
    void inputOverrideWritesStdinBeforeClosingIt() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo")
                .withInput("payload\n")
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("payload\n", result);
    }

    @Test
    void inputCharsetIsIndependentFromOutputCharset() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("stdin-echo", "--mode=hex")
                .withInput("é", Charset.forName("ISO-8859-1"))
                .withCharset(StandardCharsets.US_ASCII)
                .execute();

        assertTrue(result.succeeded());
        assertStdoutEquals("e9\n", result);
    }
}
