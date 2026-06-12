/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.List;

/**
 * Decodes stdout lines into one logical line-session response.
 */
@FunctionalInterface
public interface ResponseDecoder {

    /**
     * Decodes one logical response by reading stdout lines from the provided reader.
     *
     * @param reader deadline-aware stdout reader
     * @return response lines
     * @throws LineSessionException when response decoding reaches timeout, EOF, or a closed session
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
     * Deadline-aware stdout reader passed to custom decoders.
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
