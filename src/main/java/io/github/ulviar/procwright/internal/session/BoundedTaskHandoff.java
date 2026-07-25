/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/**
 * Owns task/rejection selection, the admission gate, and the externally observable handoff phase.
 *
 * <p>The selected callback waits without consuming interruption until the caller either transfers
 * permit ownership or proves that no task may start.
 */
class BoundedTaskHandoff {

    private Phase phase = Phase.WAITING_FOR_ADMISSION;
    private boolean callbackClaimed;

    static BoundedTaskHandoff untracked() {
        return new BoundedTaskHandoff();
    }

    synchronized boolean claimAndAwaitAdmission() {
        if (callbackClaimed) {
            return false;
        }
        callbackClaimed = true;
        boolean interrupted = false;
        while (phase == Phase.WAITING_FOR_ADMISSION) {
            try {
                wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return phase == Phase.ADMITTED;
    }

    synchronized void rejectBeforeAdmission() {
        transition(Phase.REJECTED_BEFORE_ADMISSION);
    }

    synchronized void rejectIfWaiting() {
        if (phase == Phase.WAITING_FOR_ADMISSION) {
            phase = Phase.REJECTED_BEFORE_ADMISSION;
            notifyAll();
        }
    }

    synchronized void admit() {
        transition(Phase.ADMITTED);
    }

    synchronized boolean retrySafe() {
        return phase == Phase.REJECTED_BEFORE_ADMISSION;
    }

    synchronized Phase phase() {
        return phase;
    }

    private void transition(Phase next) {
        if (phase != Phase.WAITING_FOR_ADMISSION) {
            throw new IllegalStateException("task handoff cannot transition from " + phase + " to " + next);
        }
        phase = next;
        notifyAll();
    }

    enum Phase {
        WAITING_FOR_ADMISSION,
        REJECTED_BEFORE_ADMISSION,
        ADMITTED
    }
}
