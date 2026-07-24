/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Carries an immutable, insertion-ordered, bounded snapshot of previously observed process descendants.
 *
 * <p>The caller supplies identities already computed by {@link ProcessTreeScanner}; this carrier never invokes
 * provider process APIs. Truncation and provider unavailability are the only watcher statuses retained across
 * refreshes. A caller deadline or interruption belongs to one observation and is handled by its lifecycle owner.
 *
 * <p>This type is public only for use by non-exported internal subpackages.
 */
public final class KnownDescendants {

    private static final int LIMIT = ProcessTreeScanner.shared().descendantLimit();
    private static final KnownDescendants EMPTY = new KnownDescendants(Map.of(), false, false);

    private final Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> handlesByIdentity;
    private final Set<ProcessHandle> handles;
    private final boolean truncated;
    private final boolean discoveryUnavailable;

    private KnownDescendants(
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> handlesByIdentity,
            boolean truncated,
            boolean discoveryUnavailable) {
        LinkedHashMap<ProcessTreeScanner.HandleIdentity, ProcessHandle> copy = new LinkedHashMap<>(handlesByIdentity);
        this.handlesByIdentity = Collections.unmodifiableMap(copy);
        handles = Collections.unmodifiableSet(new LinkedHashSet<>(copy.values()));
        this.truncated = truncated;
        this.discoveryUnavailable = discoveryUnavailable;
    }

    static KnownDescendants empty() {
        return EMPTY;
    }

    static KnownDescendants copyOf(
            Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> handlesByIdentity,
            boolean truncated,
            boolean discoveryUnavailable) {
        Objects.requireNonNull(handlesByIdentity, "handlesByIdentity");
        if (handlesByIdentity.size() > LIMIT) {
            throw new IllegalArgumentException("known descendants exceed the bounded process-tree limit");
        }
        handlesByIdentity.forEach((identity, handle) -> {
            Objects.requireNonNull(identity, "descendant identity");
            Objects.requireNonNull(handle, "descendant handle");
        });
        return handlesByIdentity.isEmpty() && !truncated && !discoveryUnavailable
                ? EMPTY
                : new KnownDescendants(handlesByIdentity, truncated, discoveryUnavailable);
    }

    Set<ProcessHandle> handles() {
        return handles;
    }

    Map<ProcessTreeScanner.HandleIdentity, ProcessHandle> handlesByIdentity() {
        return handlesByIdentity;
    }

    boolean truncated() {
        return truncated;
    }

    boolean discoveryUnavailable() {
        return discoveryUnavailable;
    }
}
