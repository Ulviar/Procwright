/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.session.SessionExit;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class SessionExitBarrierTest {

    @Test
    void waitsForProcessOutputAndEveryRegisteredHelper() throws Exception {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionExit> process = new CompletableFuture<>();
        CompletableFuture<Throwable> output = new CompletableFuture<>();
        SessionExitBarrier.Registration first = barrier.registerHelper();
        SessionExitBarrier.Registration second = barrier.registerHelper();
        barrier.observe(process, output);
        CompletableFuture<SessionExit> view = barrier.view();
        SessionExit expected = new SessionExit(OptionalInt.of(3), false);

        process.complete(expected);
        output.complete(null);
        first.complete();
        assertFalse(view.isDone());

        second.rollback();
        assertSame(expected, view.get(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> publisher.ownerCount() == 0));
    }

    @Test
    void combinesProcessAndInlineOutputFailureBeforePublication() {
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(1);
        SessionExitBarrier barrier = barrier(publisher);
        CompletableFuture<SessionExit> process = new CompletableFuture<>();
        CompletableFuture<Throwable> output = new CompletableFuture<>();
        AssertionError processFailure = new AssertionError("process");
        IllegalStateException outputFailure = new IllegalStateException("output");
        barrier.observe(process, output);

        process.completeExceptionally(processFailure);
        output.complete(outputFailure);

        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> barrier.view().get(1, TimeUnit.SECONDS));
        assertSame(processFailure, observed.getCause());
        assertSame(outputFailure, processFailure.getSuppressed()[0]);
        assertTrue(eventually(() -> publisher.ownerCount() == 0));
    }

    private static SessionExitBarrier barrier(BoundedLifecyclePublisher publisher) {
        BoundedLifecyclePublisher.Reservation reservation = publisher.reserve(1);
        BoundedLifecyclePublisher.Permit permit = reservation.takePermit();
        reservation.release();
        return new SessionExitBarrier(permit);
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() - deadline < 0) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }
}
