/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.nio.charset.CharacterCodingException;
import java.util.Objects;

/** Thread-confined bounded text retained between line reads of one process output stream. */
final class DecodedLineBuffer {

    private static final char[] EMPTY = new char[0];

    private final int limit;
    private char[] chars = EMPTY;
    private int start;
    private int end;

    DecodedLineBuffer(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    void append(char[] source, int offset, int count) throws LimitExceededException {
        Objects.checkFromIndexSize(offset, count, source.length);
        if (count == 0) {
            return;
        }
        if (count > remainingCapacity()) {
            throw new LimitExceededException(limit);
        }
        ensureCapacity(count);
        System.arraycopy(source, offset, chars, end, count);
        end += count;
    }

    int copyFirstLineTo(StringBuilder target) {
        Objects.requireNonNull(target, "target");
        int count = pendingCount();
        for (int index = start; index < end; index++) {
            if (chars[index] == '\n') {
                count = index - start + 1;
                break;
            }
        }
        target.append(chars, start, count);
        return count;
    }

    void consume(int count) {
        if (count < 0 || count > pendingCount()) {
            throw new IllegalArgumentException("count exceeds pending decoded line output");
        }
        start += count;
        clearIfEmpty();
    }

    int checkpoint() {
        return pendingCount();
    }

    void rollback(int checkpoint) {
        if (checkpoint < 0 || checkpoint > pendingCount()) {
            throw new IllegalArgumentException("checkpoint exceeds pending decoded line output");
        }
        end = start + checkpoint;
        clearIfEmpty();
    }

    boolean hasPending() {
        return start < end;
    }

    int remainingCapacity() {
        return limit - pendingCount();
    }

    private int pendingCount() {
        return end - start;
    }

    private void ensureCapacity(int additional) {
        int length = pendingCount();
        int required = length + additional;
        if (required <= chars.length) {
            if (start > 0 && additional > chars.length - end) {
                System.arraycopy(chars, start, chars, 0, length);
                start = 0;
                end = length;
            }
            return;
        }
        int doubled = chars.length > limit - chars.length ? limit : chars.length * 2;
        int capacity = Math.min(limit, Math.max(required, Math.max(1, doubled)));
        char[] grown = new char[capacity];
        System.arraycopy(chars, start, grown, 0, length);
        chars = grown;
        start = 0;
        end = length;
    }

    private void clearIfEmpty() {
        if (start == end) {
            start = 0;
            end = 0;
        }
    }

    static final class LimitExceededException extends CharacterCodingException {

        private static final long serialVersionUID = 1L;

        private final int limit;

        private LimitExceededException(int limit) {
            this.limit = limit;
        }

        int limit() {
            return limit;
        }

        @Override
        public String getMessage() {
            return "Decoded line buffer exceeds its " + limit + " char limit";
        }
    }
}
