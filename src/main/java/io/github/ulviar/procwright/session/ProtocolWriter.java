/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Arrays;
import java.util.Objects;

/**
 * Deadline-aware protocol request writer.
 *
 * <p>A writer is valid only on the thread executing the {@link ProtocolAdapter#writeRequest(Object, ProtocolWriter)}
 * callback and only until that callback returns. Using it from another thread or request throws {@link
 * IllegalStateException} before stdin is accessed.
 */
public interface ProtocolWriter {

    /**
     * Writes bytes to stdin.
     *
     * @param bytes bytes to write
     * @throws ProtocolSessionException when request size limits are exceeded or stdin cannot be written
     */
    void write(byte[] bytes);

    /**
     * Writes one byte-array slice to stdin.
     *
     * <p>The default implementation preserves compatibility for custom writers by copying a partial slice before
     * delegating to {@link #write(byte[])}. Procwright's runtime implementation writes the slice directly.
     *
     * @param bytes source bytes
     * @param offset first byte to write
     * @param length number of bytes to write
     * @throws ProtocolSessionException when request size limits are exceeded or stdin cannot be written
     */
    default void write(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        write(offset == 0 && length == bytes.length ? bytes : Arrays.copyOfRange(bytes, offset, offset + length));
    }

    /**
     * Returns the request byte capacity remaining for this callback.
     *
     * <p>Custom writer implementations that do not expose a bound retain the compatible unbounded default. The value
     * is only a preflight hint; every write remains authoritative and must enforce its own limits.
     *
     * @return remaining byte capacity, or {@link Long#MAX_VALUE} when unknown
     */
    default long remainingByteCapacity() {
        return Long.MAX_VALUE;
    }

    /**
     * Verifies that a complete request fragment can fit before any of it is written.
     *
     * <p>The compatible default validates only the argument. Procwright's runtime implementation also checks the
     * callback scope, deadline, and configured request-byte limit.
     *
     * @param byteCount complete fragment size
     * @throws IllegalArgumentException when {@code byteCount} is negative
     * @throws ProtocolSessionException when the fragment exceeds the remaining request capacity
     */
    default void ensureByteCapacity(long byteCount) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
    }

    /**
     * Writes text using the session charset policy charset.
     *
     * @param text text to write
     * @throws ProtocolSessionException when request size limits are exceeded or stdin cannot be written
     */
    void write(String text);

    /**
     * Writes text followed by {@code \n}.
     *
     * @param line line text
     * @throws ProtocolSessionException when request size limits are exceeded or stdin cannot be written
     */
    void writeLine(String line);

    /**
     * Flushes stdin.
     *
     * @throws ProtocolSessionException when stdin cannot be flushed
     */
    void flush();
}
