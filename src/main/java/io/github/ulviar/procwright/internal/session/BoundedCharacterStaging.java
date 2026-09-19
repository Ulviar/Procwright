/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.nio.charset.CharacterCodingException;
import java.util.Objects;
import java.util.function.IntFunction;

/** Buffers one decode operation so its output is published only after that operation succeeds. */
final class BoundedCharacterStaging implements IncrementalTextDecoder.Sink {

    private static final char[] EMPTY = new char[0];
    private static final int MAX_RETAINED_CHARS = 8192;

    private final IntFunction<char[]> allocator;
    private char[] chars = EMPTY;
    private int limit;
    private int length;

    BoundedCharacterStaging() {
        this(char[]::new);
    }

    BoundedCharacterStaging(IntFunction<char[]> allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    void reset(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        length = 0;
    }

    @Override
    public void accept(char[] source, int count) throws IncrementalTextDecoder.DecoderStateException {
        Objects.requireNonNull(source, "source");
        Objects.checkFromIndexSize(0, count, source.length);
        if (count > limit - length) {
            throw new IncrementalTextDecoder.DecoderStateException(
                    "Decoder produced more than " + limit + " chars in one decode operation");
        }
        ensureCapacity(length + count);
        System.arraycopy(source, 0, chars, length, count);
        length += count;
    }

    void publishTo(IncrementalTextDecoder.Sink target) throws CharacterCodingException {
        Objects.requireNonNull(target, "target");
        if (length > 0) {
            target.accept(chars, length);
        }
    }

    int length() {
        return length;
    }

    int remainingCapacity() {
        return limit - length;
    }

    void finishOperation() {
        length = 0;
        if (chars.length > MAX_RETAINED_CHARS) {
            chars = EMPTY;
        }
    }

    private void ensureCapacity(int required) throws IncrementalTextDecoder.DecoderStateException {
        if (required <= chars.length) {
            return;
        }
        int doubled = chars.length > limit - chars.length ? limit : chars.length * 2;
        int capacity = Math.max(required, Math.max(1, doubled));
        char[] grown = Objects.requireNonNull(allocator.apply(capacity), "stagingAllocator returned null");
        if (grown.length < capacity) {
            throw new IncrementalTextDecoder.DecoderStateException(
                    "Staging allocator returned " + grown.length + " chars for capacity " + capacity);
        }
        System.arraycopy(chars, 0, grown, 0, length);
        chars = grown;
    }
}
