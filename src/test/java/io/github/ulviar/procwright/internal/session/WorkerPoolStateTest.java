/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static io.github.ulviar.procwright.internal.session.WorkerStartup.TerminalDecision.FACTORY_COMPLETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class WorkerPoolStateTest {

    @Test
    void fullWorkerLifecycleUpdatesPartitionAndMetricsAtomically() {
        StateFixture fixture = state(settings(1, 0, 0, 2));
        WorkerPoolState<String> state = fixture.state();
        WorkerStartupCoordinator.Reservation<String> reservation = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> worker = reservation.stateWorker();
        WorkerPoolState.Lease<String> firstLease;
        claimStartup(fixture, reservation);

        try (PoolStateEffects<String> effects = fixture.effects()) {
            firstLease = state.completeStartup(
                    reservation, new WorkerStartup.CreatedWorker<>("worker", 11), FACTORY_COMPLETED, effects);
        }

        assertThrows(IllegalStateException.class, reservation::stateWorker);
        assertThrows(IllegalStateException.class, reservation::preparedLease);
        assertMetrics(state.metrics(), 1, 0, 1, 0, 0, 1, 0);
        assertNull(state.recordRequestAndRetirementReason(firstLease));
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.releaseReusable(firstLease, effects);
        }
        assertMetrics(state.metrics(), 1, 1, 0, 0, 0, 1, 0);
        PooledSessionMetrics released = state.metrics();
        try (PoolStateEffects<String> effects = fixture.effects()) {
            assertThrows(IllegalStateException.class, () -> state.releaseReusable(firstLease, effects));
        }
        assertEquals(released, state.metrics());

        WorkerPoolState.AcquireResult<String> acquired;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            acquired = state.awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1), effects);
        }
        assertTrue(acquired instanceof WorkerPoolState.LeaseAcquired<String>);
        WorkerPoolState.LeaseAcquired<String> leased = (WorkerPoolState.LeaseAcquired<String>) acquired;
        WorkerPoolState.Lease<String> secondLease = leased.lease();
        assertSame(PooledWorkerRetireReason.MAX_REQUESTS, state.recordRequestAndRetirementReason(secondLease));
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.releaseReusable(secondLease, effects);
        }
        assertMetrics(state.metrics(), 1, 0, 0, 0, 1, 1, 0);

        FailureReport lateReport;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            lateReport = state.completeRetirement(worker, WorkerRetirement.Outcome.success(), null, effects);
        }

        assertNull(lateReport);
        PooledSessionMetrics completed = state.metrics();
        assertMetrics(completed, 0, 0, 0, 0, 0, 1, 1);
        assertEquals(1L, completed.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
    }

    @Test
    void demandAcquireRegistersItsPreparedReservationBeforeReturning() {
        StateFixture fixture = state(settings(1, 0, 0, 10));
        WorkerPoolState.AcquireResult<String> acquisition;

        try (PoolStateEffects<String> effects = fixture.effects()) {
            acquisition = fixture.state().awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1), effects);
        }

        assertTrue(acquisition instanceof WorkerPoolState.StartupReserved<String>);
        WorkerPoolState.StartupReserved<String> reserved = (WorkerPoolState.StartupReserved<String>) acquisition;
        assertNotNull(reserved.reservation().preparedLease());
        assertMetrics(fixture.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
    }

    @Test
    void reservationOwnerPreparationFailureCannotConsumePoolCapacity() {
        AssertionError failure = new AssertionError("reservation allocation failed");
        AtomicBoolean retirementOwnerPrepared = new AtomicBoolean();
        WorkerPoolState<String> state =
                new WorkerPoolState<>(new WorkerPoolPolicy(settings(1, 0, 0, 10)), new PoolTermination(), purpose -> {
                    new PoolWorker<>(noOpClose(), purpose);
                    retirementOwnerPrepared.set(true);
                    throw failure;
                });

        assertSame(failure, assertThrows(AssertionError.class, state::reserveWarmup));
        assertTrue(retirementOwnerPrepared.get());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void startupTransitionRejectsEffectsOwnedByAnotherPool() {
        StateFixture fixture = state(settings(1, 0, 0, 10));
        StateFixture other = state(settings(1, 0, 0, 10));
        WorkerPoolState.ReservationResult<String> result = fixture.state().reserveWarmup();
        assertTrue(result instanceof WorkerPoolState.SlotReserved<String>);
        WorkerStartupCoordinator.Reservation<String> reservation =
                ((WorkerPoolState.SlotReserved<String>) result).reservation();

        try (PoolStateEffects<String> unrelated = other.effects()) {
            assertThrows(IllegalArgumentException.class, () -> fixture.state()
                    .completeStartup(
                            reservation, new WorkerStartup.CreatedWorker<>("worker", 1), FACTORY_COMPLETED, unrelated));
        }

        assertMetrics(fixture.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
        try (PoolStateEffects<String> effects = fixture.effects()) {
            fixture.state().removeFailedStartup(reservation, effects);
        }
        assertMetrics(fixture.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void reservationCannotBeCompletedByAnotherPool() {
        StateFixture owner = state(settings(1, 0, 0, 10));
        StateFixture unrelated = state(settings(1, 0, 0, 10));
        WorkerStartupCoordinator.Reservation<String> reservation = reserve(owner, PoolWorker.StartupPurpose.DEMAND);

        try (PoolStateEffects<String> effects = unrelated.effects()) {
            assertThrows(
                    IllegalArgumentException.class, () -> unrelated.state().removeFailedStartup(reservation, effects));
        }

        assertMetrics(owner.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
        assertMetrics(unrelated.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
        try (PoolStateEffects<String> effects = owner.effects()) {
            owner.state().removeFailedStartup(reservation, effects);
        }
    }

    @Test
    void startupCannotCompleteBeforeItsLaunchIsClaimed() {
        StateFixture fixture = state(settings(1, 0, 0, 10));
        WorkerStartupCoordinator.Reservation<String> reservation = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);

        try (PoolStateEffects<String> effects = fixture.effects()) {
            assertThrows(IllegalStateException.class, () -> fixture.state()
                    .completeStartup(
                            reservation, new WorkerStartup.CreatedWorker<>("worker", 1), FACTORY_COMPLETED, effects));
        }

        assertMetrics(fixture.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
        try (PoolStateEffects<String> effects = fixture.effects()) {
            fixture.state().removeFailedStartup(reservation, effects);
        }
    }

    @Test
    void beginClosingTransformsOnlyWorkersOwnedByThePoolLifecycle() {
        StateFixture fixture = state(settings(4, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        WorkerStartupCoordinator.Reservation<String> queued = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        WorkerStartupCoordinator.Reservation<String> running = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        WorkerStartupCoordinator.Reservation<String> idle = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        WorkerStartupCoordinator.Reservation<String> leased = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        assertSame(
                WorkerStartupCoordinator.StartupClaim.RUN,
                state.claimStartup(running, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        assertFalse(running.canRollback());
        claimStartup(fixture, idle);
        claimStartup(fixture, leased);

        WorkerPoolState.Lease<String> idleLease;
        WorkerPoolState.Lease<String> activeLease;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            idleLease = state.completeStartup(
                    idle, new WorkerStartup.CreatedWorker<>("idle", 1), FACTORY_COMPLETED, effects);
        }
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.releaseReusable(idleLease, effects);
        }
        try (PoolStateEffects<String> effects = fixture.effects()) {
            activeLease = state.completeStartup(
                    leased, new WorkerStartup.CreatedWorker<>("leased", 1), FACTORY_COMPLETED, effects);
        }

        PoolTermination.FailureDisposition disposition;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            disposition = state.beginClose(null, effects);
        }

        assertSame(PoolTermination.FailureDisposition.NONE, disposition);
        assertSame(
                WorkerStartup.TerminalDecision.CLOSED,
                queued.stateWorker().startup().terminalDecision());
        assertSame(
                WorkerStartup.TerminalDecision.CLOSED,
                running.stateWorker().startup().terminalDecision());
        assertMetrics(state.metrics(), 3, 0, 1, 1, 1, 2, 0);
        assertSame("leased", activeLease.session());
    }

    @Test
    void retirementCompletionClaimsTheDrainExactlyOnce() throws Exception {
        StateFixture fixture = state(settings(1, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        state.finishConstruction();
        WorkerStartupCoordinator.Reservation<String> reservation = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> worker = reservation.stateWorker();
        claimStartup(fixture, reservation);
        WorkerPoolState.Lease<String> lease;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            lease = state.completeStartup(
                    reservation, new WorkerStartup.CreatedWorker<>("worker", 3), FACTORY_COMPLETED, effects);
        }
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.retire(lease, PooledWorkerRetireReason.CLOSED, effects);
        }
        assertMetrics(state.metrics(), 1, 0, 0, 0, 1, 1, 0);

        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.beginClose(null, effects);
        }
        PoolStateEffects<String> completionEffects = fixture.effects();
        assertNull(state.completeRetirement(worker, WorkerRetirement.Outcome.success(), null, completionEffects));

        assertFalse(state.terminationView().isDone());
        try (PoolStateEffects<String> secondClaim = fixture.effects()) {
            state.beginClose(null, secondClaim);
        }
        assertFalse(state.terminationView().isDone());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 1, 1);

        completionEffects.close();
        state.terminationView().get(1, TimeUnit.SECONDS);
    }

    @Test
    void fatalReplenishmentFactoryFailurePrecedesRemovalOfTheLastSlot() throws Exception {
        AssertionError fatal = new AssertionError("fatal startup");
        AtomicReference<WorkerStartup<String>> startupRef = new AtomicReference<>();
        StateFixture fixture = state(settings(1, 0, 1, 10), purpose -> {
            PoolWorker<String> worker = new PoolWorker<>(noOpClose(), purpose);
            WorkerStartup<String> startup = new WorkerStartup<>(
                    () -> {
                        throw fatal;
                    },
                    "worker-pool-state-fatal-",
                    completion -> {});
            worker.startup(startup);
            startupRef.set(startup);
            return worker;
        });
        WorkerPoolState<String> state = fixture.state();
        state.finishConstruction();
        WorkerStartupCoordinator.Reservation<String> reservation =
                reserve(fixture, PoolWorker.StartupPurpose.REPLENISHMENT);
        assertSame(
                WorkerStartupCoordinator.StartupClaim.RUN,
                state.claimStartup(reservation, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        assertFalse(reservation.canRollback());
        WorkerStartup<String> startup = startupRef.get();
        startup.start(new BoundedTaskPermit(new Semaphore(0)));
        ExecutionException startupFailure = assertThrows(
                ExecutionException.class, () -> startup.await(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        assertSame(fatal, startupFailure.getCause());

        boolean closedStartup;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            closedStartup = state.factoryFailed(reservation, fatal, effects);
        }

        assertFalse(closedStartup);
        assertEquals(1, state.metrics().failedStartups());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 0, 0);
        ExecutionException terminal = assertThrows(
                ExecutionException.class, () -> state.terminationView().get(1, TimeUnit.SECONDS));
        assertSame(fatal, terminal.getCause());
    }

    @Test
    void startupExitPathsKeepCreatedAndFailedCountersDistinct() {
        StateFixture preLaunch = state(settings(1, 0, 0, 10));
        WorkerStartupCoordinator.Reservation<String> cancelled = reserve(preLaunch, PoolWorker.StartupPurpose.DEMAND);
        WorkerPoolState.Lease<String> cancelledLease = cancelled.preparedLease();
        claimStartup(preLaunch, cancelled);

        try (PoolStateEffects<String> effects = preLaunch.effects()) {
            preLaunch.state().launchFailed(cancelled, effects);
        }

        assertThrows(IllegalStateException.class, cancelledLease::session);
        assertMetrics(preLaunch.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, preLaunch.state().metrics().failedStartups());

        StateFixture lateFailure = state(settings(1, 0, 0, 10));
        WorkerStartupCoordinator.Reservation<String> failed = reserve(lateFailure, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(lateFailure, failed);

        try (PoolStateEffects<String> effects = lateFailure.effects()) {
            lateFailure
                    .state()
                    .completeAbandonedStartup(
                            failed,
                            new WorkerStartup.LateCompletion<>(
                                    null,
                                    5,
                                    PooledWorkerRetireReason.STARTUP_TIMEOUT,
                                    new IllegalStateException("late failure"),
                                    null),
                            effects);
        }

        assertMetrics(lateFailure.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(1, lateFailure.state().metrics().failedStartups());

        StateFixture lateSuccess = state(settings(1, 0, 0, 10));
        WorkerStartupCoordinator.Reservation<String> succeeded = reserve(lateSuccess, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(lateSuccess, succeeded);

        try (PoolStateEffects<String> effects = lateSuccess.effects()) {
            lateSuccess
                    .state()
                    .completeAbandonedStartup(
                            succeeded,
                            new WorkerStartup.LateCompletion<>(
                                    "late worker", 7, PooledWorkerRetireReason.STARTUP_TIMEOUT, null, null),
                            effects);
        }

        assertMetrics(lateSuccess.state().metrics(), 1, 0, 0, 0, 1, 1, 0);
        assertEquals(1, lateSuccess.state().metrics().failedStartups());
    }

    @Test
    void metricsWaitCannotLoseAChangePublishedWhileThePredicateRuns() throws Exception {
        WorkerPoolState<String> state = state(settings(1, 0, 0, 10)).state();
        CountDownLatch predicateStarted = new CountDownLatch(1);
        CountDownLatch changePublished = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean();

        Thread waiter = new Thread(() -> {
            try {
                result.set(state.awaitMetrics(
                        snapshot -> {
                            predicateStarted.countDown();
                            try {
                                changePublished.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                            return snapshot.failedRequests() == 1;
                        },
                        Duration.ofSeconds(1)));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
        waiter.start();

        assertTrue(predicateStarted.await(1, TimeUnit.SECONDS));
        state.recordRequest(false, 7);
        changePublished.countDown();
        waiter.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(waiter.isAlive());
        assertTrue(result.get());
    }

    @Test
    void acceptedFailuresPublishAsAStableSnapshotWithoutTouchingTheirMonitors() throws Exception {
        verifyAcceptedFailurePublicationWhileHolding(true);
        verifyAcceptedFailurePublicationWhileHolding(false);
    }

    private static void verifyAcceptedFailurePublicationWhileHolding(boolean holdPrimary) throws Exception {
        StateFixture fixture = state(settings(1, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        WorkerStartupCoordinator.Reservation<String> reservation = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> worker = reservation.stateWorker();
        claimStartup(fixture, reservation);
        WorkerPoolState.Lease<String> lease;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            lease = state.completeStartup(
                    reservation, new WorkerStartup.CreatedWorker<>("worker", 1), FACTORY_COMPLETED, effects);
        }
        AssertionError primary = new AssertionError("primary");
        IllegalStateException secondary = new IllegalStateException("secondary");
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.beginClose(primary, effects);
        }
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try (var monitor = hold(holdPrimary ? primary : secondary)) {
            monitor.verifyHeld();
            Future<?> closing = executor.submit(() -> {
                try (PoolStateEffects<String> effects = fixture.effects()) {
                    state.beginClose(secondary, effects);
                }
            });
            closing.get(1, TimeUnit.SECONDS);

            executor.submit(state::metrics).get(1, TimeUnit.SECONDS);
            Future<?> retirement = executor.submit(() -> {
                try (PoolStateEffects<String> effects = fixture.effects()) {
                    state.retire(lease, PooledWorkerRetireReason.CLOSED, effects);
                }
                try (PoolStateEffects<String> effects = fixture.effects()) {
                    state.completeRetirement(worker, WorkerRetirement.Outcome.success(), null, effects);
                }
            });
            retirement.get(1, TimeUnit.SECONDS);
            assertEquals(0, state.metrics().size());
            ExecutionException terminal = assertThrows(
                    ExecutionException.class, () -> state.terminationView().get(1, TimeUnit.SECONDS));
            assertSame(primary, terminal.getCause().getCause());
            assertEquals(
                    java.util.List.of(secondary),
                    java.util.List.of(terminal.getCause().getSuppressed()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, primary.getSuppressed().length);
    }

    private static StateFixture state(WorkerPoolSettings<?> settings) {
        return state(settings, WorkerPoolStateTest::startingWorker);
    }

    private static StateFixture state(
            WorkerPoolSettings<?> settings, Function<PoolWorker.StartupPurpose, PoolWorker<String>> workers) {
        WorkerRetirementCoordinator<String> retirements = new WorkerRetirementCoordinator<>(
                Runnable::run, (worker, outcome) -> null, (worker, failure) -> {}, report -> {});
        WorkerPoolState<String> state = new WorkerPoolState<>(
                new WorkerPoolPolicy(settings),
                new PoolTermination(),
                purpose -> new WorkerStartupCoordinator.Reservation<>(workers.apply(purpose)));
        return new StateFixture(state, retirements);
    }

    private static WorkerStartupCoordinator.Reservation<String> reserve(
            StateFixture fixture, PoolWorker.StartupPurpose purpose) {
        WorkerStartupCoordinator.Reservation<String> reservation =
                switch (purpose) {
                    case DEMAND -> {
                        WorkerPoolState.AcquireResult<String> result;
                        try (PoolStateEffects<String> effects = fixture.effects()) {
                            result = fixture.state()
                                    .awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1), effects);
                        }
                        yield ((WorkerPoolState.StartupReserved<String>) result).reservation();
                    }
                    case WARMUP -> {
                        WorkerPoolState.ReservationResult<String> result =
                                fixture.state().reserveWarmup();
                        yield ((WorkerPoolState.SlotReserved<String>) result).reservation();
                    }
                    case REPLENISHMENT -> fixture.state().tryReserveReplenishment();
                };
        assertNotNull(reservation);
        assertSame(purpose, reservation.purpose());
        return reservation;
    }

    private static PoolWorker<String> startingWorker(PoolWorker.StartupPurpose purpose) {
        PoolWorker<String> worker = new PoolWorker<>(noOpClose(), purpose);
        worker.startup(new WorkerStartup<>(() -> "unused", "worker-pool-state-test-", completion -> {}));
        return worker;
    }

    private static void claimStartup(StateFixture fixture, WorkerStartupCoordinator.Reservation<String> reservation) {
        assertSame(
                WorkerStartupCoordinator.StartupClaim.RUN,
                fixture.state().claimStartup(reservation, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
    }

    private static WorkerRetirement.Action<String> noOpClose() {
        return session -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
    }

    private static void assertMetrics(
            PooledSessionMetrics snapshot,
            int size,
            int idle,
            int leased,
            int starting,
            int retiring,
            long created,
            long retired) {
        assertEquals(size, snapshot.size());
        assertEquals(idle, snapshot.idle());
        assertEquals(leased, snapshot.leased());
        assertEquals(starting, snapshot.starting());
        assertEquals(retiring, snapshot.retiring());
        assertEquals(size, idle + leased + starting + retiring);
        assertEquals(created, snapshot.created());
        assertEquals(retired, snapshot.retired());
    }

    private record StateFixture(WorkerPoolState<String> state, WorkerRetirementCoordinator<String> retirements) {

        private PoolStateEffects<String> effects() {
            return new PoolStateEffects<>(state, retirements);
        }
    }

    private static WorkerPoolSettings<Object> settings(
            int maxSize, int warmupSize, int minIdle, int maxRequestsPerWorker) {
        return WorkerPoolSettings.defaults()
                .withMaxSize(maxSize)
                .withWarmupSize(warmupSize)
                .withMinIdle(minIdle)
                .withMaxRequestsPerWorker(maxRequestsPerWorker);
    }
}
