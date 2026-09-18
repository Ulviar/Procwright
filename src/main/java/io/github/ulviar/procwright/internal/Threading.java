/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @hidden
 */
public final class Threading {

    private Threading() {}

    public static ExecutorService newTaskExecutor(String namePrefix) {
        return Executors.newThreadPerTaskExecutor(threadFactory(namePrefix));
    }

    public static Thread start(String namePrefix, Runnable task) {
        Thread thread = unstarted(namePrefix, task);
        thread.start();
        return thread;
    }

    public static Thread unstarted(String namePrefix, Runnable task) {
        Objects.requireNonNull(task, "task");
        return threadFactory(namePrefix).newThread(task);
    }

    /** Creates a daemon platform thread without inheriting the creator's inheritable thread-local state. */
    public static Thread unstartedPlatformNonInheriting(String name, Runnable task) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
        return Thread.ofPlatform()
                .daemon(true)
                .inheritInheritableThreadLocals(false)
                .name(name)
                .unstarted(task);
    }

    public static void reportUncaught(Thread thread, Throwable failure) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(failure, "failure");
        Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
        if (handler == null) {
            handler = Thread.getDefaultUncaughtExceptionHandler();
        }
        if (handler != null) {
            try {
                handler.uncaughtException(thread, failure);
            } catch (Throwable ignored) {
                // The JVM also ignores failures thrown by an uncaught-exception handler.
            }
        }
    }

    private static ThreadFactory threadFactory(String namePrefix) {
        Objects.requireNonNull(namePrefix, "namePrefix");
        return Thread.ofVirtual()
                .inheritInheritableThreadLocals(false)
                .name(namePrefix, 0L)
                .factory();
    }
}
