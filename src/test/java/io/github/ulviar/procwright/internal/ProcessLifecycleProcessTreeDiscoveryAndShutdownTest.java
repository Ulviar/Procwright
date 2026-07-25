/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProcessLifecycleProcessTreeDiscoveryAndShutdownTest
        extends ProcessLifecycleProcessTreeDiscoveryAndShutdownSupport {
    @Test
    void gracefulShutdownDiscoversAndStopsDescendantCreatedByRootTermination() {
        LateDescendantProcess process = new LateDescendantProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().gracefulDestroyCalls());
    }

    @Test
    void gracefulShutdownDoesNotSignalNewDescendantUntilRootHookExits() {
        SpawnInProgressProcess process = new SpawnInProgressProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertFalse(
                process.descendantSignalledWhileRootAlive(),
                "signalling a newly visible child can interrupt ProcessBuilder.start in the root shutdown hook");
        assertTrue(
                process.rootSurvivedFirstPostDiscoveryPoll(),
                "the fixture must expose the descendant while the shutdown hook root is still alive");
        assertEquals(1, process.descendant().gracefulDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void pendingGracefulDescendantReceivesOneForcefulSignalAfterEscalation() {
        PendingAcrossPhaseProcess process = new PendingAcrossPhaseProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertEquals(0, process.descendant().gracefulDestroyCalls());
        assertEquals(1, process.descendant().forceDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void forcefulPhaseCannotEraseAGracefulDynamicDiscoveryGapAfterReparenting() {
        ReparentingDeadlineProcess process = new ReparentingDeadlineProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(25), Duration.ofMillis(50))));

        assertTrue(failure.getMessage().contains("did not exit after forceful termination"));
        assertTrue(process.hiddenDescendant().isAlive());
        assertEquals(0, process.hiddenDescendant().forceDestroyCalls());
        process.hiddenDescendant().destroyForcibly();
    }

    @Test
    void falseGracefulHandleResultDoesNotCloseOutputThroughProcessFallback() {
        FalseGracefulResultProcess process = new FalseGracefulResultProcess();

        ProcessTreeShutdown.stop(
                process,
                KnownDescendants.empty(),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                (threadPrefix, action) -> action.run());

        assertTrue(process.shutdownHookCreatedDescendant());
        assertEquals(0, process.processDestroyCalls());
        assertEquals(1, process.descendant().gracefulDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void descendantEnumerationSecurityFailureStillStopsRootAndReportsIncompleteTree() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100))));

        failureSourceContaining(failure, "discovery did not complete");
        assertFalse(process.isAlive());
        assertEquals(1, process.destroyCalls());
        assertEquals(0, process.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationSecurityFailureStillForceStopsRootAndReportsIncompleteTree() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class, () -> ProcessLifecycle.forceStop(process, Duration.ofMillis(100)));

        failureSourceContaining(failure, "discovery did not complete");
        assertFalse(process.isAlive());
        assertEquals(0, process.destroyCalls());
        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void fatalDiscoveryStillCleansTheObservedPrefixBeforeRethrowing() {
        AssertionError expected = new AssertionError("fatal discovery after prefix");
        FatalPrefixProcess process = new FatalPrefixProcess(expected);

        Error actual = assertThrows(Error.class, () -> ProcessLifecycle.forceStop(process, Duration.ofMillis(100)));

        assertSame(expected, actual.getCause());
        assertTrue(failureSources(actual).contains(expected));
        assertFalse(process.isAlive());
        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().forceDestroyCalls());
    }

    @Test
    void fatalKnownRootTraversalStillCleansChildrenFromEarlierRoots() {
        MutableProcessHandle child = new MutableProcessHandle(40);
        MutableProcessHandle firstRoot = new MutableProcessHandle(41) {
            @Override
            public java.util.stream.Stream<ProcessHandle> children() {
                return java.util.stream.Stream.of(child);
            }
        };
        AssertionError expected = new AssertionError("later root children failed");
        MutableProcessHandle failingRoot = new MutableProcessHandle(42) {
            @Override
            public java.util.stream.Stream<ProcessHandle> children() {
                throw expected;
            }
        };

        Error actual = assertThrows(
                Error.class,
                () -> ProcessLifecycle.forceStop(
                        new CompletedProcess(), knownDescendants(firstRoot, failingRoot), Duration.ofMillis(100)));

        assertSame(expected, actual.getCause());
        assertTrue(failureSources(actual).contains(expected));
        assertFalse(child.isAlive());
        assertEquals(1, child.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationRuntimeFailureDegradesNaturalWaitButFailsShutdownProof() throws Exception {
        IllegalStateException enumerationFailure = new IllegalStateException("sysctl descendant lookup failed");
        SecurityRestrictedProcess completed = new SecurityRestrictedProcess(enumerationFailure);
        completed.complete();
        LiveDescendantSnapshot observed = new LiveDescendantSnapshot();

        assertTrue(ProcessLifecycle.waitFor(completed, Duration.ofSeconds(1), observed));
        assertTrue(observed.current().isEmpty());

        SecurityRestrictedProcess graceful = new SecurityRestrictedProcess(enumerationFailure);
        CommandExecutionException gracefulFailure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        graceful, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100))));
        failureSourceContaining(gracefulFailure, "discovery did not complete");
        assertFalse(graceful.isAlive());
        assertEquals(1, graceful.destroyCalls());

        SecurityRestrictedProcess forceful = new SecurityRestrictedProcess(enumerationFailure);
        CommandExecutionException forcefulFailure = assertThrows(
                CommandExecutionException.class, () -> ProcessLifecycle.forceStop(forceful, Duration.ofMillis(100)));
        failureSourceContaining(forcefulFailure, "discovery did not complete");
        assertFalse(forceful.isAlive());
        assertEquals(1, forceful.forceDestroyCalls());
    }

    @Test
    void unavailableWatcherSnapshotPreservesItsTypedCleanupFailure() {
        KnownDescendants unavailable = KnownDescendants.copyOf(new java.util.LinkedHashMap<>(), false, true);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(new CompletedProcess(), unavailable, Duration.ofMillis(100)));

        failureSourceContaining(failure, "discovery did not complete");
    }

    @Test
    void unobservableKnownDescendantIsSignalledButCannotProveGracefulCleanup() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(45);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.stop(
                        new CompletedProcess(),
                        knownDescendants(descendant),
                        ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100))));

        assertEquals(1, descendant.destroyCalls());
        assertEquals(1, descendant.forceDestroyCalls());
        assertTrue(failure.getMessage().contains("did not exit after forceful termination"));
    }

    @Test
    void unobservableKnownDescendantIsSignalledButCannotProveForcefulCleanup() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(46);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(
                        new CompletedProcess(), knownDescendants(descendant), Duration.ofMillis(100)));

        assertEquals(0, descendant.destroyCalls());
        assertEquals(1, descendant.forceDestroyCalls());
        assertTrue(failure.getMessage().contains("did not exit during forceful cleanup"));
    }

    @Test
    void finalStabilizationScanCannotTurnItsOwnTimeoutIntoSuccess() throws Exception {
        FinalScanBlockingProcess process = new FinalScanBlockingProcess();
        MutableProcessHandle exited = new MutableProcessHandle(48);
        exited.destroyForcibly();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(process, knownDescendants(exited), Duration.ofMillis(100)));

        assertTrue(failure.getMessage().contains("did not exit during forceful cleanup"));
        assertTrue(process.scanCalls() >= 3);
        assertTrue(ProcessTreeScanner.shared().awaitReportingSettlement(Duration.ofSeconds(1)));
    }

    @Test
    void initialScanDeadlineCannotProveAnAlreadyExitedRootTree() throws Exception {
        InitialScanBlockingExitedProcess process = new InitialScanBlockingExitedProcess();
        FutureTask<Throwable> cleanup = new FutureTask<>(() -> {
            try {
                ProcessLifecycle.forceStop(process, Duration.ofMillis(25));
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        Thread caller = new Thread(cleanup, "initial-scan-deadline-cleanup-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(process.awaitScan());
            Throwable failure = cleanup.get(1, TimeUnit.SECONDS);

            assertTrue(failure instanceof CommandExecutionException);
            assertTrue(failure.getMessage().contains("did not exit during forceful cleanup"));
            assertEquals(0, process.forceDestroyCalls());
        } finally {
            process.releaseScan();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertFalse(caller.isAlive());
        assertTrue(ProcessTreeScanner.shared().awaitReportingSettlement(Duration.ofSeconds(1)));
    }

    @Test
    void descendantOverflowCannotProduceASuccessfulCompletionProof() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        Set<ProcessHandle> known = new LinkedHashSet<>();
        for (int index = 0; index < limit; index++) {
            MutableProcessHandle exited = new MutableProcessHandle(50_000L + index);
            exited.destroyForcibly();
            known.add(exited);
        }
        OverflowAfterInitializationProcess process = new OverflowAfterInitializationProcess();

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(process, knownDescendants(known), Duration.ofSeconds(2)));

        failureSourceContaining(failure, "bounded descendant limit");
        assertEquals(0, process.overflow().forceDestroyCalls());
    }

    @Test
    void forcefulWaitDiscoversAndForceStopsLateDescendant() {
        ForceLateDescendantProcess process = new ForceLateDescendantProcess();

        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().forceDestroyCalls());
    }
}
