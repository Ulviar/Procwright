/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.internal.KnownDescendants;
import io.github.ulviar.procwright.internal.LiveDescendantSnapshot;
import io.github.ulviar.procwright.internal.ProcessLifecycle;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/** Owns descendant observation and the exactly-once process-tree cleanup result for one session. */
final class SessionProcessCleanup {

    private static final Duration FAILURE_CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private final Process process;
    private final ShutdownPolicy shutdownPolicy;
    private final LiveDescendantSnapshot liveDescendants = new LiveDescendantSnapshot();
    private boolean completed;
    private OptionalInt exitCode = OptionalInt.empty();
    private volatile OptionalInt exitCodeSnapshot = OptionalInt.empty();

    SessionProcessCleanup(Process process, ShutdownPolicy shutdownPolicy) {
        this.process = Objects.requireNonNull(process, "process");
        this.shutdownPolicy = Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
    }

    int awaitNaturalExit() throws InterruptedException {
        ProcessLifecycle.waitFor(process, Duration.ZERO, liveDescendants);
        int observed = process.exitValue();
        exitCodeSnapshot = OptionalInt.of(observed);
        return observed;
    }

    OptionalInt exitCodeSnapshot() {
        return exitCodeSnapshot;
    }

    synchronized OptionalInt stop() {
        if (completed) {
            return exitCode;
        }
        try {
            exitCode = ProcessLifecycle.stop(process, knownDescendants(), shutdownPolicy);
            exitCodeSnapshot = exitCode;
            return exitCode;
        } finally {
            completed = true;
        }
    }

    Throwable forceAfterFailure() {
        synchronized (this) {
            if (completed) {
                return null;
            }
            try {
                ProcessLifecycle.forceStop(process, knownDescendants(), FAILURE_CLEANUP_TIMEOUT);
                return null;
            } catch (RuntimeException | Error failure) {
                return failure;
            } finally {
                completed = true;
            }
        }
    }

    Throwable stopAfterFailure() {
        synchronized (this) {
            if (completed) {
                return null;
            }
            try {
                exitCode = ProcessLifecycle.stop(process, knownDescendants(), shutdownPolicy);
                exitCodeSnapshot = exitCode;
                return null;
            } catch (RuntimeException | Error failure) {
                return failure;
            } finally {
                completed = true;
            }
        }
    }

    private KnownDescendants knownDescendants() {
        return liveDescendants.sealForCleanup();
    }
}
