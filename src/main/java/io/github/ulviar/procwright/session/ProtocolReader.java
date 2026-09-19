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
 * <p>All reads use the current request deadline. Stdout and stderr share per-response byte and character budgets.
 * Character counts are UTF-16 code units, as in {@link String#length()}. EOF is reported with
 * {@link ProtocolSessionException.Reason#EOF} or {@link ProtocolSessionException.Reason#PROCESS_EXITED} when the exit
 * code is known, not a null value or a negative byte count. Partial fields or lines are
 * not returned as successful results at EOF.
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
     * @return signed Java byte; use {@link Byte#toUnsignedInt(byte)} for a value from 0 through 255
     * @throws ProtocolSessionException when the request times out, reaches EOF, or output cannot be read
     */
    byte readByte();

    /**
     * Reads up to {@code length} bytes into the provided buffer, waiting for at least one byte when needed.
     *
     * <p>When {@code length} is zero, this method returns zero without reading output or checking the deadline or response
     * budgets.
     *
     * @param buffer target buffer
     * @param offset target offset
     * @param length maximum bytes to read, at least zero
     * @return bytes read, from 1 through length for a nonempty request, or zero for a zero-length request
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
     * @return a new array of exactly the requested length
     * @throws IllegalArgumentException when {@code length} is negative
     * @throws ProtocolSessionException if the deadline expires, EOF arrives early, a limit is exceeded, or reading fails
     */
    byte[] readExactly(int length);

    /**
     * Reads exactly {@code byteLength} bytes and decodes them as one complete text field.
     *
     * <p>The field is decoded from initial state through end of input with the session charset policy. It does not share
     * decoder state with {@link #readLine(int)} or {@link #readTextUntil(byte, int)}. Both the response-global byte and
     * character budgets and the field-local {@code maxChars} limit apply. A zero-byte field is valid and returns an empty
     * string without creating or flushing a field decoder, inspecting continuous decoder state or process output,
     * checking the deadline, or consulting response budgets. Nonempty fields are read and decoded incrementally. When a
     * decoded chunk exceeds a character limit, reading stops without draining the rest of the field. The amount of
     * input consumed before that failure is not specified. Protocol sessions treat the failure as terminal and close
     * the process.
     *
     * @param byteLength exact encoded byte count, at least zero
     * @param maxChars positive maximum decoded UTF-16 code units
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
     * @param maxBytes positive maximum bytes including the delimiter
     * @return bytes including delimiter
     * @throws IllegalArgumentException if maxBytes is zero or negative
     * @throws ProtocolSessionException when the delimiter is not found before timeout, EOF, or limit, or reading fails
     */
    byte[] readUntil(byte delimiter, int maxBytes);

    /**
     * Reads one LF-terminated text line, accepting an optional CR immediately before LF.
     *
     * <p>The local maxChars limit excludes that terminator. The shared response-character budget includes decoded
     * terminator characters. A standalone CR is content, not a line boundary.
     *
     * @param maxChars positive maximum decoded UTF-16 code units
     * @return line without LF and optional preceding CR
     * @throws IllegalArgumentException if maxChars is zero or negative
     * @throws ProtocolSessionException if the deadline expires, EOF arrives before LF, decoding or reading fails,
     *     or a local or shared response limit is exceeded
     */
    String readLine(int maxChars);

    /**
     * Reads text through and including {@code delimiter}.
     *
     * <p>The delimiter is matched as a raw byte. Use {@link #readLine(int)} when the configured charset represents LF
     * with more than one byte.
     *
     * @param delimiter delimiter byte
     * @param maxChars positive maximum decoded UTF-16 code units
     * @return decoded text including delimiter
     * @throws IllegalArgumentException if maxChars is zero or negative
     * @throws ProtocolSessionException if the deadline expires, EOF arrives before the delimiter, decoding or reading
     *     fails, or a local or shared response limit is exceeded
     */
    String readTextUntil(byte delimiter, int maxChars);
}
