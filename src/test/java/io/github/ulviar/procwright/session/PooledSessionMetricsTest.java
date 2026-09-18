/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class PooledSessionMetricsTest {

    @Test
    void snapshotOwnsConsistentPoolAndRetirementCounts() {
        PooledSessionMetrics metrics =
                metrics(1, 1, 0, 0, 0, 2, 1, 0, Map.of(PooledWorkerRetireReason.MAX_REQUESTS, 1L));

        assertEquals(1, metrics.size());
        assertEquals(2, metrics.created());
        assertEquals(1, metrics.retired());
        assertEquals(Map.of(PooledWorkerRetireReason.MAX_REQUESTS, 1L), metrics.retireReasons());
    }

    @Test
    void snapshotCopiesRetirementCounts() {
        EnumMap<PooledWorkerRetireReason, Long> reasons = new EnumMap<>(PooledWorkerRetireReason.class);
        reasons.put(PooledWorkerRetireReason.CLOSED, 1L);

        PooledSessionMetrics metrics = metrics(1, 1, 0, 0, 0, 2, 1, 0, reasons);
        reasons.put(PooledWorkerRetireReason.TIMEOUT, 1L);

        assertEquals(Map.of(PooledWorkerRetireReason.CLOSED, 1L), metrics.retireReasons());
        assertThrows(
                UnsupportedOperationException.class,
                () -> metrics.retireReasons().put(PooledWorkerRetireReason.TIMEOUT, 1L));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("negativeScalarFields")
    void snapshotRejectsEveryNegativeScalar(int index, String field) {
        long[] scalars = new long[14];
        scalars[index] = -1;

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> metrics(scalars, Map.of()));

        assertEquals(field + " must not be negative", failure.getMessage());
    }

    @Test
    void snapshotAccountsForEachLiveWorkerState() {
        PooledSessionMetrics starting = metrics(1, 0, 0, 1, 0, 0, 0, 0, Map.of());
        PooledSessionMetrics leased = metrics(1, 0, 1, 0, 0, 1, 0, 0, Map.of());
        PooledSessionMetrics retiring = metrics(1, 0, 0, 0, 1, 1, 0, 0, Map.of());

        assertEquals(1, starting.starting());
        assertEquals(0, starting.created());
        assertEquals(1, leased.leased());
        assertEquals(1, leased.created());
        assertEquals(1, retiring.retiring());
        assertEquals(1, retiring.created());
    }

    @Test
    void snapshotRejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class, () -> metrics(2, 1, 0, 0, 0, 1, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> metrics(1, 1, 0, 0, 0, 0, 0, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> metrics(0, 0, 0, 0, 0, 1, 1, 2, Map.of(PooledWorkerRetireReason.CLOSED, 1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> metrics(0, 0, 0, 0, 0, 1, 1, 0, Map.of(PooledWorkerRetireReason.CLOSED, -1L)));
        assertThrows(IllegalArgumentException.class, () -> metrics(0, 0, 0, 0, 0, 1, 1, 0, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> metrics(
                        0,
                        0,
                        0,
                        0,
                        0,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        0,
                        Map.of(PooledWorkerRetireReason.CLOSED, Long.MAX_VALUE, PooledWorkerRetireReason.TIMEOUT, 1L)));
        assertThrows(NullPointerException.class, () -> metrics(0, 0, 0, 0, 0, 0, 0, 0, null));
    }

    @Test
    void snapshotRejectsNullRetirementKeysAndValues() {
        Map<PooledWorkerRetireReason, Long> nullKey = new HashMap<>();
        nullKey.put(null, 0L);
        Map<PooledWorkerRetireReason, Long> nullValue = new HashMap<>();
        nullValue.put(PooledWorkerRetireReason.CLOSED, null);

        assertThrows(NullPointerException.class, () -> metrics(0, 0, 0, 0, 0, 0, 0, 0, nullKey));
        assertThrows(NullPointerException.class, () -> metrics(0, 0, 0, 0, 0, 0, 0, 0, nullValue));
    }

    private static Stream<Arguments> negativeScalarFields() {
        return Stream.of(
                Arguments.of(0, "size"),
                Arguments.of(1, "idle"),
                Arguments.of(2, "leased"),
                Arguments.of(3, "starting"),
                Arguments.of(4, "retiring"),
                Arguments.of(5, "created"),
                Arguments.of(6, "retired"),
                Arguments.of(7, "completedRequests"),
                Arguments.of(8, "failedRequests"),
                Arguments.of(9, "failedStartups"),
                Arguments.of(10, "failedWorkerCloses"),
                Arguments.of(11, "totalAcquireWaitNanos"),
                Arguments.of(12, "totalRequestDurationNanos"),
                Arguments.of(13, "totalWorkerStartupNanos"));
    }

    private static PooledSessionMetrics metrics(long[] values, Map<PooledWorkerRetireReason, Long> retireReasons) {
        return new PooledSessionMetrics(
                (int) values[0],
                (int) values[1],
                (int) values[2],
                (int) values[3],
                (int) values[4],
                values[5],
                values[6],
                values[7],
                values[8],
                values[9],
                values[10],
                values[11],
                values[12],
                values[13],
                retireReasons);
    }

    private static PooledSessionMetrics metrics(
            int size,
            int idle,
            int leased,
            int starting,
            int retiring,
            long created,
            long retired,
            long failedWorkerCloses,
            Map<PooledWorkerRetireReason, Long> retireReasons) {
        return new PooledSessionMetrics(
                size,
                idle,
                leased,
                starting,
                retiring,
                created,
                retired,
                0,
                0,
                0,
                failedWorkerCloses,
                0,
                0,
                0,
                retireReasons);
    }
}
