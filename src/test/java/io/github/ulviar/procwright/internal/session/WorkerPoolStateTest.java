/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.WorkerStartup.TerminalDecision.FACTORY_COMPLETED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class WorkerPoolStateTest {

    @Test
    void fullWorkerLifecycleUpdatesPartitionAndMetricsAtomically() {
        StateFixture fixture = state(new Options(1, 0, 0, 2));
        WorkerPoolState<String> state = fixture.state();
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        WorkerStartupCoordinator.Reservation<String> reservation =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> worker = reservation.stateWorker();
        WorkerPoolState.Lease<String> firstLease;

        try (PoolStateEffects<String> effects = reservation.effects()) {
            firstLease = state.completeStartup(
                    reservation, new WorkerStartup.CreatedWorker<>("worker", 11), FACTORY_COMPLETED, effects);
        }

        assertThrows(IllegalStateException.class, reservation::stateWorker);
        assertThrows(IllegalStateException.class, reservation::preparedLease);
        assertThrows(IllegalStateException.class, reservation::effects);
        assertMetrics(state.metrics(), 1, 0, 1, 0, 0, 1, 0);
        assertNull(state.recordRequestAndRetirementReason(firstLease));
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.releaseReusable(firstLease, effects);
        }
        assertMetrics(state.metrics(), 1, 1, 0, 0, 0, 1, 0);
        PoolMetrics.Snapshot released = state.metrics();
        try (PoolStateEffects<String> effects = fixture.effects()) {
            assertThrows(IllegalStateException.class, () -> state.releaseReusable(firstLease, effects));
        }
        assertEquals(released, state.metrics());

        WorkerPoolState.AcquireResult<String> acquired;
        try (PoolStateEffects<String> effects = fixture.effects()) {
            acquired = state.awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1), effects);
        }
        assertSame(WorkerPoolState.AcquireStatus.LEASED, acquired.status());
        WorkerPoolState.Lease<String> secondLease = acquired.lease();
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
        PoolMetrics.Snapshot completed = state.metrics();
        assertMetrics(completed, 0, 0, 0, 0, 0, 1, 1);
        assertEquals(1L, completed.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
        assertEquals(1, admissions.availablePermits());
    }

    @Test
    void demandAcquireRegistersItsPreparedReservationBeforeReturning() {
        StateFixture fixture = state(new Options(1, 0, 0, 10));
        WorkerPoolState.AcquireResult<String> acquisition;

        try (PoolStateEffects<String> effects = fixture.effects()) {
            acquisition = fixture.state().awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1), effects);
        }

        assertSame(WorkerPoolState.AcquireStatus.RESERVED, acquisition.status());
        assertNotNull(acquisition.reservation().preparedLease());
        assertMetrics(fixture.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
    }

    @Test
    void reservationOwnerPreparationFailureCannotConsumePoolCapacity() {
        AssertionError failure = new AssertionError("reservation allocation failed");
        AtomicBoolean retirementOwnerPrepared = new AtomicBoolean();
        WorkerPoolState<String> state = new WorkerPoolState<>(
                new WorkerPoolPolicy(new Options(1, 0, 0, 10)),
                new PoolTermination(new PoolTerminalPublisher.Capacity(1).reserve()),
                () -> {
                    new PoolWorker<>(noOpClose());
                    retirementOwnerPrepared.set(true);
                    throw failure;
                });

        assertSame(failure, assertThrows(AssertionError.class, state::reserve));
        assertTrue(retirementOwnerPrepared.get());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void startupTransitionRejectsAnotherEffectsOwnerFromTheSamePool() {
        StateFixture fixture = state(new Options(1, 0, 0, 10));
        WorkerPoolState.ReservationResult<String> result = fixture.state().reserve();
        WorkerStartupCoordinator.Reservation<String> reservation = result.reservation();

        try (PoolStateEffects<String> unrelated = fixture.effects()) {
            assertThrows(IllegalArgumentException.class, () -> fixture.state()
                    .completeStartup(
                            reservation, new WorkerStartup.CreatedWorker<>("worker", 1), FACTORY_COMPLETED, unrelated));
        }

        assertMetrics(fixture.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
        try (PoolStateEffects<String> effects = reservation.effects()) {
            fixture.state().launchFailed(reservation, effects);
        }
        assertMetrics(fixture.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void beginClosingTransformsOnlyWorkersOwnedByThePoolLifecycle() {
        StateFixture fixture = state(new Options(4, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(4);
        WorkerStartupCoordinator.Reservation<String> queued =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.DEMAND);
        WorkerStartupCoordinator.Reservation<String> running =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.DEMAND);
        WorkerStartupCoordinator.Reservation<String> idle =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.DEMAND);
        WorkerStartupCoordinator.Reservation<String> leased =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.DEMAND);
        assertSame(
                WorkerStartupCoordinator.StartupClaim.RUN,
                state.claimStartup(running, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));

        WorkerPoolState.Lease<String> idleLease;
        WorkerPoolState.Lease<String> activeLease;
        try (PoolStateEffects<String> effects = idle.effects()) {
            idleLease = state.completeStartup(
                    idle, new WorkerStartup.CreatedWorker<>("idle", 1), FACTORY_COMPLETED, effects);
        }
        try (PoolStateEffects<String> effects = fixture.effects()) {
            state.releaseReusable(idleLease, effects);
        }
        try (PoolStateEffects<String> effects = leased.effects()) {
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
        assertEquals(1, admissions.availablePermits());
        assertSame("leased", activeLease.session());
    }

    @Test
    void retirementCompletionClaimsTheDrainExactlyOnce() throws Exception {
        StateFixture fixture = state(new Options(1, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        state.finishConstruction();
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        WorkerStartupCoordinator.Reservation<String> reservation =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> worker = reservation.stateWorker();
        WorkerPoolState.Lease<String> lease;
        try (PoolStateEffects<String> effects = reservation.effects()) {
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
        assertEquals(1, admissions.availablePermits());
        state.terminationView().get(1, TimeUnit.SECONDS);
    }

    @Test
    void fatalReplenishmentFactoryFailurePrecedesRemovalOfTheLastSlot() throws Exception {
        AssertionError fatal = new AssertionError("fatal startup");
        AtomicReference<WorkerStartup<String>> startupRef = new AtomicReference<>();
        StateFixture fixture = state(new Options(1, 0, 0, 10), () -> {
            PoolWorker<String> worker = new PoolWorker<>(noOpClose());
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
        PoolLifecycleDispatcher.AdmissionPool admissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        WorkerStartupCoordinator.Reservation<String> reservation =
                reserveWithAdmission(fixture, admissions, PoolWorker.StartupPurpose.REPLENISHMENT);
        assertSame(
                WorkerStartupCoordinator.StartupClaim.RUN,
                state.claimStartup(reservation, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        WorkerStartup<String> startup = startupRef.get();
        startup.start(new BoundedTaskPermit(new Semaphore(0)));
        ExecutionException startupFailure = assertThrows(
                ExecutionException.class, () -> startup.await(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        assertSame(fatal, startupFailure.getCause());

        boolean closedStartup;
        try (PoolStateEffects<String> effects = reservation.effects()) {
            closedStartup = state.factoryFailed(reservation, fatal, effects);
        }

        assertFalse(closedStartup);
        assertEquals(1, state.metrics().failedStartups());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(1, admissions.availablePermits());
        ExecutionException terminal = assertThrows(
                ExecutionException.class, () -> state.terminationView().get(1, TimeUnit.SECONDS));
        assertSame(fatal, terminal.getCause());
    }

    @Test
    void startupExitPathsKeepCreatedAndFailedCountersDistinct() {
        StateFixture preLaunch = state(new Options(1, 0, 0, 10));
        PoolLifecycleDispatcher.AdmissionPool preLaunchAdmissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        WorkerStartupCoordinator.Reservation<String> cancelled =
                reserveWithAdmission(preLaunch, preLaunchAdmissions, PoolWorker.StartupPurpose.DEMAND);
        WorkerPoolState.Lease<String> cancelledLease = cancelled.preparedLease();

        try (PoolStateEffects<String> effects = cancelled.effects()) {
            preLaunch.state().launchFailed(cancelled, effects);
        }

        assertThrows(IllegalStateException.class, cancelled::effects);
        assertThrows(IllegalStateException.class, cancelledLease::session);
        assertMetrics(preLaunch.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, preLaunch.state().metrics().failedStartups());
        assertEquals(1, preLaunchAdmissions.availablePermits());

        StateFixture lateFailure = state(new Options(1, 0, 0, 10));
        PoolLifecycleDispatcher.AdmissionPool failedAdmissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        WorkerStartupCoordinator.Reservation<String> failed =
                reserveWithAdmission(lateFailure, failedAdmissions, PoolWorker.StartupPurpose.DEMAND);

        try (PoolStateEffects<String> effects = failed.effects()) {
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
        assertEquals(1, failedAdmissions.availablePermits());

        StateFixture lateSuccess = state(new Options(1, 0, 0, 10));
        PoolLifecycleDispatcher.AdmissionPool successfulAdmissions = new PoolLifecycleDispatcher.AdmissionPool(1);
        WorkerStartupCoordinator.Reservation<String> succeeded =
                reserveWithAdmission(lateSuccess, successfulAdmissions, PoolWorker.StartupPurpose.DEMAND);

        try (PoolStateEffects<String> effects = succeeded.effects()) {
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
        assertEquals(0, successfulAdmissions.availablePermits());
    }

    @Test
    void metricsWaitCannotLoseAChangePublishedWhileThePredicateRuns() throws Exception {
        WorkerPoolState<String> state = state(new Options(1, 0, 0, 10)).state();
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

    private static StateFixture state(Options options) {
        return state(options, WorkerPoolStateTest::startingWorker);
    }

    private static StateFixture state(Options options, Supplier<PoolWorker<String>> workers) {
        AtomicReference<WorkerPoolState<String>> stateRef = new AtomicReference<>();
        WorkerRetirementCoordinator<String> retirements = new WorkerRetirementCoordinator<>(
                Runnable::run, (worker, outcome) -> null, (worker, failure) -> {}, report -> {});
        WorkerPoolState<String> state = new WorkerPoolState<>(
                new WorkerPoolPolicy(options),
                new PoolTermination(new PoolTerminalPublisher.Capacity(1).reserve()),
                () -> new WorkerStartupCoordinator.Reservation<>(
                        workers.get(), new PoolStateEffects<>(stateRef.get(), retirements)));
        stateRef.set(state);
        return new StateFixture(state, retirements);
    }

    private static WorkerStartupCoordinator.Reservation<String> reserveWithAdmission(
            StateFixture fixture, PoolLifecycleDispatcher.AdmissionPool admissions, PoolWorker.StartupPurpose purpose) {
        WorkerPoolState.ReservationResult<String> result = fixture.state().reserve();
        assertSame(WorkerPoolState.ReserveStatus.RESERVED, result.status());
        WorkerStartupCoordinator.Reservation<String> reservation = result.reservation();
        assertTrue(fixture.state().attachStartupAdmission(reservation, admissions.acquireUninterruptibly(), purpose));
        return reservation;
    }

    private static PoolWorker<String> startingWorker() {
        PoolWorker<String> worker = new PoolWorker<>(noOpClose());
        worker.startup(new WorkerStartup<>(() -> "unused", "worker-pool-state-test-", completion -> {}));
        return worker;
    }

    private static WorkerRetirement.Action<String> noOpClose() {
        return (session, admission) -> () -> CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
    }

    private static void assertMetrics(
            PoolMetrics.Snapshot snapshot,
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

    private record Options(int maxSize, int warmupSize, int minIdle, int maxRequestsPerWorker)
            implements WorkerPoolPolicy.Options {

        @Override
        public Duration acquireTimeout() {
            return Duration.ofSeconds(1);
        }

        @Override
        public Duration maxWorkerAge() {
            return Duration.ZERO;
        }

        @Override
        public boolean backgroundReplenishment() {
            return false;
        }
    }
}
