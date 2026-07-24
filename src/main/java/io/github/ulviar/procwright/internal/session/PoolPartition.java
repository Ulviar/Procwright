/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the mutually exclusive lifecycle partition of one worker pool.
 *
 * <p>The collection containing a worker is its state. Workers do not carry a second mutable state flag.
 */
final class PoolPartition<W> {

    private final int maxSize;
    private final Set<W> starting = identitySet();
    private final ArrayDeque<W> idle = new ArrayDeque<>();
    private final Set<W> leased = identitySet();
    private final Set<W> retiring = identitySet();

    PoolPartition(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
    }

    void addStarting(W worker) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (size() >= maxSize) {
            throw new IllegalStateException("pool capacity is exhausted");
        }
        requireAbsent(candidate);
        starting.add(candidate);
    }

    W leaseIdle() {
        W worker = idle.pollFirst();
        if (worker != null) {
            leased.add(worker);
        }
        return worker;
    }

    void startingToLeased(W worker) {
        moveFromSet(starting, worker, leased, State.STARTING);
    }

    void startingToRetiring(W worker) {
        moveToRetiring(starting, worker, State.STARTING);
    }

    void leasedToIdle(W worker) {
        W candidate = removeFromSet(leased, worker, State.LEASED);
        idle.addLast(candidate);
    }

    void leasedToRetiring(W worker) {
        moveToRetiring(leased, worker, State.LEASED);
    }

    void idleToRetiring(W worker) {
        W candidate = removeIdle(worker);
        retiring.add(candidate);
    }

    void removeStarting(W worker) {
        removeFromSet(starting, worker, State.STARTING);
    }

    void removeRetiring(W worker) {
        removeFromSet(retiring, worker, State.RETIRING);
    }

    boolean contains(W worker) {
        return stateOf(worker) != null;
    }

    boolean is(W worker, State expected) {
        return stateOf(worker) == Objects.requireNonNull(expected, "expected");
    }

    State requireState(W worker, State expected) {
        State actual = stateOf(worker);
        if (actual != expected) {
            throw new IllegalStateException("worker state must be " + expected);
        }
        return actual;
    }

    State stateOf(W worker) {
        if (worker == null) {
            return null;
        }
        if (starting.contains(worker)) {
            return State.STARTING;
        }
        if (containsIdentity(idle, worker)) {
            return State.IDLE;
        }
        if (leased.contains(worker)) {
            return State.LEASED;
        }
        if (retiring.contains(worker)) {
            return State.RETIRING;
        }
        return null;
    }

    int size() {
        return starting.size() + idle.size() + leased.size() + retiring.size();
    }

    int idleCount() {
        return idle.size();
    }

    Counts counts() {
        return new Counts(size(), idle.size(), leased.size(), starting.size(), retiring.size());
    }

    List<W> startingWorkers() {
        return List.copyOf(starting);
    }

    List<W> idleWorkers() {
        return List.copyOf(idle);
    }

    List<W> leasedWorkers() {
        return List.copyOf(leased);
    }

    List<W> retiringWorkers() {
        return List.copyOf(retiring);
    }

    List<W> workers() {
        List<W> workers = new ArrayList<>(size());
        workers.addAll(starting);
        workers.addAll(idle);
        workers.addAll(leased);
        workers.addAll(retiring);
        return List.copyOf(workers);
    }

    void verify() {
        if (size() > maxSize) {
            throw new IllegalStateException("pool partition exceeds configured capacity");
        }
        Set<W> observed = identitySet();
        addUnique(observed, starting);
        addUnique(observed, idle);
        addUnique(observed, leased);
        addUnique(observed, retiring);
        if (observed.size() != size()) {
            throw new IllegalStateException("pool partition is inconsistent");
        }
    }

    private void moveFromSet(Set<W> source, W worker, Set<W> target, State expected) {
        W candidate = removeFromSet(source, worker, expected);
        target.add(candidate);
    }

    private void moveToRetiring(Set<W> source, W worker, State expected) {
        W candidate = removeFromSet(source, worker, expected);
        retiring.add(candidate);
    }

    private W removeFromSet(Set<W> source, W worker, State expected) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (!source.remove(candidate)) {
            throw new IllegalStateException("worker state must be " + expected);
        }
        return candidate;
    }

    private W removeIdle(W worker) {
        W candidate = Objects.requireNonNull(worker, "worker");
        for (var iterator = idle.iterator(); iterator.hasNext(); ) {
            W current = iterator.next();
            if (current == candidate) {
                iterator.remove();
                return candidate;
            }
        }
        throw new IllegalStateException("worker state must be " + State.IDLE);
    }

    private void requireAbsent(W worker) {
        if (contains(worker)) {
            throw new IllegalStateException("worker already belongs to the pool");
        }
    }

    private static <W> boolean containsIdentity(Iterable<W> values, W candidate) {
        for (W value : values) {
            if (value == candidate) {
                return true;
            }
        }
        return false;
    }

    private static <W> void addUnique(Set<W> observed, Iterable<W> values) {
        for (W value : values) {
            if (!observed.add(value)) {
                throw new IllegalStateException("worker belongs to more than one pool state");
            }
        }
    }

    private static <W> Set<W> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    enum State {
        STARTING,
        IDLE,
        LEASED,
        RETIRING
    }

    record Counts(int size, int idle, int leased, int starting, int retiring) {}
}
