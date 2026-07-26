/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class PooledSessionFailuresTest {

    @Test
    void scenarioLabelsChangeMessagesWithoutChangingTaxonomy() {
        PooledSessionFailures line = new PooledSessionFailures("line");
        PooledSessionFailures protocol = new PooledSessionFailures("protocol");

        PooledSessionException lineClosed = (PooledSessionException) line.closed("ignored");
        PooledSessionException protocolClosed = (PooledSessionException) protocol.closed("ignored");
        PooledSessionException lineDrain = (PooledSessionException) line.drainTimeout(Duration.ofSeconds(1));
        PooledSessionException protocolDrain = (PooledSessionException) protocol.drainTimeout(Duration.ofSeconds(1));

        assertEquals(PooledSessionException.Reason.CLOSED, lineClosed.reason());
        assertEquals(lineClosed.reason(), protocolClosed.reason());
        assertTrue(lineClosed.getMessage().contains("line"));
        assertTrue(protocolClosed.getMessage().contains("protocol"));
        assertEquals(PooledSessionException.Reason.DRAIN_TIMEOUT, lineDrain.reason());
        assertEquals(lineDrain.reason(), protocolDrain.reason());
    }

    @Test
    void phaseMappingsPreserveCauseIdentity() {
        PooledSessionFailures failures = new PooledSessionFailures("line");
        InterruptedException interruption = new InterruptedException("interrupted");
        IllegalStateException workerFailure = new IllegalStateException("failed");

        PooledSessionException hookTimeout = failures.hookTimeout("hook timed out");
        PooledSessionException interrupted = failures.interrupted("interrupted", interruption);
        PooledSessionException failed = failures.workerFailure("worker failed", workerFailure);

        assertEquals(PooledSessionException.Reason.HOOK_TIMEOUT, hookTimeout.reason());
        assertEquals(PooledWorkerRetireReason.TIMEOUT, PooledSessionFailures.retireReason(hookTimeout));
        assertEquals(PooledSessionException.Reason.INTERRUPTED, interrupted.reason());
        assertSame(interruption, interrupted.getCause());
        assertEquals(PooledSessionException.Reason.WORKER_FAILED, failed.reason());
        assertSame(workerFailure, failed.getCause());
        assertEquals(PooledWorkerRetireReason.WORKER_FAILED, PooledSessionFailures.retireReason(failed));
    }

    @Test
    void aggregateExposureKeepsPublicReasonAndMessage() {
        PooledSessionFailures failures = new PooledSessionFailures("protocol");
        PooledSessionException primary = failures.hookTimeout("hook timed out");
        IllegalStateException aggregate = new IllegalStateException("aggregate");

        PooledSessionException exposed = (PooledSessionException) failures.exposeAggregate(primary, aggregate);

        assertNotSame(primary, exposed);
        assertEquals(primary.reason(), exposed.reason());
        assertEquals(primary.getMessage(), exposed.getMessage());
        assertSame(aggregate, exposed.getCause());
    }
}
