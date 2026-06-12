/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Bounded byte queue between protocol output pumps and deadline-aware protocol readers.
 *
 * <p>The overflow policy is per stream: the strict policy rejects bytes beyond the limit so the session can fail with
 * a typed backlog overflow, while the drop-oldest policy retains only the newest pending bytes so an unread stream can
 * never fail the session or grow without bound.
 */
final class ProtocolOutputQueue {

    enum OverflowPolicy {
        /** Reject bytes beyond the limit; the caller turns the rejection into a typed session failure. */
        STRICT,
        /** Drop the oldest pending bytes beyond the limit and accept the new bytes. */
        DROP_OLDEST
    }

    private final int byteLimit;
    private final OverflowPolicy overflowPolicy;
    private final ArrayDeque<ProtocolOutputEvent> events = new ArrayDeque<>();
    private long pendingBytes;
    private boolean closed;

    ProtocolOutputQueue(int byteLimit, OverflowPolicy overflowPolicy) {
        this.byteLimit = byteLimit;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
    }

    synchronized boolean offer(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (closed) {
            return true;
        }
        if (pendingBytes + bytes.length > byteLimit) {
            if (overflowPolicy == OverflowPolicy.STRICT) {
                return false;
            }
            dropOldestPendingBytes(bytes);
            if (pendingBytes + bytes.length > byteLimit) {
                // The new chunk alone exceeds the limit; keep only its newest bytes.
                bytes = Arrays.copyOfRange(bytes, bytes.length - byteLimit, bytes.length);
            }
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
        closed = true;
        notifyAll();
    }

    private void dropOldestPendingBytes(byte[] incoming) {
        while (pendingBytes + incoming.length > byteLimit) {
            ProtocolOutputEvent oldest = events.peekFirst();
            if (oldest == null || oldest.kind() != ProtocolOutputKind.BYTES) {
                return;
            }
            long excess = pendingBytes + incoming.length - byteLimit;
            byte[] data = oldest.bytes();
            events.removeFirst();
            if (data.length <= excess) {
                pendingBytes -= data.length;
            } else {
                byte[] trimmed = Arrays.copyOfRange(data, (int) excess, data.length);
                events.addFirst(ProtocolOutputEvent.bytes(trimmed));
                pendingBytes -= excess;
                return;
            }
        }
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
