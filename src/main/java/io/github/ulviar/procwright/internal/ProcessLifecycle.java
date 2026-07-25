/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;
import java.util.OptionalInt;

/** Exposes shared process wait and process-tree shutdown operations to internal runtime packages. */
public final class ProcessLifecycle {

    private ProcessLifecycle() {}

    /**
     * Waits for process completion while periodically snapshotting live descendants.
     *
     * <p>Descendants of an exited process are no longer discoverable through {@link Process#descendants()} because the
     * operating system reparents them, so cleanup paths that may run after exit need a snapshot taken while the
     * process was still alive.
     *
     * <p>A {@link Duration#ZERO} timeout disables the deadline: the wait continues until the process exits on its
     * own. {@link ProcessExitWaiter} owns this wait-specific interpretation of a zero timeout.
     *
     * @param process watched process
     * @param timeout maximum wait, or {@link Duration#ZERO} to wait indefinitely
     * @param descendants receives live descendants observed while the process was alive; exited handles are
     *     pruned on later polls
     * @return whether the process exited within the timeout
     * @throws InterruptedException when the waiting thread is interrupted; the caller still owns process shutdown
     */
    public static boolean waitFor(Process process, Duration timeout, LiveDescendantSnapshot descendants)
            throws InterruptedException {
        return ProcessExitWaiter.waitFor(process, timeout, descendants);
    }

    public static OptionalInt stop(Process process, ShutdownPolicy shutdownPolicy) {
        return ProcessTreeShutdown.stop(process, shutdownPolicy);
    }

    public static OptionalInt stop(Process process, KnownDescendants knownDescendants, ShutdownPolicy shutdownPolicy) {
        return ProcessTreeShutdown.stop(process, knownDescendants, shutdownPolicy);
    }

    public static void forceStop(Process process, Duration timeout) {
        ProcessTreeShutdown.forceStop(process, timeout);
    }

    /**
     * Forcefully stops the process tree, including known descendants captured while the process was still alive.
     *
     * <p>The known-descendants snapshot covers descendants that survive their parent: once the launched process has
     * exited they are reparented and invisible to {@link Process#descendants()}, yet they can keep inherited output
     * pipes open.
     *
     * @param process launched process
     * @param knownDescendants descendants snapshot taken while the process was alive
     * @param timeout maximum cleanup wait
     */
    public static void forceStop(Process process, KnownDescendants knownDescendants, Duration timeout) {
        ProcessTreeShutdown.forceStop(process, knownDescendants, timeout);
    }
}
