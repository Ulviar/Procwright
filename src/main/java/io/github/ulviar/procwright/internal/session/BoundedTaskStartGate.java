/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Keeps a newly started thread side-effect free until admission and permit ownership transfer are committed. */
final class BoundedTaskStartGate {

    private State state = State.PENDING;

    synchronized void admit() {
        state = State.ADMITTED;
        notifyAll();
    }

    synchronized void reject() {
        if (state == State.PENDING) {
            state = State.REJECTED;
            notifyAll();
        }
    }

    synchronized boolean awaitAdmission() {
        boolean interrupted = false;
        while (state == State.PENDING) {
            try {
                wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return state == State.ADMITTED;
    }

    private enum State {
        PENDING,
        ADMITTED,
        REJECTED
    }
}
