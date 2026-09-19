/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Arrays;
import java.util.Objects;

/**
 * Deadline-aware protocol request writer.
 *
 * <p>Writes do not flush automatically. Call {@link #flush()} after framing the complete request and before the
 * adapter's write callback returns. Byte writes consume the request-byte budget; text writes also consume the
 * request-character budget, measured in UTF-16 code units. All writes share the current request deadline and budgets.
 *
 * <p>A writer is valid only on the thread executing the {@link ProtocolAdapter#writeRequest(Object, ProtocolWriter)}
 * callback and only until that callback returns. Using it from another thread or request throws {@link
 * IllegalStateException} before stdin is accessed.
 */
public interface ProtocolWriter {

    /**
     * Writes bytes to stdin without flushing.
     *
     * @param bytes bytes to write
     * @throws ProtocolSessionException if the deadline expires, a request limit is exceeded, or writing fails
     */
    void write(byte[] bytes);

    /**
     * Writes one byte-array slice to stdin without flushing.
     *
     * <p>The default implementation preserves compatibility for custom writers by copying a partial slice before
     * delegating to {@link #write(byte[])}. Procwright's runtime implementation writes the slice directly.
     *
     * @param bytes source bytes
     * @param offset first byte to write
     * @param length number of bytes to write, at least zero
     * @throws IndexOutOfBoundsException if the offset and length do not identify a valid array range
     * @throws ProtocolSessionException if the deadline expires, a request limit is exceeded, or writing fails
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
     * @throws ProtocolSessionException if the deadline expires in a Procwright runtime writer
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
     * @throws ProtocolSessionException if the deadline expires or the fragment exceeds remaining request capacity
     */
    default void ensureByteCapacity(long byteCount) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
    }

    /**
     * Encodes and writes text with the session charset without flushing.
     *
     * <p>The charset policy's malformed-input mode applies to response decoding. Request encoding replaces malformed
     * or unmappable text according to the charset encoder's replacement behavior.
     *
     * @param text text to write
     * @throws ProtocolSessionException if the deadline expires, a request limit is exceeded, or writing fails
     */
    void write(String text);

    /**
     * Encodes and writes text followed by LF without flushing.
     *
     * <p>Existing CR and LF characters are preserved; this method always adds one LF. Both the text and the added LF
     * count toward the request's byte and UTF-16 character budgets.
     *
     * @param line line text
     * @throws ProtocolSessionException if the deadline expires, a request limit is exceeded, or writing fails
     */
    void writeLine(String line);

    /**
     * Flushes stdin.
     *
     * @throws ProtocolSessionException if the deadline expires or flushing fails
     */
    void flush();
}
