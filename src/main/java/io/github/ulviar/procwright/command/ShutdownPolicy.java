/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

import java.time.Duration;
import java.util.Objects;

/**
 * Defines the escalation path used when a process must be stopped.
 *
 * <p>Procwright requests graceful termination, waits up to {@code interruptGrace}, then requests forceful termination
 * for surviving processes and waits up to {@code killGrace}. These are shutdown budgets in addition to a scenario's
 * operation timeout, not a deadline for the entire operation. Process-tree discovery and stream cleanup may add work.
 *
 * <p>Graceful termination uses the JDK process termination mechanism; it does not promise a particular operating-system
 * signal such as SIGINT. Process-tree cleanup is best effort because descendants can detach or become unobservable.
 *
 * @param interruptGrace non-negative wait after requesting graceful termination; zero skips that wait
 * @param killGrace non-negative wait after requesting forceful termination; zero skips that wait
 */
public record ShutdownPolicy(Duration interruptGrace, Duration killGrace) {

    /**
     * Creates a shutdown policy.
     *
     * @param interruptGrace non-negative wait after requesting graceful termination
     * @param killGrace non-negative wait after requesting forceful termination
     * @throws IllegalArgumentException if either duration is negative
     */
    public ShutdownPolicy {
        Objects.requireNonNull(interruptGrace, "interruptGrace");
        Objects.requireNonNull(killGrace, "killGrace");
        requireNonNegative(interruptGrace, "interruptGrace");
        requireNonNegative(killGrace, "killGrace");
    }

    /**
     * Creates a policy that escalates from graceful to forceful termination after the graceful wait expires.
     *
     * @param interruptGrace non-negative wait after requesting graceful termination; zero skips that wait
     * @param killGrace non-negative wait after requesting forceful termination; zero skips that wait
     * @return a shutdown policy with graceful-then-forceful escalation
     * @throws IllegalArgumentException if either duration is negative
     */
    public static ShutdownPolicy interruptThenKill(Duration interruptGrace, Duration killGrace) {
        return new ShutdownPolicy(interruptGrace, killGrace);
    }

    private static void requireNonNegative(Duration duration, String name) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
