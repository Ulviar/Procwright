/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ThreadingTest {

    @Test
    void lifecycleThreadsAreVirtualNamedDaemonAndDoNotInheritCallerState() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
        inherited.set("caller-state");
        try {
            Thread thread = Threading.unstarted("test-non-inheriting-", () -> observed.set(inherited.get()));
            assertTrue(thread.isVirtual());
            assertTrue(thread.isDaemon());
            assertTrue(thread.getName().startsWith("test-non-inheriting-"));
            assertSame(ClassLoader.getSystemClassLoader(), thread.getContextClassLoader());
            thread.start();
            assertTrue(thread.join(Duration.ofSeconds(1)));
            assertNull(observed.get());
        } finally {
            inherited.remove();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void taskExecutorUsesNonInheritingVirtualThreads() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
        inherited.set("caller-state");
        var executor = Threading.newTaskExecutor("test-task-");
        try {
            var result = executor.submit(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                assertTrue(Thread.currentThread().isDaemon());
                assertTrue(Thread.currentThread().getName().startsWith("test-task-"));
                assertNull(inherited.get());
                assertSame(
                        ClassLoader.getSystemClassLoader(),
                        Thread.currentThread().getContextClassLoader());
            });
            result.get(1, TimeUnit.SECONDS);
        } finally {
            inherited.remove();
            Thread.currentThread().setContextClassLoader(original);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void isolatedWorkersRemainNonInheritingPlatformDaemons() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
        inherited.set("caller-state");
        try {
            Thread thread =
                    Threading.unstartedPlatformNonInheriting("test-isolation", () -> observed.set(inherited.get()));
            assertFalse(thread.isVirtual());
            assertTrue(thread.isDaemon());
            assertSame(ClassLoader.getSystemClassLoader(), thread.getContextClassLoader());
            thread.start();
            assertTrue(thread.join(Duration.ofSeconds(1)));
            assertNull(observed.get());
        } finally {
            inherited.remove();
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
