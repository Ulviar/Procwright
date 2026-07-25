/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayDeque;
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
    private final Set<W> starting;
    private final ArrayDeque<W> idle;
    private final Set<W> leased;
    private final Set<W> retiring;

    PoolPartition(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
        starting = identitySet(maxSize);
        idle = new ArrayDeque<>(maxSize);
        leased = identitySet(maxSize);
        retiring = identitySet(maxSize);
    }

    void addStarting(W worker) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (size() >= maxSize) {
            throw new IllegalStateException("pool capacity is exhausted");
        }
        requireAbsent(candidate);
        addTarget(starting, candidate);
    }

    W leaseIdle() {
        W worker = idle.peekFirst();
        if (worker == null) {
            return null;
        }
        addTarget(leased, worker);
        W removed = idle.removeFirst();
        if (removed != worker) {
            leased.remove(worker);
            throw new IllegalStateException("pool idle order changed while leasing");
        }
        return worker;
    }

    W firstIdle() {
        return idle.peekFirst();
    }

    void startingToLeased(W worker) {
        moveFromSet(starting, worker, leased, State.STARTING);
    }

    void startingToRetiring(W worker) {
        moveToRetiring(starting, worker, State.STARTING);
    }

    void leasedToIdle(W worker) {
        W candidate = requireInSet(leased, worker, State.LEASED);
        idle.addLast(candidate);
        if (!leased.remove(candidate)) {
            removeIdle(candidate);
            throw new IllegalStateException("worker state must be " + State.LEASED);
        }
    }

    void leasedToRetiring(W worker) {
        moveToRetiring(leased, worker, State.LEASED);
    }

    void idleToRetiring(W worker) {
        W candidate = requireIdle(worker);
        addTarget(retiring, candidate);
        try {
            removeIdle(candidate);
        } catch (RuntimeException | Error failure) {
            retiring.remove(candidate);
            throw failure;
        }
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

    private void moveFromSet(Set<W> source, W worker, Set<W> target, State expected) {
        W candidate = requireInSet(source, worker, expected);
        addTarget(target, candidate);
        if (!source.remove(candidate)) {
            target.remove(candidate);
            throw new IllegalStateException("worker state must be " + expected);
        }
    }

    private void moveToRetiring(Set<W> source, W worker, State expected) {
        moveFromSet(source, worker, retiring, expected);
    }

    private W removeFromSet(Set<W> source, W worker, State expected) {
        W candidate = requireInSet(source, worker, expected);
        removeKnown(source, candidate, expected);
        return candidate;
    }

    private W requireInSet(Set<W> source, W worker, State expected) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (!source.contains(candidate)) {
            throw new IllegalStateException("worker state must be " + expected);
        }
        return candidate;
    }

    private void removeKnown(Set<W> source, W worker, State expected) {
        if (!source.remove(worker)) {
            throw new IllegalStateException("worker state must be " + expected);
        }
    }

    private W requireIdle(W worker) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (!containsIdentity(idle, candidate)) {
            throw new IllegalStateException("worker state must be " + State.IDLE);
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

    private static <W> void addTarget(Set<W> target, W worker) {
        if (!target.add(worker)) {
            throw new IllegalStateException("worker already belongs to the target pool state");
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

    private static <W> Set<W> identitySet(int expectedSize) {
        return Collections.newSetFromMap(new IdentityHashMap<>(expectedSize));
    }

    enum State {
        STARTING,
        IDLE,
        LEASED,
        RETIRING
    }

    record Counts(int size, int idle, int leased, int starting, int retiring) {}
}
