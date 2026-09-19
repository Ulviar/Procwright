/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Owns root and descendant signal ordering and JDK process destroy fallback. */
final class ProcessShutdownSignals {

    private final Process process;
    private final ShutdownTreeState tree;
    private final ShutdownFailureLedger failures;
    private final DestroyFallbackDispatcher destroyFallback;

    ProcessShutdownSignals(
            Process process,
            ShutdownTreeState tree,
            ShutdownFailureLedger failures,
            DestroyFallbackDispatcher destroyFallback) {
        this.process = process;
        this.tree = tree;
        this.failures = failures;
        this.destroyFallback = destroyFallback;
    }

    void destroyDescendants(ShutdownPhase phase) {
        destroyHandlesInReverseOrder(tree.takeAllDescendants(), phase);
    }

    void signalPendingDescendants(ShutdownPhase phase) {
        Set<ProcessHandle> pending = tree.takePendingDescendants();
        if (pending.isEmpty()) {
            return;
        }
        destroyHandlesInReverseOrder(pending, phase);
    }

    void destroyRoot(ShutdownPhase phase) {
        boolean useFallback = false;
        try {
            ProcessHandle handle = process.toHandle();
            failures.interruptionBoundary();
            boolean alive = true;
            try {
                ProcessLiveness.Observation observation = ProcessLiveness.observe(handle);
                alive = observation != ProcessLiveness.Observation.EXITED;
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
            }
            failures.interruptionBoundary();
            if (!alive) {
                return;
            }
            try {
                boolean signalled = phase.isForceful() ? handle.destroyForcibly() : handle.destroy();
                if (signalled || phase == ShutdownPhase.GRACEFUL) {
                    // Process.destroy() closes all three process streams on Unix JDKs. A false result from a
                    // graceful ProcessHandle signal can race with successful signal delivery, so escalating through
                    // the Process API here can break a running shutdown hook before its output is drained. Observe
                    // the graceful deadline first; the forceful phase may use the fallback if the root stays alive.
                    return;
                }
                useFallback = true;
            } catch (UnsupportedOperationException | SecurityException ignored) {
                useFallback = true;
            } catch (RuntimeException | Error failure) {
                failures.record(failure);
                useFallback = true;
            }
            failures.interruptionBoundary();
        } catch (UnsupportedOperationException | SecurityException exception) {
            // Fall back to the Process API below. It must remain off the lifecycle thread because some JDK
            // implementations close a contended stdin stream before signalling the process.
            useFallback = true;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            useFallback = true;
        }
        failures.interruptionBoundary();
        if (!useFallback) {
            return;
        }
        failures.attempt(() -> dispatchDestroyFallback(
                phase.isForceful() ? "procwright-process-force-destroy-" : "procwright-process-destroy-",
                phase.isForceful() ? process::destroyForcibly : process::destroy));
    }

    private void destroyHandles(Iterable<ProcessHandle> handles, ShutdownPhase phase) {
        for (ProcessHandle handle : handles) {
            destroy(handle, phase);
            failures.interruptionBoundary();
        }
    }

    private void destroyHandlesInReverseOrder(Iterable<ProcessHandle> handles, ShutdownPhase phase) {
        List<ProcessHandle> reverseOrder = new ArrayList<>();
        handles.forEach(reverseOrder::add);
        Collections.reverse(reverseOrder);
        destroyHandles(reverseOrder, phase);
    }

    private void destroy(ProcessHandle handle, ShutdownPhase phase) {
        boolean alive = true;
        try {
            ProcessLiveness.Observation observation = ProcessLiveness.observe(handle);
            alive = observation != ProcessLiveness.Observation.EXITED;
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            if (phase.isForceful()) {
                tree.excludeFromCompletion(handle);
            }
        }
        failures.interruptionBoundary();
        if (!alive) {
            return;
        }
        try {
            if (phase.isForceful()) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Cleanup remains best-effort when the operating system denies the signal.
        } catch (RuntimeException | Error failure) {
            failures.record(failure);
            if (phase.isForceful()) {
                tree.excludeFromCompletion(handle);
            }
        }
    }

    private void dispatchDestroyFallback(String threadPrefix, Runnable action) {
        try {
            destroyFallback.dispatch(threadPrefix, action);
        } catch (CommandExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }
}
