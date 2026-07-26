/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.ProcwrightException;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

final class PooledSessionExceptionTest {

    @Test
    void exceptionExposesStablePoolFailureState() {
        IllegalStateException cause = new IllegalStateException("worker failed");
        PooledSessionException failure =
                new PooledSessionException(PooledSessionException.Reason.WORKER_FAILED, "failed", cause);

        assertInstanceOf(ProcwrightException.class, failure);
        assertEquals(PooledSessionException.Reason.WORKER_FAILED, failure.reason());
        assertSame(cause, failure.getCause());
        assertThrows(NullPointerException.class, () -> new PooledSessionException(null, "failed"));
    }

    @Test
    void reasonTaxonomyCoversOnlyPoolLifecycleFailures() {
        assertEquals(
                EnumSet.of(
                        PooledSessionException.Reason.ACQUIRE_TIMEOUT,
                        PooledSessionException.Reason.CLOSED,
                        PooledSessionException.Reason.STARTUP_FAILED,
                        PooledSessionException.Reason.HOOK_TIMEOUT,
                        PooledSessionException.Reason.INTERRUPTED,
                        PooledSessionException.Reason.DRAIN_TIMEOUT,
                        PooledSessionException.Reason.WORKER_FAILED),
                EnumSet.allOf(PooledSessionException.Reason.class));
    }
}
