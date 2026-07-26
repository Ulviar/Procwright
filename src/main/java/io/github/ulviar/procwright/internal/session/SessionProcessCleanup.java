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
    private CleanupOutcome cleanupOutcome;
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

    OptionalInt stop() {
        CleanupOutcome outcome = cleanup(CleanupMode.NORMAL);
        rethrow(outcome.failure());
        return outcome.exitCode();
    }

    Throwable forceAfterFailure() {
        return cleanup(CleanupMode.FORCE).failure();
    }

    Throwable stopAfterFailure() {
        return cleanup(CleanupMode.NORMAL).failure();
    }

    private synchronized CleanupOutcome cleanup(CleanupMode mode) {
        if (cleanupOutcome != null) {
            return cleanupOutcome;
        }
        try {
            OptionalInt exitCode;
            if (mode == CleanupMode.NORMAL) {
                exitCode = ProcessLifecycle.stop(process, knownDescendants(), shutdownPolicy);
                exitCodeSnapshot = exitCode;
            } else {
                ProcessLifecycle.forceStop(process, knownDescendants(), FAILURE_CLEANUP_TIMEOUT);
                exitCode = OptionalInt.empty();
            }
            cleanupOutcome = new CleanupOutcome(exitCode, null);
        } catch (RuntimeException | Error failure) {
            cleanupOutcome = new CleanupOutcome(OptionalInt.empty(), failure);
        }
        return cleanupOutcome;
    }

    private KnownDescendants knownDescendants() {
        return liveDescendants.sealForCleanup();
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private enum CleanupMode {
        NORMAL,
        FORCE
    }

    private record CleanupOutcome(OptionalInt exitCode, Throwable failure) {

        private CleanupOutcome {
            Objects.requireNonNull(exitCode, "exitCode");
        }
    }
}
