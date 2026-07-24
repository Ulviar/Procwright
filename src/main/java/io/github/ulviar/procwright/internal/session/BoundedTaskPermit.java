/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Releases one bounded-task admission exactly once. */
final class BoundedTaskPermit implements AutoCloseable {

    private final Semaphore permits;
    private final AtomicBoolean closed = new AtomicBoolean();

    BoundedTaskPermit(Semaphore permits) {
        this.permits = permits;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            permits.release();
        }
    }
}
