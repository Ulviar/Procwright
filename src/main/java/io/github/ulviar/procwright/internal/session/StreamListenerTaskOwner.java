/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Owns one lazy, replaceable session-affine listener thread and at most one pending delivery. */
final class StreamListenerTaskOwner implements BoundedTaskRunner.TaskStarter, AutoCloseable {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();
    private static final IdleWaiter MONITOR_WAITER = Object::wait;
    private static final OwnerThreadFactory DEFAULT_THREAD_FACTORY = Threading::unstartedPlatformNonInheriting;

    private final IdleWaiter idleWaiter;
    private final OwnerThreadFactory threadFactory;
    private PendingDelivery pending;
    private Thread owner;
    private boolean closed;

    StreamListenerTaskOwner() {
        this(MONITOR_WAITER, DEFAULT_THREAD_FACTORY);
    }

    StreamListenerTaskOwner(IdleWaiter idleWaiter) {
        this(idleWaiter, DEFAULT_THREAD_FACTORY);
    }

    StreamListenerTaskOwner(IdleWaiter idleWaiter, OwnerThreadFactory threadFactory) {
        this.idleWaiter = Objects.requireNonNull(idleWaiter, "idleWaiter");
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    @Override
    public synchronized void start(String threadPrefix, Runnable task, BoundedTaskRunner.TaskRejection rejection) {
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(rejection, "rejection");
        if (closed) {
            throw new RejectedExecutionException("stream listener owner is closed");
        }
        if (pending != null) {
            throw new RejectedExecutionException("stream listener owner already has a pending delivery");
        }
        pending = new PendingDelivery(threadPrefix, task, rejection);
        if (owner == null) {
            try {
                startOwner(threadPrefix);
            } catch (RuntimeException | Error startFailure) {
                pending = null;
                notifyAll();
                throw startFailure;
            }
        }
        notifyAll();
    }

    private void startOwner(String threadPrefix) {
        Thread created = Objects.requireNonNull(
                threadFactory.unstarted(threadPrefix + "owner-" + OWNER_SEQUENCE.getAndIncrement(), this::runLoop),
                "threadFactory returned null");
        owner = created;
        try {
            created.start();
        } catch (RuntimeException | Error startFailure) {
            owner = null;
            throw startFailure;
        }
    }

    private void runLoop() {
        try {
            while (true) {
                PendingDelivery delivery;
                synchronized (this) {
                    while (pending == null && !closed) {
                        try {
                            idleWaiter.await(this);
                        } catch (InterruptedException interruption) {
                            if (closed && pending == null) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    delivery = pending;
                    pending = null;
                    if (delivery == null && closed) {
                        return;
                    }
                }
                try {
                    delivery.task().run();
                } catch (Throwable failure) {
                    Threading.reportUncaught(Thread.currentThread(), failure);
                }
            }
        } finally {
            recoverAfterOwnerExit(Thread.currentThread());
        }
    }

    private void recoverAfterOwnerExit(Thread exitedOwner) {
        PendingDelivery rejected = null;
        Throwable rejectionFailure = null;
        synchronized (this) {
            if (owner != exitedOwner) {
                return;
            }
            owner = null;
            if (pending != null) {
                try {
                    startOwner(pending.threadPrefix());
                } catch (RuntimeException | Error startFailure) {
                    rejected = pending;
                    pending = null;
                    closed = true;
                    rejectionFailure = startFailure;
                }
            }
            notifyAll();
        }
        if (rejected != null) {
            rejected.rejection().reject(rejectionFailure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (owner != null) {
            owner.interrupt();
        }
        notifyAll();
    }

    synchronized boolean awaitStopped(Duration timeout) throws InterruptedException {
        Duration checkedTimeout = DurationSupport.requireNonNegative(timeout, "timeout");
        long deadlineNanos = DurationSupport.deadlineFromNow(checkedTimeout);
        while (owner != null && owner != Thread.currentThread()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
        }
        return owner == null || !owner.isAlive();
    }

    @FunctionalInterface
    interface IdleWaiter {

        void await(Object monitor) throws InterruptedException;
    }

    @FunctionalInterface
    interface OwnerThreadFactory {

        Thread unstarted(String name, Runnable task);
    }

    private record PendingDelivery(String threadPrefix, Runnable task, BoundedTaskRunner.TaskRejection rejection) {}
}
