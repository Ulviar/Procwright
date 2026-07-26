/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the mutually exclusive lifecycle partition of one worker pool.
 *
 * <p>One identity map is the canonical worker state. The idle queue is only the FIFO acquisition
 * index.
 */
final class PoolPartition<W> {

    private final int maxSize;
    private final IdentityHashMap<W, State> states;
    private final ArrayDeque<W> idle;

    PoolPartition(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
        states = new IdentityHashMap<>(maxSize);
        idle = new ArrayDeque<>(maxSize);
    }

    void addStarting(W worker) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (size() >= maxSize) {
            throw new IllegalStateException("pool capacity is exhausted");
        }
        if (states.putIfAbsent(candidate, State.STARTING) != null) {
            throw new IllegalStateException("worker already belongs to the pool");
        }
    }

    W leaseIdle() {
        W worker = idle.peekFirst();
        if (worker == null) {
            return null;
        }
        transition(worker, State.IDLE, State.LEASED);
        idle.removeFirst();
        return worker;
    }

    W firstIdle() {
        return idle.peekFirst();
    }

    void startingToLeased(W worker) {
        transition(worker, State.STARTING, State.LEASED);
    }

    void startingToRetiring(W worker) {
        transition(worker, State.STARTING, State.RETIRING);
    }

    void leasedToIdle(W worker) {
        W candidate = requireStateWorker(worker, State.LEASED);
        states.put(candidate, State.IDLE);
        idle.addLast(candidate);
    }

    void leasedToRetiring(W worker) {
        transition(worker, State.LEASED, State.RETIRING);
    }

    void idleToRetiring(W worker) {
        W candidate = requireStateWorker(worker, State.IDLE);
        removeIdle(candidate);
        states.put(candidate, State.RETIRING);
    }

    void removeStarting(W worker) {
        remove(worker, State.STARTING);
    }

    void removeRetiring(W worker) {
        remove(worker, State.RETIRING);
    }

    boolean contains(W worker) {
        return worker != null && states.containsKey(worker);
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
        return worker == null ? null : states.get(worker);
    }

    int size() {
        return states.size();
    }

    boolean hasCapacity() {
        return size() < maxSize;
    }

    int idleCount() {
        return idle.size();
    }

    Counts counts() {
        int leased = 0;
        int starting = 0;
        int retiring = 0;
        for (State state : states.values()) {
            switch (state) {
                case IDLE -> {}
                case LEASED -> leased++;
                case STARTING -> starting++;
                case RETIRING -> retiring++;
            }
        }
        return new Counts(size(), idle.size(), leased, starting, retiring);
    }

    List<W> startingWorkers() {
        return workersIn(State.STARTING);
    }

    List<W> idleWorkers() {
        return List.copyOf(idle);
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

    private void transition(W worker, State expected, State target) {
        W candidate = requireStateWorker(worker, expected);
        states.put(candidate, target);
    }

    private void remove(W worker, State expected) {
        W candidate = requireStateWorker(worker, expected);
        states.remove(candidate);
    }

    private W requireStateWorker(W worker, State expected) {
        W candidate = Objects.requireNonNull(worker, "worker");
        if (states.get(candidate) != expected) {
            throw new IllegalStateException("worker state must be " + expected);
        }
        return candidate;
    }

    private List<W> workersIn(State expected) {
        java.util.ArrayList<W> selected = new java.util.ArrayList<>();
        for (Map.Entry<W, State> entry : states.entrySet()) {
            if (entry.getValue() == expected) {
                selected.add(entry.getKey());
            }
        }
        return List.copyOf(selected);
    }

    enum State {
        STARTING,
        IDLE,
        LEASED,
        RETIRING
    }

    record Counts(int size, int idle, int leased, int starting, int retiring) {}
}
