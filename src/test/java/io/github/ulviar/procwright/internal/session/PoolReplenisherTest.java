/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PoolReplenisherTest {

    @Test
    void concurrentEnsureCallsScheduleOnlyOneOwner() {
        AtomicBoolean needed = new AtomicBoolean(true);
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Runnable> owner = new AtomicReference<>();
        PoolReplenisher replenisher = replenisher(
                task -> {
                    starts.incrementAndGet();
                    owner.set(task);
                },
                needed::get,
                () -> {
                    needed.set(false);
                    return PoolReplenisher.Step.STOP;
                },
                backoff -> true);

        replenisher.ensureStarted();
        replenisher.ensureStarted();

        assertEquals(1, starts.get());
        owner.get().run();
        replenisher.ensureStarted();
        assertEquals(1, starts.get());
    }

    @Test
    void retryBackoffResetsAfterSuccess() {
        AtomicBoolean needed = new AtomicBoolean(true);
        ArrayDeque<PoolReplenisher.Step> steps = new ArrayDeque<>(List.of(
                PoolReplenisher.Step.RETRY,
                PoolReplenisher.Step.SUCCESS,
                PoolReplenisher.Step.RETRY,
                PoolReplenisher.Step.STOP));
        ArrayDeque<Duration> waits = new ArrayDeque<>();
        PoolReplenisher replenisher = replenisher(
                Runnable::run,
                needed::get,
                () -> {
                    PoolReplenisher.Step result = steps.removeFirst();
                    if (result == PoolReplenisher.Step.STOP) {
                        needed.set(false);
                    }
                    return result;
                },
                backoff -> {
                    waits.addLast(backoff);
                    return true;
                });

        replenisher.ensureStarted();

        assertEquals(
                List.of(Duration.ZERO, Duration.ofMillis(10), Duration.ZERO, Duration.ofMillis(10)),
                List.copyOf(waits));
    }

    @Test
    void stopRecheckCannotLoseDemandThatAppearsDuringShutdown() {
        AtomicInteger neededChecks = new AtomicInteger();
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        PoolReplenisher replenisher = replenisher(
                task -> {
                    starts.incrementAndGet();
                    task.run();
                },
                () -> neededChecks.incrementAndGet() < 3,
                () -> steps.incrementAndGet() == 1 ? PoolReplenisher.Step.STOP : stop(),
                backoff -> true);

        replenisher.ensureStarted();

        assertEquals(2, steps.get());
        assertTrue(neededChecks.get() >= 3);
        assertEquals(1, starts.get(), "the active owner must handle a shutdown-race retry without self-submission");
    }

    @Test
    void stopRecheckDoesNotWaitForItsOwnSaturatedDispatcherDomain() throws Exception {
        PoolLifecycleDispatcher dispatcher = new PoolLifecycleDispatcher(
                new BoundedTaskLimiter(1), Threading::start, "test-replenishment-recheck-", 1);
        AtomicBoolean needed = new AtomicBoolean(true);
        AtomicInteger steps = new AtomicInteger();
        PoolReplenisher replenisher = replenisher(
                task -> dispatcher.dispatch(task),
                needed::get,
                () -> {
                    if (steps.incrementAndGet() == 2) {
                        needed.set(false);
                    }
                    return PoolReplenisher.Step.STOP;
                },
                backoff -> true);

        replenisher.ensureStarted();

        dispatcher.whenIdle().get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, steps.get());
        assertEquals(1, dispatcher.availableAdmissions());
    }

    @Test
    void disabledReplenisherNeverSchedules() {
        AtomicBoolean started = new AtomicBoolean();
        PoolReplenisher replenisher = new PoolReplenisher(
                false,
                task -> started.set(true),
                () -> true,
                () -> PoolReplenisher.Step.SUCCESS,
                backoff -> true,
                failure -> {});

        replenisher.ensureStarted();

        assertFalse(started.get());
    }

    @Test
    void schedulingFailureIsFatalInsteadOfCreatingAnUnboundedFallbackOwner() {
        IllegalStateException schedulingFailure = new IllegalStateException("scheduler unavailable");
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        PoolReplenisher replenisher = new PoolReplenisher(
                true,
                task -> {
                    throw schedulingFailure;
                },
                () -> true,
                () -> {
                    steps.incrementAndGet();
                    return PoolReplenisher.Step.STOP;
                },
                backoff -> true,
                observedFailure::set);

        replenisher.ensureStarted();

        assertSame(schedulingFailure, observedFailure.get());
        assertEquals(0, steps.get());
    }

    private static PoolReplenisher replenisher(
            java.util.function.Consumer<Runnable> starter,
            java.util.function.BooleanSupplier needed,
            java.util.function.Supplier<PoolReplenisher.Step> step,
            PoolReplenisher.Waiter waiter) {
        return new PoolReplenisher(true, starter, needed, step, waiter, failure -> {});
    }

    private static PoolReplenisher.Step stop() {
        return PoolReplenisher.Step.STOP;
    }
}
