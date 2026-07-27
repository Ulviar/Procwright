/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.concurrent.atomic.AtomicBoolean;

/** Records whether a timed task crossed the conservative thread-start boundary. */
final class TaskStart {

    private final AtomicBoolean started = new AtomicBoolean();

    void markStarted() {
        started.set(true);
    }

    boolean started() {
        return started.get();
    }
}
