/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.BlockingCloseInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.CloseTrackingInputStream;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.drainToEof;
import static io.github.ulviar.procwright.internal.session.OutputPumpTestFixtures.startCoordinator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class OutputPumpCleanupCoordinationTest {

    @Test
    void physicalOutputCloseWaitsForLogicalModeSettlement() throws Exception {
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2);
        CloseTrackingInputStream stdout = new CloseTrackingInputStream(closeDispatcher);
        CloseTrackingInputStream stderr = new CloseTrackingInputStream(closeDispatcher);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch stdoutFinished = new CountDownLatch(1);
        CountDownLatch stderrEntered = new CountDownLatch(1);
        CountDownLatch releaseStderr = new CountDownLatch(1);
        CountDownLatch stderrFinished = new CountDownLatch(1);
        OutputPumpTestFixtures.CoordinatorHarness harness = startCoordinator(
                process,
                closeDispatcher,
                SessionOutputMode.LINE,
                PumpStarter.threading(),
                "procwright-asymmetric-eof-stdout-pump-",
                stream -> drainToEof(stream, stdoutFinished),
                "procwright-asymmetric-eof-stderr-pump-",
                stream -> {
                    stderrEntered.countDown();
                    awaitUninterruptibly(releaseStderr);
                    drainToEof(stream, stderrFinished);
                });
        DefaultSession session = harness.session();
        OutputPumpCoordinator coordinator = harness.coordinator();
        try {
            assertTrue(stdoutFinished.await(1, TimeUnit.SECONDS));
            assertTrue(stderrEntered.await(1, TimeUnit.SECONDS));

            process.exitNaturally(0);

            assertEquals(0, stdout.closeCalls());
            assertEquals(0, stderr.closeCalls());
            assertFalse(session.onExit().isDone());

            releaseStderr.countDown();

            assertTrue(stderrFinished.await(1, TimeUnit.SECONDS));
            assertEquals(0, session.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(stdout.awaitClose());
            assertEquals(1, stdout.closeCalls());
            assertTrue(stderr.awaitClose());
            assertEquals(1, stderr.closeCalls());
        } finally {
            releaseStderr.countDown();
            coordinator.closeSession();
            session.close();
        }
    }

    @Test
    void pumpEofCannotPhysicallyCloseReservedOutputBeforeProcessCleanup() throws Exception {
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(2, 2);
        CloseTrackingInputStream stdout = new CloseTrackingInputStream(closeDispatcher);
        CloseTrackingInputStream stderr = new CloseTrackingInputStream(closeDispatcher);
        ControllableProcess process = new ControllableProcess(stdout, stderr);
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        OutputPumpTestFixtures.CoordinatorHarness harness = startCoordinator(
                process,
                closeDispatcher,
                SessionOutputMode.LINE,
                PumpStarter.threading(),
                "procwright-eof-race-stdout-pump-",
                stream -> drainToEof(stream, pumpsFinished),
                "procwright-eof-race-stderr-pump-",
                stream -> drainToEof(stream, pumpsFinished));
        DefaultSession session = harness.session();
        OutputPumpCoordinator coordinator = harness.coordinator();

        try {
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));
            assertEquals(0, stdout.closeCalls());
            assertEquals(0, stderr.closeCalls());
            assertTrue(process.isAlive());

            coordinator.closeSession();
            session.onExit().get(1, TimeUnit.SECONDS);

            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertTrue(stdout.activeDuringClose() > 0);
            assertTrue(stderr.activeDuringClose() > 0);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            coordinator.closeSession();
            session.close();
        }
    }

    @Test
    void occupiedDispatcherCapacityCannotStrandAReservedClose() throws Exception {
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(1, 2);
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        CloseTrackingInputStream stderr = new CloseTrackingInputStream();
        ControllableProcess process = new ControllableProcess(stdout, stderr, processAlive);
        CountDownLatch pumpsFinished = new CountDownLatch(2);
        OutputPumpTestFixtures.CoordinatorHarness harness = startCoordinator(
                process,
                closeDispatcher,
                SessionOutputMode.LINE,
                PumpStarter.threading(),
                "procwright-queued-close-stdout-pump-",
                stream -> drainToEof(stream, pumpsFinished),
                "procwright-queued-close-stderr-pump-",
                stream -> drainToEof(stream, pumpsFinished));
        DefaultSession session = harness.session();
        OutputPumpCoordinator coordinator = harness.coordinator();

        try {
            assertTrue(pumpsFinished.await(1, TimeUnit.SECONDS));

            coordinator.closeSession();

            assertTrue(process.awaitDestroyed());
            assertTrue(session.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted());
            assertEquals(0, stderr.closeCalls());

            stdout.releaseClose();

            assertTrue(stdout.awaitCloseCompleted());
            assertTrue(stderr.awaitClose());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            stdout.releaseClose();
            coordinator.closeSession();
            session.close();
        }
    }
}
