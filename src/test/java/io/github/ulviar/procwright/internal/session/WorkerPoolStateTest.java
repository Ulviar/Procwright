/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class WorkerPoolStateTest {

    @Test
    void fullWorkerLifecycleKeepsPartitionMetricsAndLeaseOwnershipConsistent() {
        StateFixture fixture = state(settings(1, 0, 0, 2));
        WorkerPoolState<String> state = fixture.state();
        PoolWorker<String> worker = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(fixture, worker);

        WorkerPoolState.Lease<String> firstLease =
                state.completeStartup(worker, new WorkerStartup.CreatedWorker<>("worker", 11));

        assertMetrics(state.metrics(), 1, 0, 1, 0, 0, 1, 0);
        assertNull(state.recordRequestAndRetirementReason(firstLease));
        state.releaseReusable(firstLease);

        assertMetrics(state.metrics(), 1, 1, 0, 0, 0, 1, 0);
        PooledSessionMetrics released = state.metrics();
        assertThrows(IllegalStateException.class, () -> state.releaseReusable(firstLease));
        assertEquals(released, state.metrics());

        WorkerPoolState.AcquireResult<String> acquired =
                state.awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        assertTrue(acquired instanceof WorkerPoolState.LeaseAcquired<String>);
        WorkerPoolState.Lease<String> secondLease = ((WorkerPoolState.LeaseAcquired<String>) acquired).lease();
        assertSame(PooledWorkerRetireReason.MAX_REQUESTS, state.recordRequestAndRetirementReason(secondLease));

        state.releaseReusable(secondLease);

        assertMetrics(state.metrics(), 1, 0, 0, 0, 1, 1, 0);
        assertEquals(1, fixture.closeCount());
        assertNull(state.completeRetirement(worker, WorkerRetirement.Outcome.success(), null));

        PooledSessionMetrics completed = state.metrics();
        assertMetrics(completed, 0, 0, 0, 0, 0, 1, 1);
        assertEquals(1L, completed.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
    }

    @Test
    void demandAcquireCommitsAStartingWorkerBeforeReturningIt() {
        StateFixture fixture = state(settings(1, 0, 0, 10));

        WorkerPoolState.AcquireResult<String> acquisition =
                fixture.state().awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));

        assertTrue(acquisition instanceof WorkerPoolState.StartupReserved<String>);
        assertNotNull(((WorkerPoolState.StartupReserved<String>) acquisition).worker());
        assertMetrics(fixture.state().metrics(), 1, 0, 0, 1, 0, 0, 0);
    }

    @Test
    void workerFactoryFailureDoesNotConsumePoolCapacity() {
        AssertionError failure = new AssertionError("worker allocation failed");
        WorkerPoolState<String> state = new WorkerPoolState<>(
                new WorkerPoolPolicy(settings(1, 0, 0, 10)),
                purpose -> {
                    throw failure;
                },
                retirementCoordinator());

        assertSame(failure, assertThrows(AssertionError.class, state::reserveWarmup));
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void closeDetachesStartingWorkersAndKeepsLeasedWorkersOwnedUntilReturn() {
        StateFixture fixture = state(settings(4, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        PoolWorker<String> queued = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> running = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> idle = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        PoolWorker<String> leased = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(fixture, running);
        claimStartup(fixture, idle);
        claimStartup(fixture, leased);
        WorkerPoolState.Lease<String> idleLease =
                state.completeStartup(idle, new WorkerStartup.CreatedWorker<>("idle", 1));
        state.releaseReusable(idleLease);
        WorkerPoolState.Lease<String> activeLease =
                state.completeStartup(leased, new WorkerStartup.CreatedWorker<>("leased", 1));

        PoolTermination.FailureDisposition disposition = state.beginClose(null);

        assertSame(PoolTermination.FailureDisposition.NONE, disposition);
        assertSame(WorkerStartup.TerminalDecision.CLOSED, queued.startup().terminalDecision());
        assertSame(WorkerStartup.TerminalDecision.CLOSED, running.startup().terminalDecision());
        assertMetrics(state.metrics(), 2, 0, 1, 0, 1, 2, 0);
        assertSame("leased", activeLease.session());
        assertEquals(1, fixture.closeCount());
    }

    @Test
    void closingPoolPublishesTerminationAfterItsLastRetirementCompletes() throws Exception {
        StateFixture fixture = state(settings(1, 0, 0, 10));
        WorkerPoolState<String> state = fixture.state();
        state.finishConstruction();
        PoolWorker<String> worker = reserve(fixture, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(fixture, worker);
        WorkerPoolState.Lease<String> lease =
                state.completeStartup(worker, new WorkerStartup.CreatedWorker<>("worker", 3));
        state.retire(lease, PooledWorkerRetireReason.CLOSED);

        state.beginClose(null);

        assertFalse(state.terminationView().isDone());
        assertNull(state.completeRetirement(worker, WorkerRetirement.Outcome.success(), null));
        state.terminationView().get(1, TimeUnit.SECONDS);
        assertTrue(state.terminationView().isDone());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 1, 1);
    }

    @Test
    void fatalReplenishmentFailureClosesPoolAfterRemovingItsSlot() throws Exception {
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
        PoolWorker<String> worker = reserve(fixture, PoolWorker.StartupPurpose.REPLENISHMENT);
        claimStartup(fixture, worker);
        WorkerStartup<String> startup = startupRef.get();
        startup.start(new BoundedTaskPermit(new Semaphore(0)));
        ExecutionException startupFailure = assertThrows(
                ExecutionException.class, () -> startup.await(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
        assertSame(fatal, startupFailure.getCause());

        assertFalse(state.factoryFailed(worker, fatal));

        assertEquals(1, state.metrics().failedStartups());
        assertMetrics(state.metrics(), 0, 0, 0, 0, 0, 0, 0);
        ExecutionException terminal = assertThrows(
                ExecutionException.class, () -> state.terminationView().get(1, TimeUnit.SECONDS));
        assertSame(fatal, terminal.getCause());
    }

    @Test
    void startupExitPathsKeepCreatedAndFailedCountersDistinct() {
        StateFixture preLaunch = state(settings(1, 0, 0, 10));
        PoolWorker<String> cancelled = reserve(preLaunch, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(preLaunch, cancelled);

        preLaunch.state().discardStartingWorker(cancelled);

        assertMetrics(preLaunch.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, preLaunch.state().metrics().failedStartups());

        StateFixture lateFailure = state(settings(1, 0, 0, 10));
        PoolWorker<String> failed = reserve(lateFailure, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(lateFailure, failed);
        lateFailure
                .state()
                .completeAbandonedStartup(
                        failed,
                        new WorkerStartup.LateCompletion<>(
                                null,
                                5,
                                PooledWorkerRetireReason.STARTUP_TIMEOUT,
                                new IllegalStateException("late failure"),
                                null));

        assertMetrics(lateFailure.state().metrics(), 0, 0, 0, 0, 0, 0, 0);
        assertEquals(1, lateFailure.state().metrics().failedStartups());

        StateFixture lateSuccess = state(settings(1, 0, 0, 10));
        PoolWorker<String> succeeded = reserve(lateSuccess, PoolWorker.StartupPurpose.DEMAND);
        claimStartup(lateSuccess, succeeded);
        lateSuccess
                .state()
                .completeAbandonedStartup(
                        succeeded,
                        new WorkerStartup.LateCompletion<>(
                                "late worker", 7, PooledWorkerRetireReason.STARTUP_TIMEOUT, null, null));

        assertMetrics(lateSuccess.state().metrics(), 1, 0, 0, 0, 1, 1, 0);
        assertEquals(1, lateSuccess.state().metrics().failedStartups());
        assertEquals(1, lateSuccess.closeCount());
    }

    private static StateFixture state(WorkerPoolSettings<?> settings) {
        AtomicInteger closes = new AtomicInteger();
        return state(settings, purpose -> startingWorker(purpose, closes), closes);
    }

    private static StateFixture state(
            WorkerPoolSettings<?> settings, Function<PoolWorker.StartupPurpose, PoolWorker<String>> workers) {
        return state(settings, workers, new AtomicInteger());
    }

    private static StateFixture state(
            WorkerPoolSettings<?> settings,
            Function<PoolWorker.StartupPurpose, PoolWorker<String>> workers,
            AtomicInteger closes) {
        WorkerPoolState<String> state =
                new WorkerPoolState<>(new WorkerPoolPolicy(settings), workers, retirementCoordinator());
        return new StateFixture(state, closes);
    }

    private static WorkerRetirementCoordinator<String> retirementCoordinator() {
        return new WorkerRetirementCoordinator<>(
                Runnable::run,
                (worker, outcome) -> null,
                (worker, failure) -> {
                    throw new AssertionError(failure);
                },
                report -> {});
    }

    private static PoolWorker<String> reserve(StateFixture fixture, PoolWorker.StartupPurpose purpose) {
        PoolWorker<String> worker =
                switch (purpose) {
                    case DEMAND -> {
                        WorkerPoolState.AcquireResult<String> result =
                                fixture.state().awaitAcquire(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
                        yield ((WorkerPoolState.StartupReserved<String>) result).worker();
                    }
                    case WARMUP -> fixture.state().reserveWarmup();
                    case REPLENISHMENT -> fixture.state().tryReserveReplenishment();
                };
        assertNotNull(worker);
        assertSame(purpose, worker.startupPurpose());
        return worker;
    }

    private static PoolWorker<String> startingWorker(PoolWorker.StartupPurpose purpose, AtomicInteger closes) {
        PoolWorker<String> worker = new PoolWorker<>(
                session -> {
                    closes.incrementAndGet();
                    return CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
                },
                purpose);
        worker.startup(new WorkerStartup<>(() -> "unused", "worker-pool-state-test-", completion -> {}));
        return worker;
    }

    private static void claimStartup(StateFixture fixture, PoolWorker<String> worker) {
        assertSame(
                WorkerStartupCoordinator.StartupClaim.RUN,
                fixture.state().claimStartup(worker, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)));
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

    private record StateFixture(WorkerPoolState<String> state, AtomicInteger closes) {

        private int closeCount() {
            return closes.get();
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
