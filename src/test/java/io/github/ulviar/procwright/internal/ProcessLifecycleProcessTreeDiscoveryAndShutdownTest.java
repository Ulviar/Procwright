/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.Set;
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
    void falseGracefulHandleResultDoesNotCloseOutputThroughProcessFallback() {
        FalseGracefulResultProcess process = new FalseGracefulResultProcess();

        ProcessLifecycle.stop(
                process,
                Set.of(),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)),
                (threadPrefix, action) -> action.run());

        assertTrue(process.shutdownHookCreatedDescendant());
        assertEquals(0, process.processDestroyCalls());
        assertEquals(1, process.descendant().gracefulDestroyCalls());
        assertFalse(process.descendant().isAlive());
    }

    @Test
    void descendantEnumerationSecurityFailureDoesNotPreventRootShutdown() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        ProcessLifecycle.stop(
                process, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertFalse(process.isAlive());
        assertEquals(1, process.destroyCalls());
        assertEquals(0, process.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationSecurityFailureDoesNotPreventForcedRootShutdown() {
        SecurityRestrictedProcess process = new SecurityRestrictedProcess();

        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertFalse(process.isAlive());
        assertEquals(0, process.destroyCalls());
        assertEquals(1, process.forceDestroyCalls());
    }

    @Test
    void descendantEnumerationRuntimeFailureDegradesToRootOnlyForNormalExitAndShutdown() throws Exception {
        IllegalStateException enumerationFailure = new IllegalStateException("sysctl descendant lookup failed");
        SecurityRestrictedProcess completed = new SecurityRestrictedProcess(enumerationFailure);
        completed.complete();
        LiveDescendantSnapshot observed = new LiveDescendantSnapshot();

        assertTrue(ProcessLifecycle.waitFor(completed, Duration.ofSeconds(1), observed));
        assertTrue(observed.current().isEmpty());

        SecurityRestrictedProcess graceful = new SecurityRestrictedProcess(enumerationFailure);
        ProcessLifecycle.stop(
                graceful, ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));
        assertFalse(graceful.isAlive());
        assertEquals(1, graceful.destroyCalls());

        SecurityRestrictedProcess forceful = new SecurityRestrictedProcess(enumerationFailure);
        ProcessLifecycle.forceStop(forceful, Duration.ofMillis(100));
        assertFalse(forceful.isAlive());
        assertEquals(1, forceful.forceDestroyCalls());
    }

    @Test
    void unobservableKnownDescendantStillReceivesGracefulShutdown() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(45);

        ProcessLifecycle.stop(
                new CompletedProcess(),
                Set.of(descendant),
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertEquals(1, descendant.destroyCalls());
        assertEquals(0, descendant.forceDestroyCalls());
    }

    @Test
    void unobservableKnownDescendantStillReceivesForcedShutdown() {
        UnobservableProcessHandle descendant = new UnobservableProcessHandle(46);

        ProcessLifecycle.forceStop(new CompletedProcess(), Set.of(descendant), Duration.ofMillis(100));

        assertEquals(0, descendant.destroyCalls());
        assertEquals(1, descendant.forceDestroyCalls());
    }

    @Test
    void stabilizationRefreshCannotBypassExpiredCleanupDeadline() {
        SuccessiveDescendantsHandle descendant = new SuccessiveDescendantsHandle(47, 64);

        assertThrows(
                CommandExecutionException.class,
                () -> ProcessLifecycle.forceStop(new CompletedProcess(), Set.of(descendant), Duration.ZERO));

        assertTrue(descendant.discoveryCalls() < 64, "cleanup must stop discovering after its deadline");
    }

    @Test
    void forcefulWaitDiscoversAndForceStopsLateDescendant() {
        ForceLateDescendantProcess process = new ForceLateDescendantProcess();

        ProcessLifecycle.forceStop(process, Duration.ofMillis(100));

        assertFalse(process.descendant().isAlive());
        assertEquals(1, process.descendant().forceDestroyCalls());
    }
}
