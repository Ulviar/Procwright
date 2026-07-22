/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DurationSupportTest {

    @Test
    void deadlineFromUsesNanoTimeCompatibleWraparound() {
        assertEquals(-1, DurationSupport.deadlineFrom(Long.MIN_VALUE, Duration.ofNanos(Long.MAX_VALUE)));
    }

    @Test
    void remainingNanosHandlesWrappedPositiveDeadline() {
        assertEquals(20, DurationSupport.remainingNanos(Long.MIN_VALUE + 9, Long.MAX_VALUE - 10));
    }

    @Test
    void remainingMillisHandlesNegativeCurrentTime() {
        assertEquals(
                TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE), DurationSupport.remainingMillis(-1, Long.MIN_VALUE));
    }

    @Test
    void saturatedMillisCapsHugeDuration() {
        assertEquals(Long.MAX_VALUE, DurationSupport.saturatedMillis(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void elapsedClampsBackwardReadingsToZero() {
        assertEquals(Duration.ZERO, DurationSupport.elapsed(100, 50));
    }

    @Test
    void elapsedHandlesSignedNanoTimeWraparound() {
        assertEquals(Duration.ofNanos(20), DurationSupport.elapsed(Long.MAX_VALUE - 10, Long.MIN_VALUE + 9));
    }

    @Test
    void elapsedSaturatesAnUnrepresentablePositiveDelta() {
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), DurationSupport.elapsed(Long.MIN_VALUE, Long.MAX_VALUE));
    }
}
