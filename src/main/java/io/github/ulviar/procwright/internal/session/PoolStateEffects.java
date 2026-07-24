/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns the post-monitor effects selected by one pool-state transaction.
 *
 * <p>The first retirement and admission require no secondary allocation. Closing the effects owner attempts every
 * physical effect and the selected terminal publication before propagating a failure.
 */
final class PoolStateEffects<S> implements AutoCloseable, Runnable {

    private final WorkerPoolState<S> state;
    private final WorkerRetirementCoordinator<S> retirements;
    private PoolWorker<S> firstRetirement;
    private ArrayList<PoolWorker<S>> additionalRetirements;
    private FailureReport firstImmediateReport;
    private FailureReport[] additionalImmediateReports;
    private int immediateReportCount;
    private PoolLifecycleDispatcher.Admission firstAdmission;
    private ArrayList<PoolLifecycleDispatcher.Admission> additionalAdmissions;
    private PoolTermination.Publication publication;
    private RuntimeException runtimeFailure;
    private Error fatalFailure;
    private boolean closed;

    PoolStateEffects(WorkerPoolState<S> state, WorkerRetirementCoordinator<S> retirements) {
        this.state = Objects.requireNonNull(state, "state");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
    }

    void requireOwner(WorkerPoolState<S> expected) {
        requireOpen();
        if (state != expected) {
            throw new IllegalArgumentException("pool state effects belong to another pool");
        }
    }

    void prepare(int retirementAdditions, int admissionAdditions) {
        requireOpen();
        if (retirementAdditions < 0 || admissionAdditions < 0) {
            throw new IllegalArgumentException("effect additions must not be negative");
        }
        additionalRetirements = ensureCapacity(additionalRetirements, firstRetirement != null, retirementAdditions);
        int retirementCount = (firstRetirement == null ? 0 : 1)
                + (additionalRetirements == null ? 0 : additionalRetirements.size())
                + retirementAdditions;
        int requiredReports = Math.max(0, retirementCount - 1);
        if (requiredReports > 0
                && (additionalImmediateReports == null || additionalImmediateReports.length < requiredReports)) {
            additionalImmediateReports = new FailureReport[requiredReports];
        }
        additionalAdmissions = ensureCapacity(additionalAdmissions, firstAdmission != null, admissionAdditions);
    }

    void retire(PoolWorker<S> worker) {
        requireOpen();
        PoolWorker<S> candidate = Objects.requireNonNull(worker, "worker");
        if (firstRetirement == null) {
            firstRetirement = candidate;
            return;
        }
        if (additionalRetirements == null) {
            additionalRetirements = new ArrayList<>();
        }
        int requiredReports = additionalRetirements.size() + 1;
        if (additionalImmediateReports == null || additionalImmediateReports.length < requiredReports) {
            additionalImmediateReports = new FailureReport[requiredReports];
        }
        additionalRetirements.add(candidate);
    }

    void release(PoolLifecycleDispatcher.Admission admission) {
        requireOpen();
        if (admission == null) {
            return;
        }
        if (firstAdmission == null) {
            firstAdmission = admission;
            return;
        }
        if (additionalAdmissions == null) {
            additionalAdmissions = new ArrayList<>();
        }
        additionalAdmissions.add(admission);
    }

    void publish(PoolTermination.Publication selected) {
        requireOpen();
        if (selected == null) {
            return;
        }
        if (publication != null) {
            throw new IllegalStateException("pool state transaction selected more than one terminal publication");
        }
        publication = selected;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAdmissions();
        if (firstRetirement != null) {
            try {
                retirements.dispatch(this);
            } catch (RuntimeException | Error failure) {
                record(failure);
            }
        }
        try {
            state.publish(publication);
        } catch (RuntimeException | Error failure) {
            record(failure);
        }
        throwRecordedFailure();
    }

    @Override
    public void run() {
        retirements.run(this, firstRetirement, additionalRetirements == null ? List.of() : additionalRetirements);
    }

    void recordImmediateReport(FailureReport report) {
        if (report == null) {
            return;
        }
        if (immediateReportCount == 0) {
            firstImmediateReport = report;
        } else {
            if (additionalImmediateReports == null || immediateReportCount > additionalImmediateReports.length) {
                throw new IllegalStateException("retirement report capacity was not prepared");
            }
            additionalImmediateReports[immediateReportCount - 1] = report;
        }
        immediateReportCount++;
    }

    void publishImmediateReports(Consumer<FailureReport> reporter) {
        Objects.requireNonNull(reporter, "reporter");
        if (firstImmediateReport != null) {
            reporter.accept(firstImmediateReport);
        }
        for (int index = 1; index < immediateReportCount; index++) {
            reporter.accept(additionalImmediateReports[index - 1]);
        }
    }

    private void closeAdmissions() {
        if (firstAdmission != null) {
            try {
                firstAdmission.close();
            } catch (RuntimeException | Error failure) {
                record(failure);
            }
        }
        if (additionalAdmissions != null) {
            for (PoolLifecycleDispatcher.Admission admission : additionalAdmissions) {
                try {
                    admission.close();
                } catch (RuntimeException | Error failure) {
                    record(failure);
                }
            }
        }
    }

    private void record(Throwable failure) {
        if (failure instanceof Error error) {
            if (fatalFailure == null) {
                fatalFailure = error;
            } else {
                suppress(fatalFailure, error);
            }
        } else if (runtimeFailure == null) {
            runtimeFailure = (RuntimeException) failure;
        } else {
            suppress(runtimeFailure, failure);
        }
    }

    private void throwRecordedFailure() {
        if (fatalFailure != null) {
            suppress(fatalFailure, runtimeFailure);
            throw fatalFailure;
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("pool state effects are already closed");
        }
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (secondary == null || primary == secondary) {
            return;
        }
        try {
            SuppressionSupport.attach(primary, secondary);
        } catch (RuntimeException | Error ignored) {
            // Cleanup must continue even when optional failure bookkeeping is unavailable.
        }
    }

    private static <T> ArrayList<T> ensureCapacity(ArrayList<T> additional, boolean hasFirst, int additions) {
        int existing = (hasFirst ? 1 : 0) + (additional == null ? 0 : additional.size());
        int requiredAdditional = Math.max(0, existing + additions - 1);
        if (requiredAdditional == 0) {
            return additional;
        }
        if (additional == null) {
            return new ArrayList<>(requiredAdditional);
        }
        additional.ensureCapacity(requiredAdditional);
        return additional;
    }
}
