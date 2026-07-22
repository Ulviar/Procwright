/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Bounded byte queue between protocol output pumps and deadline-aware protocol readers.
 *
 * <p>The overflow policy is per stream: the strict policy rejects bytes beyond the limit so the
 * output pump can fail the session immediately. The fail-on-read policy discards all pending bytes
 * and retains one bounded overflow marker. Its pump remains healthy, but no reader can observe a
 * potentially valid suffix after bytes were lost.
 */
final class ProtocolOutputQueue {

    enum OverflowPolicy {
        /**
         * Reject bytes beyond the limit; the caller turns the rejection into a typed session failure.
         */
        STRICT,
        /** Keep overflow nonfatal until a reader observes a bounded terminal marker. */
        FAIL_ON_READ
    }

    private final int byteLimit;
    private final OverflowPolicy overflowPolicy;
    private final LongSupplier nanoTime;
    private final Runnable beforeWait;
    private final Runnable beforeTimeoutFailure;
    private final Supplier<OptionalInt> processExitCodeSnapshot;
    private final Runnable readTransactionObserver;
    private final ArrayDeque<ProtocolOutputEvent> events = new ArrayDeque<>();
    private long pendingBytes;
    private int headByteOffset;
    private boolean closed;
    private boolean terminal;

    ProtocolOutputQueue(int byteLimit, OverflowPolicy overflowPolicy) {
        this(byteLimit, overflowPolicy, System::nanoTime, () -> {}, () -> {}, OptionalInt::empty, null);
    }

    ProtocolOutputQueue(int byteLimit, OverflowPolicy overflowPolicy, LongSupplier nanoTime, Runnable beforeWait) {
        this(byteLimit, overflowPolicy, nanoTime, beforeWait, () -> {}, OptionalInt::empty, null);
    }

    ProtocolOutputQueue(
            int byteLimit,
            OverflowPolicy overflowPolicy,
            LongSupplier nanoTime,
            Runnable beforeWait,
            Runnable beforeTimeoutFailure) {
        this(byteLimit, overflowPolicy, nanoTime, beforeWait, beforeTimeoutFailure, OptionalInt::empty, null);
    }

    ProtocolOutputQueue(
            int byteLimit,
            OverflowPolicy overflowPolicy,
            LongSupplier nanoTime,
            Runnable beforeWait,
            Runnable beforeTimeoutFailure,
            Runnable readTransactionObserver) {
        this(
                byteLimit,
                overflowPolicy,
                nanoTime,
                beforeWait,
                beforeTimeoutFailure,
                OptionalInt::empty,
                readTransactionObserver);
    }

    ProtocolOutputQueue(
            int byteLimit,
            OverflowPolicy overflowPolicy,
            LongSupplier nanoTime,
            Runnable beforeWait,
            Runnable beforeTimeoutFailure,
            Supplier<OptionalInt> processExitCodeSnapshot,
            Runnable readTransactionObserver) {
        this.byteLimit = byteLimit;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.beforeWait = Objects.requireNonNull(beforeWait, "beforeWait");
        this.beforeTimeoutFailure = Objects.requireNonNull(beforeTimeoutFailure, "beforeTimeoutFailure");
        this.processExitCodeSnapshot = Objects.requireNonNull(processExitCodeSnapshot, "processExitCodeSnapshot");
        this.readTransactionObserver = readTransactionObserver;
    }

    synchronized boolean offer(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || closed || terminal) {
            return true;
        }
        if (pendingBytes + bytes.length > byteLimit) {
            if (overflowPolicy == OverflowPolicy.STRICT) {
                return false;
            }
            retainOverflowMarker();
            return true;
        }
        pendingBytes += bytes.length;
        events.addLast(ProtocolOutputEvent.bytes(bytes));
        notifyAll();
        return true;
    }

    synchronized void eof() {
        eof(OptionalInt.empty());
    }

    synchronized void eof(OptionalInt processExitCode) {
        Objects.requireNonNull(processExitCode, "processExitCode");
        if (closed || terminal) {
            return;
        }
        events.addLast(ProtocolOutputEvent.eof(processExitCode));
        terminal = true;
        notifyAll();
    }

    synchronized void close() {
        events.clear();
        pendingBytes = 0;
        headByteOffset = 0;
        events.addLast(ProtocolOutputEvent.closed());
        closed = true;
        terminal = true;
        notifyAll();
    }

    private void retainOverflowMarker() {
        events.clear();
        pendingBytes = 0;
        headByteOffset = 0;
        events.addLast(ProtocolOutputEvent.failure(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW,
                new IllegalStateException("Protocol output backlog overflow")));
        terminal = true;
        notifyAll();
    }

    synchronized void failure(ProtocolSessionException.Reason reason, Throwable failure) {
        if (closed || terminal) {
            return;
        }
        events.addLast(ProtocolOutputEvent.failure(reason, failure));
        terminal = true;
        notifyAll();
    }

    synchronized void failAndClear(ProtocolSessionException.Reason reason, Throwable failure) {
        if (closed) {
            return;
        }
        ProtocolOutputEvent head = events.peekFirst();
        if (terminal
                && head != null
                && head.kind() == ProtocolOutputEvent.Kind.FAILURE
                && head.reason() == reason
                && head.failure() == failure) {
            return;
        }
        events.clear();
        pendingBytes = 0;
        headByteOffset = 0;
        events.addLast(ProtocolOutputEvent.failure(reason, failure));
        terminal = true;
        notifyAll();
    }

    synchronized long pendingBytes() {
        return pendingBytes;
    }

    /**
     * Returns the terminal event currently at the read boundary without consuming it.
     *
     * <p>The caller materializes the typed failure after this method releases the queue monitor. Keeping the event in
     * place gives every caught retry the same bounded terminal state.
     */
    ProtocolOutputEvent peekTerminal(long deadlineNanos) {
        ProtocolOutputEvent head = headSnapshot();
        if (head != null && head.kind() != ProtocolOutputEvent.Kind.BYTES) {
            head = canonicalizeEofSnapshot(head, deadlineNanos);
        }
        return head != null && head.kind() != ProtocolOutputEvent.Kind.BYTES ? head : null;
    }

    ProtocolOutputEvent refreshTerminal(ProtocolOutputEvent terminalEvent, long deadlineNanos) {
        Objects.requireNonNull(terminalEvent, "terminalEvent");
        if (terminalEvent.kind() != ProtocolOutputEvent.Kind.EOF) {
            return terminalEvent;
        }
        ProtocolOutputEvent head = headSnapshot();
        return head != null && head.kind() == ProtocolOutputEvent.Kind.EOF
                ? canonicalizeEofSnapshot(head, deadlineNanos)
                : terminalEvent;
    }

    int peek(
            byte[] buffer,
            int offset,
            int length,
            ReadWindow window,
            long deadlineNanos,
            ProtocolRuntimeFailures failures,
            UnaryOperator<ProtocolOutputEvent> terminalObserver) {
        PeekResult result = peekResult(buffer, offset, length, window, deadlineNanos, failures);
        if (result.terminalEvent() != null) {
            throwTerminal(result.terminalEvent(), failures, terminalObserver);
        }
        return result.count();
    }

    PeekResult peekResult(
            byte[] buffer,
            int offset,
            int length,
            ReadWindow window,
            long deadlineNanos,
            ProtocolRuntimeFailures failures) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(failures, "failures");
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        window.requireAvailable();
        if (readTransactionObserver != null) {
            readTransactionObserver.run();
        }
        ProtocolOutputEvent event = awaitHead(deadlineNanos, failures);
        if (event.kind() != ProtocolOutputEvent.Kind.BYTES) {
            return PeekResult.terminal(event);
        }
        ProtocolOutputEvent initialHead;
        int sourceOffset;
        int count;
        synchronized (this) {
            initialHead = events.peekFirst();
            if (initialHead == event) {
                sourceOffset = headByteOffset;
                count = Math.min(length, event.bytes().length - sourceOffset);
                System.arraycopy(event.bytes(), sourceOffset, buffer, offset, count);
                window.capture(event, sourceOffset, count);
            } else {
                sourceOffset = 0;
                count = 0;
            }
        }
        if (initialHead != event) {
            if (initialHead != null && initialHead.kind() != ProtocolOutputEvent.Kind.BYTES) {
                return PeekResult.terminal(initialHead);
            }
            throw changedHeadFailure(failures);
        }
        return PeekResult.bytes(count);
    }

    void commit(
            ReadWindow window,
            int count,
            IntConsumer beforeMutation,
            ProtocolRuntimeFailures failures,
            UnaryOperator<ProtocolOutputEvent> terminalObserver) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(beforeMutation, "beforeMutation");
        Objects.requireNonNull(failures, "failures");
        Objects.requireNonNull(terminalObserver, "terminalObserver");
        window.requireCommitCount(count);
        try {
            beforeMutation.accept(count);
        } catch (RuntimeException | Error failure) {
            window.discard();
            throw failure;
        }
        ProtocolOutputEvent changedHead;
        synchronized (this) {
            changedHead = events.peekFirst();
            if (changedHead == window.event && headByteOffset == window.sourceOffset) {
                consumeHeadBytes(count, window.event.bytes().length);
                window.discard();
                return;
            }
        }
        window.discard();
        throwChangedHead(changedHead, failures, terminalObserver);
    }

    int read(byte[] buffer, int offset, int length, long deadlineNanos, ProtocolRuntimeFailures failures) {
        return read(buffer, offset, length, deadlineNanos, failures, ignored -> {}, event -> event);
    }

    int read(
            byte[] buffer,
            int offset,
            int length,
            long deadlineNanos,
            ProtocolRuntimeFailures failures,
            IntConsumer beforeMutation) {
        return read(buffer, offset, length, deadlineNanos, failures, beforeMutation, event -> event);
    }

    int read(
            byte[] buffer,
            int offset,
            int length,
            long deadlineNanos,
            ProtocolRuntimeFailures failures,
            IntConsumer beforeMutation,
            UnaryOperator<ProtocolOutputEvent> terminalObserver) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        Objects.requireNonNull(failures, "failures");
        Objects.requireNonNull(beforeMutation, "beforeMutation");
        Objects.requireNonNull(terminalObserver, "terminalObserver");
        if (length == 0) {
            return 0;
        }
        ProtocolOutputEvent event = awaitHead(deadlineNanos, failures);
        if (event.kind() != ProtocolOutputEvent.Kind.BYTES) {
            throwTerminal(event, failures, terminalObserver);
        }
        int sourceOffset;
        int count;
        ProtocolOutputEvent initialHead;
        synchronized (this) {
            initialHead = events.peekFirst();
            if (initialHead == event) {
                sourceOffset = headByteOffset;
                count = Math.min(length, event.bytes().length - sourceOffset);
            } else {
                sourceOffset = 0;
                count = 0;
            }
        }
        if (initialHead != event) {
            throwChangedHead(initialHead, failures, terminalObserver);
        }
        beforeMutation.accept(count);
        ProtocolOutputEvent changedHead;
        synchronized (this) {
            changedHead = events.peekFirst();
            if (changedHead == event && headByteOffset == sourceOffset) {
                System.arraycopy(event.bytes(), sourceOffset, buffer, offset, count);
                consumeHeadBytes(count, event.bytes().length);
                return count;
            }
        }
        throwChangedHead(changedHead, failures, terminalObserver);
        throw new AssertionError("unreachable");
    }

    int readUnsignedByte(long deadlineNanos, ProtocolRuntimeFailures failures) {
        return readUnsignedByte(deadlineNanos, failures, ignored -> {}, event -> event);
    }

    int readUnsignedByte(long deadlineNanos, ProtocolRuntimeFailures failures, IntConsumer beforeMutation) {
        return readUnsignedByte(deadlineNanos, failures, beforeMutation, event -> event);
    }

    int readUnsignedByte(
            long deadlineNanos,
            ProtocolRuntimeFailures failures,
            IntConsumer beforeMutation,
            UnaryOperator<ProtocolOutputEvent> terminalObserver) {
        Objects.requireNonNull(failures, "failures");
        Objects.requireNonNull(beforeMutation, "beforeMutation");
        Objects.requireNonNull(terminalObserver, "terminalObserver");
        ProtocolOutputEvent event = awaitHead(deadlineNanos, failures);
        if (event.kind() != ProtocolOutputEvent.Kind.BYTES) {
            throwTerminal(event, failures, terminalObserver);
        }
        int sourceOffset;
        int value;
        ProtocolOutputEvent initialHead;
        synchronized (this) {
            initialHead = events.peekFirst();
            if (initialHead == event) {
                sourceOffset = headByteOffset;
                value = event.bytes()[sourceOffset] & 0xff;
            } else {
                sourceOffset = 0;
                value = 0;
            }
        }
        if (initialHead != event) {
            throwChangedHead(initialHead, failures, terminalObserver);
        }
        beforeMutation.accept(1);
        ProtocolOutputEvent changedHead;
        synchronized (this) {
            changedHead = events.peekFirst();
            if (changedHead == event && headByteOffset == sourceOffset) {
                consumeHeadBytes(1, event.bytes().length);
                return value;
            }
        }
        throwChangedHead(changedHead, failures, terminalObserver);
        throw new AssertionError("unreachable");
    }

    private ProtocolOutputEvent awaitHead(long deadlineNanos, ProtocolRuntimeFailures failures) {
        ProtocolOutputEvent event = null;
        try {
            synchronized (this) {
                while (events.isEmpty()) {
                    long remainingNanos = deadlineNanos - nanoTime.getAsLong();
                    if (remainingNanos <= 0) {
                        break;
                    }
                    beforeWait.run();
                    TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                }
                event = events.peekFirst();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (deadlineNanos - nanoTime.getAsLong() <= 0) {
                beforeTimeoutFailure.run();
                throw failures.timeout(exception);
            }
            throw failures.interrupted("Interrupted while waiting for protocol output", exception);
        }
        if (event == null) {
            beforeTimeoutFailure.run();
            throw failures.timeout(null);
        }
        return canonicalizeEofSnapshot(event, deadlineNanos);
    }

    private synchronized ProtocolOutputEvent headSnapshot() {
        return events.peekFirst();
    }

    private ProtocolOutputEvent canonicalizeEofSnapshot(ProtocolOutputEvent snapshot, long deadlineNanos) {
        if (!snapshot.isEofWithoutProcessExit()) {
            return snapshot;
        }
        OptionalInt exitCode = queryProcessExitCode(deadlineNanos);
        synchronized (this) {
            ProtocolOutputEvent current = events.peekFirst();
            if (current != snapshot) {
                return current == null ? snapshot : current;
            }
            if (exitCode.isEmpty()) {
                return snapshot;
            }
            ProtocolOutputEvent canonical = ProtocolOutputEvent.eof(exitCode);
            events.removeFirst();
            events.addFirst(canonical);
            return canonical;
        }
    }

    private OptionalInt queryProcessExitCode(long deadlineNanos) {
        if (deadlineNanos - nanoTime.getAsLong() <= 0) {
            return OptionalInt.empty();
        }
        OptionalInt exitCode;
        try {
            exitCode = processExitCodeSnapshot.get();
        } catch (RuntimeException | Error unavailable) {
            return OptionalInt.empty();
        }
        if (deadlineNanos - nanoTime.getAsLong() <= 0) {
            return OptionalInt.empty();
        }
        return exitCode == null ? OptionalInt.empty() : exitCode;
    }

    private void consumeHeadBytes(int count, int chunkLength) {
        headByteOffset += count;
        pendingBytes -= count;
        if (headByteOffset == chunkLength) {
            events.removeFirst();
            headByteOffset = 0;
        }
    }

    private static void throwTerminal(
            ProtocolOutputEvent event,
            ProtocolRuntimeFailures failures,
            UnaryOperator<ProtocolOutputEvent> terminalObserver) {
        ProtocolOutputEvent observed = Objects.requireNonNull(terminalObserver.apply(event), "terminalObserver result");
        throw observed.terminalFailure(failures);
    }

    private static ProtocolSessionException changedHeadFailure(ProtocolRuntimeFailures failures) {
        return failures.failure(
                ProtocolSessionException.Reason.FAILURE,
                "Protocol output was consumed concurrently",
                new IllegalStateException("Concurrent protocol output reads are not supported"));
    }

    private static void throwChangedHead(
            ProtocolOutputEvent event,
            ProtocolRuntimeFailures failures,
            UnaryOperator<ProtocolOutputEvent> terminalObserver) {
        if (event != null && event.kind() != ProtocolOutputEvent.Kind.BYTES) {
            throwTerminal(event, failures, terminalObserver);
        }
        throw changedHeadFailure(failures);
    }

    record PeekResult(int count, ProtocolOutputEvent terminalEvent) {

        private static PeekResult bytes(int count) {
            return new PeekResult(count, null);
        }

        private static PeekResult terminal(ProtocolOutputEvent terminalEvent) {
            return new PeekResult(0, Objects.requireNonNull(terminalEvent, "terminalEvent"));
        }
    }

    static final class ReadWindow {

        private ProtocolOutputEvent event;
        private int sourceOffset;
        private int length;

        void discard() {
            event = null;
            sourceOffset = 0;
            length = 0;
        }

        private void capture(ProtocolOutputEvent event, int sourceOffset, int length) {
            this.event = Objects.requireNonNull(event, "event");
            this.sourceOffset = sourceOffset;
            this.length = length;
        }

        private void requireAvailable() {
            if (event != null) {
                throw new IllegalStateException("read window already has an uncommitted transaction");
            }
        }

        private void requireCommitCount(int count) {
            if (event == null) {
                throw new IllegalStateException("read window has no transaction to commit");
            }
            if (count <= 0 || count > length) {
                throw new IllegalArgumentException("count must be within the read window");
            }
        }
    }
}
