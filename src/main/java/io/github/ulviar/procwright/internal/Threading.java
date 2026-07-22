/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * @hidden
 */
public final class Threading {

    private static final Method THREAD_OF_VIRTUAL = method(Thread.class, "ofVirtual");
    private static final Method EXECUTORS_NEW_THREAD_PER_TASK_EXECUTOR =
            method(Executors.class, "newThreadPerTaskExecutor", ThreadFactory.class);
    private static final Class<?> THREAD_BUILDER = type("java.lang.Thread$Builder");
    private static final Method THREAD_BUILDER_NAME =
            THREAD_BUILDER == null ? null : method(THREAD_BUILDER, "name", String.class, long.class);
    private static final Method THREAD_BUILDER_FACTORY =
            THREAD_BUILDER == null ? null : method(THREAD_BUILDER, "factory");

    private Threading() {}

    public static ExecutorService newTaskExecutor(String namePrefix) {
        ThreadFactory factory = threadFactory(namePrefix);
        if (virtualThreadingAvailable()) {
            return newThreadPerTaskExecutor(factory);
        }
        return Executors.newCachedThreadPool(factory);
    }

    public static ExecutorService newBoundedExecutor(String namePrefix, int parallelism, int queueCapacity) {
        return newReusableBoundedExecutor(namePrefix, parallelism, queueCapacity);
    }

    public static ThreadPoolExecutor newReusableBoundedExecutor(String namePrefix, int parallelism, int queueCapacity) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        int priority = Thread.currentThread().getPriority();
        return newReusableBoundedExecutor(namePrefix, parallelism, queueCapacity, (threadName, task) -> {
            Thread thread = unstartedPlatformNonInheriting(threadName, task);
            thread.setContextClassLoader(contextClassLoader);
            thread.setPriority(priority);
            return thread;
        });
    }

    public static ThreadPoolExecutor newReusableBoundedExecutor(
            String namePrefix, int parallelism, int queueCapacity, BiFunction<String, Runnable, Thread> threadFactory) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        Objects.requireNonNull(namePrefix, "namePrefix");
        Objects.requireNonNull(threadFactory, "threadFactory");
        AtomicLong sequence = new AtomicLong();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                task -> {
                    String threadPrefix = namePrefix + sequence.getAndIncrement() + "-";
                    Thread thread = Objects.requireNonNull(
                            threadFactory.apply(threadPrefix, task), "threadFactory returned null");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
        Thread thread = new Thread(null, task, name, 0, false);
        thread.setDaemon(true);
        return thread;
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
        if (virtualThreadingAvailable()) {
            return virtualThreadFactory(namePrefix);
        }
        return platformThreadFactory(namePrefix);
    }

    private static boolean virtualThreadingAvailable() {
        return THREAD_OF_VIRTUAL != null
                && EXECUTORS_NEW_THREAD_PER_TASK_EXECUTOR != null
                && THREAD_BUILDER_NAME != null
                && THREAD_BUILDER_FACTORY != null;
    }

    private static ExecutorService newThreadPerTaskExecutor(ThreadFactory factory) {
        try {
            return (ExecutorService) EXECUTORS_NEW_THREAD_PER_TASK_EXECUTOR.invoke(null, factory);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access virtual-thread executor factory", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Could not create virtual-thread executor", exception.getCause());
        }
    }

    private static ThreadFactory virtualThreadFactory(String namePrefix) {
        try {
            Object builder = THREAD_OF_VIRTUAL.invoke(null);
            Object namedBuilder = THREAD_BUILDER_NAME.invoke(builder, namePrefix, 0L);
            return (ThreadFactory) THREAD_BUILDER_FACTORY.invoke(namedBuilder);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access virtual-thread factory", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Could not create virtual-thread factory", exception.getCause());
        }
    }

    private static ThreadFactory platformThreadFactory(String namePrefix) {
        AtomicLong sequence = new AtomicLong();
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        int priority = Thread.currentThread().getPriority();
        return task -> {
            Thread thread = unstartedPlatformNonInheriting(namePrefix + sequence.getAndIncrement(), task);
            thread.setContextClassLoader(contextClassLoader);
            thread.setPriority(priority);
            return thread;
        };
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }
}
