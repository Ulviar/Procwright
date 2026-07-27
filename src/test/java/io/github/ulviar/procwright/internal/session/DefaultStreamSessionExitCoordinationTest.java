/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.session.StreamExit;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class DefaultStreamSessionExitCoordinationTest extends DefaultStreamSessionTestSupport {

    @Test
    void earlyProcessExitDoesNotWaitForStreamTimeout() throws Exception {
        ControllableProcess process = new ControllableProcess(
                new ByteArrayInputStream("done\n".getBytes(StandardCharsets.UTF_8)), InputStream.nullInputStream());

        try (DefaultStreamSession session =
                openStream(process, plan(chunk -> {}, Duration.ofSeconds(Long.MAX_VALUE)))) {
            process.complete(0);

            StreamExit exit = session.onExit().get(2, TimeUnit.SECONDS);

            assertEquals(0, exit.exitCode().orElseThrow());
        }
    }

    @Test
    void exitDurationUsesInjectedMonotonicTimeAndClampsBackwardReadings() throws Exception {
        AtomicLong nanoTime = new AtomicLong(100);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultStreamSession stream = openStream(
                process,
                plan(chunk -> {}),
                diagnostics(),
                StreamSessionTestDependencies.withNanoTime(() -> nanoTime.getAndSet(50)));
        try {
            process.complete(0);

            assertEquals(Duration.ZERO, stream.onExit().get(1, TimeUnit.SECONDS).duration());
        } finally {
            stream.close();
        }
    }
}
