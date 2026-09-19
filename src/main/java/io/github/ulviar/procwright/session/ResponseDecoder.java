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
 * <p>Consume exactly the lines belonging to one response, including any terminator line, and return the lines the
 * caller should receive. The returned list must be non-null and contain no null elements; Procwright copies it.
 * Configured response limits count lines read and their UTF-16 code units, including lines omitted from the returned
 * list. A decoder may return an empty list when that is a complete response for its protocol.
 *
 * <p>An untyped {@link RuntimeException} thrown by {@link #decode(Reader)} is exposed as a
 * {@link LineSessionException} with reason {@link LineSessionException.Reason#DECODER_FAILED}. A callback-thrown
 * {@code LineSessionException} keeps its reason. The mapping applies when the callback failure wins request
 * arbitration; an already-selected terminal or fatal session outcome remains canonical. A callback-thrown
 * {@link Error} remains fatal when it wins arbitration; otherwise it does not replace the earlier outcome.
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
     * @return non-null response lines, with no null elements; the list is copied before publication
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
         * <p>LF terminates a line; an immediately preceding CR is removed. EOF also completes a final nonempty line
         * without a separator. A standalone CR is content. This consumes the current response's line and UTF-16
         * character budgets even if the decoder does not include the line in its returned response.
         *
         * @return next stdout line without the line separator
         * @throws IllegalStateException if used outside this callback's thread or lifetime
         * @throws LineSessionException if the request times out, reaches EOF, the session closes, output decoding fails,
         *     or a response limit is exceeded
         */
        String readLine();
    }
}
