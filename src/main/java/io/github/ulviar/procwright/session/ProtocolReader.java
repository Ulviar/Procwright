/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Deadline-aware protocol output reader.
 */
public interface ProtocolReader {

    /**
     * Reads one byte.
     *
     * @return byte value
     * @throws ProtocolSessionException when the request times out, reaches EOF, or output cannot be read
     */
    byte readByte();

    /**
     * Reads bytes into the provided buffer.
     *
     * @param buffer target buffer
     * @param offset target offset
     * @param length maximum bytes to read
     * @return bytes read
     * @throws ProtocolSessionException when the request times out, reaches EOF, or output cannot be read
     */
    int read(byte[] buffer, int offset, int length);

    /**
     * Reads exactly {@code length} bytes.
     *
     * @param length byte count
     * @return bytes read
     * @throws ProtocolSessionException when EOF arrives before the requested bytes
     */
    byte[] readExactly(int length);

    /**
     * Reads bytes through and including {@code delimiter}.
     *
     * @param delimiter delimiter byte
     * @param maxBytes maximum bytes including delimiter
     * @return bytes including delimiter
     * @throws ProtocolSessionException when the delimiter is not found before timeout, EOF, or limit
     */
    byte[] readUntil(byte delimiter, int maxBytes);

    /**
     * Reads one LF-terminated text line.
     *
     * @param maxChars maximum decoded characters
     * @return line without LF and optional preceding CR
     * @throws ProtocolSessionException when decoding fails or the line exceeds the limit
     */
    String readLine(int maxChars);

    /**
     * Reads text through and including {@code delimiter}.
     *
     * @param delimiter delimiter byte
     * @param maxChars maximum decoded characters
     * @return decoded text including delimiter
     * @throws ProtocolSessionException when decoding fails or the text exceeds the limit
     */
    String readTextUntil(byte delimiter, int maxChars);
}
