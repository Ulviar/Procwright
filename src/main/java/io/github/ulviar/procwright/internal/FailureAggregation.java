/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Combines independent failures around a caller-selected primary without mutating any source.
 *
 * @hidden
 */
public final class FailureAggregation {

    private FailureAggregation() {}

    public static Throwable combine(Throwable first, Throwable second, String message) {
        if (first == null || first == second) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return combineWithPrimary(primarySource(first), List.of(first, second), message);
    }

    public static Throwable combine(List<? extends Throwable> failures, String message) {
        Objects.requireNonNull(failures, "failures");
        if (failures.isEmpty()) {
            return null;
        }
        Throwable first = Objects.requireNonNull(failures.get(0), "failure");
        boolean oneIdentity = true;
        for (Throwable failure : failures) {
            if (Objects.requireNonNull(failure, "failure") != first) {
                oneIdentity = false;
            }
        }
        if (oneIdentity) {
            return first;
        }
        List<Throwable> unique = uniqueFailures(failures);
        return combineWithPrimary(primarySource(first), unique, message);
    }

    public static Throwable combineWithPrimary(Throwable primary, List<? extends Throwable> failures, String message) {
        Throwable selectedPrimary = primarySource(Objects.requireNonNull(primary, "primary"));
        List<Throwable> unique = uniqueFailures(failures);
        if (!containsIdentity(unique, selectedPrimary)) {
            throw new IllegalArgumentException("primary must be one of the aggregated failures");
        }
        if (unique.size() == 1) {
            return selectedPrimary;
        }
        Objects.requireNonNull(message, "message");
        return switch (selectedPrimary) {
            case Error _ -> new AggregateError(message, selectedPrimary, unique);
            case RuntimeException _ -> new AggregateRuntimeException(message, selectedPrimary, unique);
            default -> new AggregateException(message, selectedPrimary, unique);
        };
    }

    /** Returns source identities for aggregates created by this class, or the supplied failure itself. */
    public static List<Throwable> sources(Throwable failure) {
        Throwable candidate = Objects.requireNonNull(failure, "failure");
        return candidate instanceof AggregateFailure aggregate ? aggregate.sources() : List.of(candidate);
    }

    /** Returns the selected primary identity for an aggregate created by this class, or the supplied failure itself. */
    public static Throwable primary(Throwable failure) {
        return primarySource(Objects.requireNonNull(failure, "failure"));
    }

    private static List<Throwable> uniqueFailures(List<? extends Throwable> failures) {
        Objects.requireNonNull(failures, "failures");
        ArrayList<Throwable> unique = new ArrayList<>(failures.size());
        IdentityHashMap<Throwable, Boolean> identities = new IdentityHashMap<>();
        for (Throwable failure : failures) {
            Throwable candidate = Objects.requireNonNull(failure, "failure");
            for (Throwable source : sources(candidate)) {
                if (identities.put(source, Boolean.TRUE) == null) {
                    unique.add(source);
                }
            }
        }
        return unique;
    }

    private static Throwable primarySource(Throwable failure) {
        return failure instanceof AggregateFailure aggregate ? aggregate.primary() : failure;
    }

    private static boolean containsIdentity(List<Throwable> failures, Throwable expected) {
        for (Throwable failure : failures) {
            if (failure == expected) {
                return true;
            }
        }
        return false;
    }

    private interface AggregateFailure {

        Throwable primary();

        List<Throwable> sources();
    }

    @SuppressWarnings("serial")
    private static final class AggregateException extends Exception implements AggregateFailure {

        private final Throwable primary;
        private final List<Throwable> sources;

        private AggregateException(String message, Throwable primary, List<Throwable> sources) {
            super(message, primary);
            this.primary = primary;
            this.sources = List.copyOf(sources);
            attachSecondary(this, primary, sources);
        }

        @Override
        public Throwable primary() {
            return primary;
        }

        @Override
        public List<Throwable> sources() {
            return sources;
        }
    }

    @SuppressWarnings("serial")
    private static final class AggregateRuntimeException extends RuntimeException implements AggregateFailure {

        private final Throwable primary;
        private final List<Throwable> sources;

        private AggregateRuntimeException(String message, Throwable primary, List<Throwable> sources) {
            super(message, primary);
            this.primary = primary;
            this.sources = List.copyOf(sources);
            attachSecondary(this, primary, sources);
        }

        @Override
        public Throwable primary() {
            return primary;
        }

        @Override
        public List<Throwable> sources() {
            return sources;
        }
    }

    @SuppressWarnings("serial")
    private static final class AggregateError extends Error implements AggregateFailure {

        private final Throwable primary;
        private final List<Throwable> sources;

        private AggregateError(String message, Throwable primary, List<Throwable> sources) {
            super(message, primary);
            this.primary = primary;
            this.sources = List.copyOf(sources);
            attachSecondary(this, primary, sources);
        }

        @Override
        public Throwable primary() {
            return primary;
        }

        @Override
        public List<Throwable> sources() {
            return sources;
        }
    }

    private static void attachSecondary(Throwable aggregate, Throwable primary, List<Throwable> sources) {
        for (Throwable source : sources) {
            if (source != primary) {
                aggregate.addSuppressed(source);
            }
        }
    }
}
