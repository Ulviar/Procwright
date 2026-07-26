/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one stable stream reference and its exact-once logical close.
 *
 * @hidden
 */
public final class OwnedStream<T extends Closeable> {

    private final String name;
    private final T stream;
    private final AtomicBoolean closed = new AtomicBoolean();

    OwnedStream(String name, T stream) {
        this.name = Objects.requireNonNull(name, "name");
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    public T stream() {
        return stream;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Threading.start("procwright-process-" + name + "-close-", this::closeSilently);
        } catch (Throwable ignored) {
            // Logical close is complete. A late physical-close failure cannot change a scenario outcome.
        }
    }

    private void closeSilently() {
        try {
            stream.close();
        } catch (Throwable ignored) {
            // Physical close is best effort after ownership has been released.
        }
    }
}
