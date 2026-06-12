/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class RunOptionsTest {

    @Test
    void defaultsExposeDocumentedValues() {
        RunOptions defaults = RunOptions.defaults();

        assertEquals(Duration.ofSeconds(30), defaults.timeout());
        assertEquals(CapturePolicy.bounded(1024 * 1024), defaults.capturePolicy());
        assertEquals(
                ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                defaults.shutdownPolicy());
        assertEquals(CharsetPolicy.replace(StandardCharsets.UTF_8), defaults.charsetPolicy());
        assertEquals(OutputMode.SEPARATE, defaults.outputMode());
    }

    @Test
    void withTimeoutReplacesOnlyTimeout() {
        RunOptions updated = RunOptions.defaults().withTimeout(Duration.ofSeconds(7));

        assertEquals(Duration.ofSeconds(7), updated.timeout());
        assertEquals(RunOptions.defaults().capturePolicy(), updated.capturePolicy());
        assertEquals(RunOptions.defaults().shutdownPolicy(), updated.shutdownPolicy());
        assertEquals(RunOptions.defaults().charsetPolicy(), updated.charsetPolicy());
        assertEquals(RunOptions.defaults().outputMode(), updated.outputMode());
    }

    @Test
    void withTimeoutAcceptsZeroAsDisabledAndRejectsNegative() {
        assertEquals(
                Duration.ZERO, RunOptions.defaults().withTimeout(Duration.ZERO).timeout());
        assertThrows(IllegalArgumentException.class, () -> RunOptions.defaults().withTimeout(Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> RunOptions.defaults().withTimeout(null));
    }

    @Test
    void withCaptureReplacesOnlyCapturePolicy() {
        RunOptions updated = RunOptions.defaults().withCapture(CapturePolicy.bounded(16));

        assertEquals(CapturePolicy.bounded(16), updated.capturePolicy());
        assertEquals(RunOptions.defaults().timeout(), updated.timeout());
        assertThrows(NullPointerException.class, () -> RunOptions.defaults().withCapture(null));
    }

    @Test
    void withCaptureAcceptsRedirectingPolicies() {
        RunOptions discard = RunOptions.defaults().withCapture(CapturePolicy.discard());
        RunOptions toPath = RunOptions.defaults().withCapture(CapturePolicy.toPath(Path.of("out"), Path.of("err")));

        assertEquals(CapturePolicy.discard(), discard.capturePolicy());
        assertEquals(CapturePolicy.toPath(Path.of("out"), Path.of("err")), toPath.capturePolicy());
    }

    @Test
    void withShutdownReplacesOnlyShutdownPolicy() {
        ShutdownPolicy shutdown = ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(100));

        RunOptions updated = RunOptions.defaults().withShutdown(shutdown);

        assertEquals(shutdown, updated.shutdownPolicy());
        assertEquals(RunOptions.defaults().capturePolicy(), updated.capturePolicy());
        assertThrows(NullPointerException.class, () -> RunOptions.defaults().withShutdown(null));
    }

    @Test
    void withCharsetPolicyReplacesOnlyCharsetPolicy() {
        RunOptions updated = RunOptions.defaults().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.US_ASCII));

        assertEquals(CharsetPolicy.report(StandardCharsets.US_ASCII), updated.charsetPolicy());
        assertEquals(StandardCharsets.US_ASCII, updated.charset());
        assertEquals(RunOptions.defaults().outputMode(), updated.outputMode());
        assertThrows(NullPointerException.class, () -> RunOptions.defaults().withCharsetPolicy(null));
    }

    @Test
    void withOutputModeReplacesOnlyOutputMode() {
        RunOptions updated = RunOptions.defaults().withOutputMode(OutputMode.MERGED);

        assertEquals(OutputMode.MERGED, updated.outputMode());
        assertEquals(RunOptions.defaults().timeout(), updated.timeout());
        assertThrows(NullPointerException.class, () -> RunOptions.defaults().withOutputMode(null));
    }
}
