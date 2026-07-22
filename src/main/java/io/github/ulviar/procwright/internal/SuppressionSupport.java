/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** @hidden */
public final class SuppressionSupport {

    private static final Object GRAPH_MUTATION_LOCK = new Object();

    private SuppressionSupport() {}

    public static void attach(Throwable primary, Throwable secondary) {
        Objects.requireNonNull(primary, "primary");
        if (secondary == null || primary == secondary) {
            return;
        }
        synchronized (GRAPH_MUTATION_LOCK) {
            if (alreadySuppressed(primary, secondary)
                    || relatedOrCannotBeSafelyTraversed(primary, secondary)
                    || relatedOrCannotBeSafelyTraversed(secondary, primary)) {
                return;
            }
            try {
                primary.addSuppressed(secondary);
            } catch (RuntimeException | Error ignored) {
                // Suppression is secondary bookkeeping and must never replace the primary failure.
            }
        }
    }

    public static void attachDirect(Throwable primary, Throwable secondary) {
        Objects.requireNonNull(primary, "primary");
        if (secondary == null || primary == secondary) {
            return;
        }
        synchronized (GRAPH_MUTATION_LOCK) {
            if (alreadySuppressed(primary, secondary) || relatedOrCannotBeSafelyTraversed(secondary, primary)) {
                return;
            }
            try {
                primary.addSuppressed(secondary);
            } catch (RuntimeException | Error ignored) {
                // Suppression is secondary bookkeeping and must never replace the primary failure.
            }
        }
    }

    public static Throwable combine(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        attach(primary, secondary);
        return primary;
    }

    public static boolean containsInterruption(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof InterruptedException) {
                return true;
            }
            try {
                current = current.getCause();
            } catch (RuntimeException | Error hostileCause) {
                return false;
            }
        }
        return false;
    }

    private static boolean alreadySuppressed(Throwable primary, Throwable candidate) {
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == candidate) {
                return true;
            }
        }
        return false;
    }

    private static boolean relatedOrCannotBeSafelyTraversed(Throwable root, Throwable target) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeLast();
            if (current == target) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            try {
                Throwable cause = current.getCause();
                if (cause != null) {
                    pending.add(cause);
                }
                for (Throwable suppressed : current.getSuppressed()) {
                    if (suppressed != null) {
                        pending.add(suppressed);
                    }
                }
            } catch (RuntimeException | Error hostileGraph) {
                // When safety cannot be proven, skipping optional suppression preserves the primary failure.
                return true;
            }
        }
        return false;
    }
}
