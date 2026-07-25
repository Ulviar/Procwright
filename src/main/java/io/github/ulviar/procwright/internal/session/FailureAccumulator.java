/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.FailureAggregation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Caller-confined collector of ordered failure identities. */
final class FailureAccumulator {

    private List<Throwable> observed;

    boolean add(Throwable failure) {
        boolean added = false;
        if (failure == null) {
            return false;
        }
        Throwable primary = FailureAggregation.primary(failure);
        added = addIdentity(primary);
        for (Throwable source : FailureAggregation.sources(failure)) {
            if (source != primary) {
                added |= addIdentity(source);
            }
        }
        return added;
    }

    Throwable aggregateErrorFirst(String message) {
        if (observed == null) {
            return null;
        }
        Throwable primary = observed.get(0);
        for (Throwable failure : observed) {
            if (failure instanceof Error) {
                primary = failure;
                break;
            }
        }
        return FailureAggregation.combineWithPrimary(primary, observed, Objects.requireNonNull(message, "message"));
    }

    private boolean addIdentity(Throwable failure) {
        if (containsIdentity(failure)) {
            return false;
        }
        if (observed == null) {
            observed = new ArrayList<>();
        }
        observed.add(failure);
        return true;
    }

    private boolean containsIdentity(Throwable candidate) {
        if (observed == null) {
            return false;
        }
        for (Throwable failure : observed) {
            if (failure == candidate) {
                return true;
            }
        }
        return false;
    }
}
