/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.assertStdoutEquals;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RunCharsetPolicyIntegrationTest {

    @Test
    void outputIsDecodedWithConfiguredCharset() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=d09fd180d0b8d0b2d0b5d1820a")
                .withCharset(StandardCharsets.UTF_8)
                .execute();

        assertStdoutEquals("Привет\n", result);
    }

    @Test
    void strictCharsetPolicyReportsDecodeErrorAsTypedFailure() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=ff", "--stream=both")
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .execute());

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
        CommandResult snapshot = exception.result().orElseThrow();
        assertEquals(0, snapshot.exitCode().orElseThrow());
        assertEquals((byte) 0xFF, snapshot.stdoutBytes()[0]);
        assertEquals((byte) 0xFF, snapshot.stderrBytes()[0]);
        assertTrue(snapshot.stdout().contains("\uFFFD"));
        assertTrue(snapshot.stderr().contains("\uFFFD"));
    }

    @Test
    void replacementDecodingPreservesExactCapturedBytes() {
        byte[] expected = {0x00, (byte) 0xFF, 0x41};
        RunScenario.Draft strict = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=00ff41", "--stream=both")
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        CommandResult result = strict.withTimeout(Duration.ofSeconds(2))
                .withCapture(CapturePolicy.bounded(1024))
                .withOutput(OutputMode.SEPARATE)
                .withCharsetPolicy(CharsetPolicy.replace(StandardCharsets.UTF_8))
                .execute();

        assertTrue(result.succeeded());
        assertArrayEquals(expected, result.stdoutBytes());
        assertArrayEquals(expected, result.stderrBytes());
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.stdout());
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.stderr());
    }

    @Test
    void unrelatedRunPoliciesPreserveStrictDecodingPolicy() {
        RunScenario.Draft strict = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=ff")
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        CommandExecutionException failure =
                assertThrows(CommandExecutionException.class, () -> strict.withTimeout(Duration.ofSeconds(2))
                        .withCapture(CapturePolicy.bounded(1024))
                        .withOutput(OutputMode.SEPARATE)
                        .execute());

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, failure.reason());
    }

    @Test
    void strictCharsetPolicyAcceptsValidOutputTruncatedInsideFinalCodePoint() {
        CommandResult result = fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=e282ac")
                .withCapture(CapturePolicy.bounded(2))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .execute();

        assertTrue(result.stdoutTruncated());
        assertEquals(2, result.stdoutBytes().length);
        assertEquals("", result.stdout());
    }

    @Test
    void capturedOutputPreservesEmittedNewlineStyleWithoutNormalization() {
        CommandResult lf = fixtureService()
                .run()
                .withArgs("platform-newlines", "--style=lf")
                .execute();
        CommandResult crlf = fixtureService()
                .run()
                .withArgs("platform-newlines", "--style=crlf")
                .execute();
        CommandResult crOnly = fixtureService()
                .run()
                .withArgs("platform-newlines", "--style=cr")
                .execute();

        assertEquals("out:0\nout:1\n", lf.stdout());
        assertEquals("out:0\r\nout:1\r\n", crlf.stdout());
        assertEquals("out:0\rout:1\r", crOnly.stdout());
    }
}
