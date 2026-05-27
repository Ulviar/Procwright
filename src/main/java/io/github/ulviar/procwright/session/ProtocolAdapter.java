package io.github.ulviar.procwright.session;

/**
 * Adapter that owns a request/response protocol over a long-lived CLI process.
 *
 * @param <I> request type
 * @param <O> response type
 */
public interface ProtocolAdapter<I, O> {

    /**
     * Writes one request to process stdin.
     *
     * @param request request value
     * @param writer deadline-aware stdin writer
     * @throws ProtocolSessionException when request framing fails
     */
    void writeRequest(I request, ProtocolWriter writer);

    /**
     * Reads one response from process output streams.
     *
     * @param readers deadline-aware stdout/stderr readers
     * @return decoded response
     * @throws ProtocolSessionException when response decoding fails
     */
    O readResponse(ProtocolReaders readers);
}
