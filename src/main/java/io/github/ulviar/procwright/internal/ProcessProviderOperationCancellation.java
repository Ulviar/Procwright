/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;

/** Delivers caller cancellation before or after the disposable worker binds. */
final class ProcessProviderOperationCancellation {

    private Thread activeThread;
    private boolean interruptRequested;

    synchronized void bind(Thread thread) {
        activeThread = Objects.requireNonNull(thread, "thread");
        if (interruptRequested) {
            thread.interrupt();
        }
    }

    synchronized void unbind(Thread thread) {
        if (activeThread == thread) {
            activeThread = null;
        }
    }

    synchronized void interrupt() {
        interruptRequested = true;
        if (activeThread != null) {
            activeThread.interrupt();
        }
    }
}
