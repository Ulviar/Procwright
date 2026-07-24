/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ThreadingTest {

    @Test
    void createdThreadDoesNotInheritCallerThreadLocalState() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>();
        inherited.set("caller-state");
        try {
            Thread thread = Threading.unstarted("test-non-inheriting-", () -> observed.set(inherited.get()));

            thread.start();
            thread.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(thread.isAlive());
            assertNull(observed.get());
        } finally {
            inherited.remove();
        }
    }
}
