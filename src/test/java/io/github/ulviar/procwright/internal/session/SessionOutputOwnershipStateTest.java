/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SessionOutputOwnershipStateTest {

    @Test
    void lifecycleCloseClaimPreventsAHelperFromTakingOutputResponsibility() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();

        assertTrue(ownership.claimLifecycleClose());

        assertThrows(IllegalStateException.class, () -> ownership.claim("late helper"));
        assertFalse(ownership.claimLifecycleClose(), "lifecycle close responsibility must be claimed once");
    }

    @Test
    void helperClaimPreventsLifecycleFromClosingItsOutput() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();

        ownership.claim("helper");

        assertFalse(ownership.claimLifecycleClose());
    }

    @Test
    void cleanupSettlementSelectsHelperResponsibilityAtomically() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();
        ownership.claim("helper");

        assertEquals(SessionOutputOwnership.CloseResponsibility.OUTPUT_OWNER, ownership.settleCloseResponsibility());

        assertThrows(IllegalStateException.class, ownership::settleCloseResponsibility);
    }

    @Test
    void helperClaimAndCleanupSettlementHaveOneConsistentWinner() throws Exception {
        ExecutorService competitors = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 1_000; attempt++) {
                SessionOutputOwnership ownership = new SessionOutputOwnership();
                CountDownLatch start = new CountDownLatch(1);
                Future<Throwable> claim = competitors.submit(() -> {
                    start.await();
                    try {
                        ownership.claim("helper");
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                Future<SessionOutputOwnership.CloseResponsibility> settlement = competitors.submit(() -> {
                    start.await();
                    return ownership.settleCloseResponsibility();
                });

                start.countDown();
                Throwable claimFailure = claim.get(1, TimeUnit.SECONDS);
                boolean helperOwnsClose =
                        settlement.get(1, TimeUnit.SECONDS) == SessionOutputOwnership.CloseResponsibility.OUTPUT_OWNER;

                assertEquals(
                        claimFailure == null,
                        helperOwnsClose,
                        "claim and settlement selected different close owners at attempt " + attempt);
                if (claimFailure != null) {
                    assertTrue(claimFailure instanceof IllegalStateException);
                }
            }
        } finally {
            competitors.shutdownNow();
            assertTrue(competitors.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupSettlementRejectsALateHelperClaim() {
        SessionOutputOwnership ownership = new SessionOutputOwnership();
        assertTrue(ownership.claimLifecycleClose());

        assertEquals(SessionOutputOwnership.CloseResponsibility.LIFECYCLE, ownership.settleCloseResponsibility());

        assertThrows(IllegalStateException.class, () -> ownership.claim("late helper"));
    }
}
