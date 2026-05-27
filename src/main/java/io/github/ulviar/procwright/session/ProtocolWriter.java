package io.github.ulviar.procwright.session;

/**
 * Deadline-aware protocol request writer.
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
