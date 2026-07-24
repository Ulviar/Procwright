/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RequestObservationTest {

    @Test
    void measuresActiveSegmentsAndExcludesAcquireWait() {
        AtomicLong now = new AtomicLong(100);
        AtomicReference<Boolean> successful = new AtomicReference<>();
        AtomicLong duration = new AtomicLong();
        RequestObservation observation = new RequestObservation(now::get, (result, nanos) -> {
            successful.set(result);
            duration.set(nanos);
        });

        now.set(150);
        observation.pauseForAcquire();
        now.set(1_150);
        observation.resumeAfterAcquire();
        now.set(1_220);
        observation.succeed();

        assertEquals(Boolean.TRUE, successful.get());
        assertEquals(120, duration.get());
    }

    @Test
    void completionIsIdempotentAndFirstOutcomeWins() {
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<Boolean> successful = new AtomicReference<>();
        RequestObservation observation = new RequestObservation(() -> 0, (result, ignored) -> {
            completions.incrementAndGet();
            successful.set(result);
        });

        observation.fail();
        observation.succeed();

        assertEquals(1, completions.get());
        assertEquals(Boolean.FALSE, successful.get());
    }

    @Test
    void stateTransitionsRejectDoublePauseAndInvalidResume() {
        RequestObservation observation = new RequestObservation(() -> 0, (result, nanos) -> {});

        assertThrows(IllegalStateException.class, observation::resumeAfterAcquire);
        observation.pauseForAcquire();
        assertThrows(IllegalStateException.class, observation::pauseForAcquire);
        observation.resumeAfterAcquire();
        observation.succeed();
        assertThrows(IllegalStateException.class, observation::pauseForAcquire);
        assertThrows(IllegalStateException.class, observation::resumeAfterAcquire);
    }

    @Test
    void nonMonotonicClockCannotProduceNegativeDuration() {
        AtomicLong now = new AtomicLong(100);
        AtomicLong duration = new AtomicLong(-1);
        RequestObservation observation = new RequestObservation(now::get, (result, nanos) -> duration.set(nanos));

        now.set(50);
        observation.succeed();

        assertEquals(0, duration.get());
    }
}
