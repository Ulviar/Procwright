/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RequestFailureTrackerTest {

    @Test
    void retainsAndReturnsFirstFailure() {
        RequestFailureTracker<IllegalStateException> tracker = new RequestFailureTracker<>();
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException later = new IllegalStateException("later");

        assertSame(first, tracker.record(first));
        assertSame(first, tracker.record(later));
        assertSame(first, tracker.failure());
        assertSame(first, assertThrows(IllegalStateException.class, tracker::throwIfFailed));
    }

    @Test
    void terminalSelectionReplacesAnEarlierNonterminalFailure() {
        RequestFailureTracker<IllegalStateException> tracker = new RequestFailureTracker<>();
        IllegalStateException nonterminal = new IllegalStateException("closed");
        IllegalStateException terminal = new IllegalStateException("decode failed");

        tracker.record(nonterminal);

        assertSame(terminal, tracker.replaceWithTerminal(terminal));
        assertSame(terminal, tracker.failure());
        assertSame(terminal, assertThrows(IllegalStateException.class, tracker::throwIfFailed));
    }
}
