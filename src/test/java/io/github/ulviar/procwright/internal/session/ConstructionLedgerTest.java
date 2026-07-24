/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import org.junit.jupiter.api.Test;

final class ConstructionLedgerTest {

    @Test
    void commitDrainsPreDecisionReportsAndRoutesLaterReportsImmediately() {
        ConstructionLedger ledger = new ConstructionLedger();
        FailureReport early = report("early");
        FailureReport late = report("late");

        assertNull(ledger.route(early));
        assertEquals(java.util.List.of(early), ledger.commit());
        assertSame(late, ledger.route(late));
        assertThrows(IllegalStateException.class, ledger::commit);
    }

    @Test
    void failureDrainsPreDecisionReportsAndRemainsObservable() {
        ConstructionLedger ledger = new ConstructionLedger();
        FailureReport early = report("early");
        ledger.record(early);

        assertEquals(java.util.List.of(early), ledger.fail());
        assertTrue(ledger.failed());
        FailureReport late = report("late");
        assertSame(late, ledger.route(late));
        assertThrows(IllegalStateException.class, () -> ledger.record(report("invalid")));
    }

    private static FailureReport report(String message) {
        return new FailureReport(
                BoundedFailureReporter.captureFailureTarget(Thread.currentThread()),
                new IllegalStateException(message));
    }
}
