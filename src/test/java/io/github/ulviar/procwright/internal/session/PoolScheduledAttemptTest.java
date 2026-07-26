/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PoolScheduledAttemptTest {

    @Test
    void cancellationBeforeHandleAttachmentCancelsTheLateHandle() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        PoolScheduledAttempt attempt = new PoolScheduledAttempt(ignored -> runs.incrementAndGet());

        attempt.cancel();
        attempt.attach(cancellations::incrementAndGet);
        attempt.run();

        assertEquals(0, runs.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void cancellationAfterHandleAttachmentPreventsExecution() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        PoolScheduledAttempt attempt = new PoolScheduledAttempt(ignored -> runs.incrementAndGet());

        attempt.attach(cancellations::incrementAndGet);
        attempt.cancel();
        attempt.run();

        assertEquals(0, runs.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void handleAttachedAfterExecutionIsNotCancelled() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        PoolScheduledAttempt attempt = new PoolScheduledAttempt(ignored -> runs.incrementAndGet());

        attempt.run();
        attempt.run();
        attempt.attach(cancellations::incrementAndGet);
        attempt.cancel();

        assertEquals(1, runs.get());
        assertEquals(0, cancellations.get());
    }
}
