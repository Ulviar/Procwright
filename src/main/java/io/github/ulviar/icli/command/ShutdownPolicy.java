package io.github.ulviar.icli.command;

import java.time.Duration;
import java.util.Objects;

/**
 * Defines the escalation path used when a process must be stopped.
 *
 * @param interruptGrace time allowed for graceful interruption
 * @param killGrace time allowed before forceful termination
 */
public record ShutdownPolicy(Duration interruptGrace, Duration killGrace) {

    /**
     * Creates a shutdown policy.
     *
     * @param interruptGrace time allowed for graceful interruption
     * @param killGrace time allowed before forceful termination
     */
    public ShutdownPolicy {
        Objects.requireNonNull(interruptGrace, "interruptGrace");
        Objects.requireNonNull(killGrace, "killGrace");
        requireNonNegative(interruptGrace, "interruptGrace");
        requireNonNegative(killGrace, "killGrace");
    }

    /**
     * Requests graceful interruption first and escalates to forceful termination after the configured grace periods.
     *
     * @param interruptGrace time allowed for graceful interruption
     * @param killGrace time allowed before forceful termination
     * @return a shutdown policy with graceful-then-forceful escalation
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
