/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

final class SessionOutputOwnershipStateTest {

    @Test
    void helperModeRejectsRawAccessBeforeTheHelperStarts() {
        SessionOutputOwnership ownership = new SessionOutputOwnership(SessionOutputMode.EXPECT);

        assertThrows(
                IllegalStateException.class, () -> ownership.publicStream(new ByteArrayInputStream(new byte[] {1})));
    }

    @Test
    void helperModeAcceptsOnlyTheOwnerSelectedBeforeLaunch() {
        SessionOutputOwnership ownership = new SessionOutputOwnership(SessionOutputMode.LINE);

        assertThrows(IllegalStateException.class, () -> ownership.ensureOwnedBy(SessionOutputMode.PROTOCOL));
        ownership.claimHelper(SessionOutputMode.LINE);
        ownership.markHelperReady(SessionOutputMode.LINE);
        ownership.requireHelperReady(SessionOutputMode.LINE);
        assertThrows(IllegalStateException.class, () -> ownership.claimHelper(SessionOutputMode.LINE));
        assertThrows(IllegalStateException.class, () -> ownership.markHelperReady(SessionOutputMode.LINE));

        assertFalse(ownership.raw());
    }

    @Test
    void helperModeMustBeReadyBeforeConstructionCommits() {
        SessionOutputOwnership ownership = new SessionOutputOwnership(SessionOutputMode.EXPECT);

        assertThrows(IllegalStateException.class, () -> ownership.requireHelperReady(SessionOutputMode.EXPECT));
        ownership.claimHelper(SessionOutputMode.EXPECT);
        assertThrows(IllegalStateException.class, () -> ownership.requireHelperReady(SessionOutputMode.EXPECT));
    }

    @Test
    void rawModeRejectsHelperOwnership() {
        SessionOutputOwnership ownership = new SessionOutputOwnership(SessionOutputMode.RAW);

        assertThrows(IllegalStateException.class, () -> ownership.ensureOwnedBy(SessionOutputMode.EXPECT));
    }

    @Test
    void rawModeOwnsLifecycleOutputClose() {
        SessionOutputOwnership ownership = new SessionOutputOwnership(SessionOutputMode.RAW);

        assertTrue(ownership.raw());
    }

    @Test
    void helperModePreventsLifecycleFromClosingItsOutput() {
        SessionOutputOwnership ownership = new SessionOutputOwnership(SessionOutputMode.LINE);

        assertFalse(ownership.raw());
    }
}
