/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Deadline-aware protocol output reader.
 *
 * <p>{@link #readLine(int)} and {@link #readTextUntil(byte, int)} share decoder state for one continuous process output
 * stream, including across requests. {@link #readTextExactly(int, int)} uses a separate decoder because its bytes form
 * one complete text field. Raw byte methods bypass text decoding and character budgets. A nonempty raw or complete-field
 * read is rejected with {@link ProtocolSessionException.Reason#PROTOCOL_DECODER_FAILED} when the continuous decoder has
 * pending input, before another byte is consumed. While decoded line output remains pending, all nonempty reads except
 * {@code readLine} are rejected for the same reason. After an arbitrary raw read, Procwright cannot infer whether the
 * bytes ended at a character boundary; the framing adapter must establish that boundary before resuming continuous text
 * reads.
 *
 * <p>Method arguments are validated before callback access. A zero-length buffer, exact-byte, or complete-field read
 * still requires valid callback access, but returns an empty result without inspecting decoder state or process output,
 * checking the deadline, or consulting response budgets.
 *
 * <p>A reader is valid only on the thread executing the {@link ProtocolAdapter#readResponse(ProtocolReaders)} callback
 * that received it and only until that callback returns. Using it from another thread or request throws {@link
 * IllegalStateException} before process output is consumed.
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
     * <p>When {@code length} is zero, this method returns zero without reading output or checking the deadline or response
     * budgets.
     *
     * @param buffer target buffer
     * @param offset target offset
     * @param length maximum bytes to read, at least zero
     * @return bytes read
     * @throws NullPointerException when {@code buffer} is {@code null}
     * @throws IndexOutOfBoundsException when {@code offset} and {@code length} do not identify a valid buffer range
     * @throws ProtocolSessionException when the request times out, reaches EOF, or output cannot be read
     */
    int read(byte[] buffer, int offset, int length);

    /**
     * Reads exactly {@code length} bytes.
     *
     * <p>When {@code length} is zero, this method returns an empty array without reading output or checking the deadline
     * or response budgets.
     *
     * @param length byte count, at least zero
     * @return bytes read
     * @throws IllegalArgumentException when {@code length} is negative
     * @throws ProtocolSessionException when EOF arrives before the requested bytes
     */
    byte[] readExactly(int length);

    /**
     * Reads exactly {@code byteLength} bytes and decodes them as one complete text field.
     *
     * <p>The field is decoded from initial state through end of input with the session charset policy. It does not share
     * decoder state with {@link #readLine(int)} or {@link #readTextUntil(byte, int)}. Both the response-global byte and
     * character budgets and the field-local {@code maxChars} limit apply. A zero-byte field is valid and returns an empty
     * string without creating or flushing a field decoder, inspecting continuous decoder state or process output,
     * checking the deadline, or consulting response budgets. When decoded text exceeds a character limit, reading stops
     * after the first excess character; bytes from the rest of the declared field may remain unread. Protocol sessions
     * treat that failure as terminal and close the process.
     *
     * @param byteLength exact encoded byte count, at least zero
     * @param maxChars maximum decoded characters, greater than zero
     * @return decoded field
     * @throws IllegalArgumentException when {@code byteLength} is negative or {@code maxChars} is not positive
     * @throws ProtocolSessionException when a nonempty field reaches EOF early, the deadline expires, decoding fails,
     *     continuous text decoding has pending input or line output, or a response limit is exceeded
     */
    String readTextExactly(int byteLength, int maxChars);

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
     * <p>The delimiter is matched as a raw byte. Use {@link #readLine(int)} when the configured charset represents LF
     * with more than one byte.
     *
     * @param delimiter delimiter byte
     * @param maxChars maximum decoded characters
     * @return decoded text including delimiter
     * @throws ProtocolSessionException when decoding fails or the text exceeds the limit
     */
    String readTextUntil(byte delimiter, int maxChars);
}
