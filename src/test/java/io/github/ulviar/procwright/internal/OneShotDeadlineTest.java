/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class OneShotDeadlineTest {

    @Test
    void completedFutureRemainsObservableAfterDeadline() throws Exception {
        OneShotDeadline deadline = expiredDeadline();

        String result = deadline.await(CompletableFuture.completedFuture("captured"));

        assertEquals("captured", result);
    }

    @Test
    void incompleteFutureIsRejectedAfterDeadline() {
        OneShotDeadline deadline = expiredDeadline();
        FutureTask<String> incomplete = new FutureTask<>(() -> "never started");

        assertThrows(TimeoutException.class, () -> deadline.await(incomplete));
    }

    private static OneShotDeadline expiredDeadline() {
        OneShotDeadline deadline = OneShotDeadline.start(Duration.ofNanos(1));
        while (deadline.remainingNanos() > 0) {
            Thread.onSpinWait();
        }
        return deadline;
    }
}
