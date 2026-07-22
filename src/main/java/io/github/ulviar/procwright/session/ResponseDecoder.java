/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.List;

/**
 * Decodes stdout lines into one logical line-session response.
 *
 * <p>One {@link LineSession} serializes requests, so decoder calls do not overlap within that session. A line-session
 * {@code Draft} retains the supplied decoder instance, however, and concurrent opens or pooled workers can invoke that
 * same instance concurrently. A decoder shared this way must be thread-safe; otherwise, use separate Draft branches
 * with separate decoder instances.
 *
 * <p>An untyped {@link RuntimeException} thrown by {@link #decode(Reader)} is exposed as a
 * {@link LineSessionException} with reason {@link LineSessionException.Reason#DECODER_FAILED}. A callback-thrown
 * {@code LineSessionException} keeps its reason. The mapping applies when the callback failure wins request
 * arbitration; an already-selected terminal or fatal session outcome remains canonical. A callback-thrown
 * {@link Error} is rethrown as the same object. If a fatal {@code Error} was already selected, that earlier object wins
 * and the callback failure is attached to it as a suppressed exception. Fatal errors preserve object identity.
 */
@FunctionalInterface
public interface ResponseDecoder {

    /**
     * Decodes one logical response by reading stdout lines from the provided reader.
     *
     * <p>The reader is valid only on the thread executing this callback and only until this callback returns. Retaining
     * it, passing it to another thread, or using it during a later request throws {@link IllegalStateException} before
     * process output is consumed.
     *
     * @param reader deadline-aware stdout reader
     * @return response lines
     * @throws LineSessionException when response decoding reaches timeout, EOF, or a closed session; an untyped
     *     callback {@code RuntimeException} maps to reason {@link LineSessionException.Reason#DECODER_FAILED}
     */
    List<String> decode(Reader reader);

    /**
     * Returns a decoder that treats the next stdout line as the complete response.
     *
     * @return first-line response decoder
     */
    static ResponseDecoder firstLine() {
        return reader -> List.of(reader.readLine());
    }

    /**
     * Deadline-aware, callback-scoped stdout reader passed to custom decoders.
     */
    interface Reader {

        /**
         * Reads the next stdout line within the current request deadline.
         *
         * @return next stdout line without the line separator
         * @throws LineSessionException when the request times out, reaches EOF, or the session is closed
         */
        String readLine();
    }
}
