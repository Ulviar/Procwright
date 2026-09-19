/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.ArrayList;
import java.util.List;

/** Observes trusted process objects without treating unavailable state as proof of exit. */
final class ProcessLiveness {

    private ProcessLiveness() {}

    static boolean hasExited(Process process) {
        Observation observation = observe(process);
        return observation == Observation.EXITED
                || observation == Observation.UNOBSERVABLE && observeExitValue(process) == Observation.EXITED;
    }

    static Observation observe(Process process) {
        try {
            return process.isAlive() ? Observation.LIVE : Observation.EXITED;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        }
    }

    static Observation observe(ProcessHandle handle) {
        try {
            return handle.isAlive() ? Observation.LIVE : Observation.EXITED;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        }
    }

    static ExitObservation observeExitForCleanup(Process process) {
        List<Throwable> events = new ArrayList<>(2);
        try {
            Observation observation = observe(process);
            if (observation != Observation.UNOBSERVABLE) {
                return new ExitObservation(observation, events);
            }
        } catch (RuntimeException | Error failure) {
            events.add(failure);
        }
        try {
            return new ExitObservation(observeExitValue(process), events);
        } catch (RuntimeException | Error failure) {
            events.add(failure);
            return new ExitObservation(Observation.UNOBSERVABLE, events);
        }
    }

    private static Observation observeExitValue(Process process) {
        try {
            process.exitValue();
            return Observation.EXITED;
        } catch (IllegalThreadStateException stillRunning) {
            return Observation.LIVE;
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            return Observation.UNOBSERVABLE;
        }
    }

    enum Observation {
        LIVE,
        EXITED,
        UNOBSERVABLE
    }

    record ExitObservation(Observation state, List<Throwable> events) {
        ExitObservation {
            events = List.copyOf(events);
        }
    }
}
