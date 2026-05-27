package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Bounded byte queue between protocol output pumps and deadline-aware protocol readers.
 */
final class ProtocolOutputQueue {

    private final int byteLimit;
    private final ArrayDeque<ProtocolOutputEvent> events = new ArrayDeque<>();
    private long pendingBytes;

    ProtocolOutputQueue(int byteLimit) {
        this.byteLimit = byteLimit;
    }

    synchronized boolean offer(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (pendingBytes + bytes.length > byteLimit) {
            return false;
        }
        pendingBytes += bytes.length;
        events.addLast(ProtocolOutputEvent.bytes(bytes));
        notifyAll();
        return true;
    }

    synchronized void eof() {
        events.addLast(ProtocolOutputEvent.eof());
        notifyAll();
    }

    synchronized void close() {
        events.clear();
        pendingBytes = 0;
        events.addLast(ProtocolOutputEvent.closed());
        notifyAll();
    }

    synchronized void failure(ProtocolSessionException.Reason reason, Throwable failure) {
        events.addLast(ProtocolOutputEvent.failure(reason, failure));
        notifyAll();
    }

    synchronized void failAndClear(ProtocolSessionException.Reason reason, Throwable failure) {
        events.clear();
        pendingBytes = 0;
        events.addLast(ProtocolOutputEvent.failure(reason, failure));
        notifyAll();
    }

    synchronized ProtocolOutputEvent take(long deadlineNanos, ProtocolRuntimeFailures failures) {
        Objects.requireNonNull(failures, "failures");
        while (events.isEmpty()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw failures.timeout(null);
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failures.failure(
                        ProtocolSessionException.Reason.FAILURE,
                        "Interrupted while waiting for protocol output",
                        exception);
            }
        }
        ProtocolOutputEvent event = events.removeFirst();
        if (event.kind() == ProtocolOutputKind.BYTES) {
            pendingBytes -= event.bytes().length;
        }
        return event;
    }
}

record ProtocolOutputEvent(
        ProtocolOutputKind kind, byte[] bytes, ProtocolSessionException.Reason reason, Throwable failure) {

    static ProtocolOutputEvent bytes(byte[] bytes) {
        return new ProtocolOutputEvent(ProtocolOutputKind.BYTES, bytes, null, null);
    }

    static ProtocolOutputEvent eof() {
        return new ProtocolOutputEvent(ProtocolOutputKind.EOF, null, null, null);
    }

    static ProtocolOutputEvent closed() {
        return new ProtocolOutputEvent(ProtocolOutputKind.CLOSED, null, null, null);
    }

    static ProtocolOutputEvent failure(ProtocolSessionException.Reason reason, Throwable failure) {
        return new ProtocolOutputEvent(ProtocolOutputKind.FAILURE, null, reason, failure);
    }

    ProtocolOutputEvent {
        Objects.requireNonNull(kind, "kind");
        if (kind == ProtocolOutputKind.BYTES) {
            Objects.requireNonNull(bytes, "bytes");
        }
        if (kind == ProtocolOutputKind.FAILURE) {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(failure, "failure");
        }
    }
}

enum ProtocolOutputKind {
    BYTES,
    EOF,
    CLOSED,
    FAILURE
}
