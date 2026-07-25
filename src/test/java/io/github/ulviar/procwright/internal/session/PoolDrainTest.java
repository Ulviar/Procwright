/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PoolDrainTest {

    @Test
    void viewsHaveIndependentCancellationAndShareOneSuccess() throws Exception {
        PoolDrain drain = newDrain();
        CompletableFuture<Void> cancelled = drain.view();
        CompletableFuture<Void> observed = drain.view();

        assertTrue(cancelled.cancel(true));
        claimAndPublish(drain, null);

        observed.get(1, TimeUnit.SECONDS);
        assertTrue(cancelled.isCancelled());
        assertFalse(observed.isCancelled());
    }

    @Test
    void failureIsPublishedWithoutStringConversion() {
        PoolDrain drain = newDrain();
        IllegalStateException failure = new IllegalStateException("close failed");
        CompletableFuture<Void> observed = drain.view();

        claimAndPublish(drain, failure);

        ExecutionException result = assertThrows(ExecutionException.class, () -> observed.get(1, TimeUnit.SECONDS));
        assertSame(failure, result.getCause());
    }

    @Test
    void outcomeCanOnlyBePublishedOnce() {
        PoolDrain drain = newDrain();
        PoolDrain.Publication publication = drain.claim(null);
        assertNotNull(publication);
        publication.publish();

        assertThrows(IllegalStateException.class, publication::publish);
    }

    @Test
    void terminalPublicationCanOnlyBeClaimedOnce() throws Exception {
        PoolDrain drain = newDrain();

        PoolDrain.Publication publication = drain.claim(null);
        assertNotNull(publication);
        assertNull(drain.claim(null));
        publication.publish();
        drain.view().get(1, TimeUnit.SECONDS);
    }

    private static void claimAndPublish(PoolDrain drain, Throwable failure) {
        PoolDrain.Publication publication = drain.claim(failure);
        assertNotNull(publication);
        publication.publish();
    }

    private static PoolDrain newDrain() {
        return new PoolDrain(new PoolTerminalPublisher.Capacity(1).reserve());
    }
}
