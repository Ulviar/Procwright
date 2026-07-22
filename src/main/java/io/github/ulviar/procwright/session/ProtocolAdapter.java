/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Adapter that owns a request/response protocol over a long-lived CLI process.
 *
 * <p>Each adapter instance belongs to exactly one protocol session or pooled worker. Supply adapter instances through
 * a concurrent-safe factory that returns a fresh instance for every invocation; sharing one adapter instance between
 * workers is unsupported.
 *
 * <p>One protocol session serializes request cycles. Its adapter completes {@link #writeRequest(Object, ProtocolWriter)}
 * before {@link #readResponse(ProtocolReaders)}, and adapter calls do not overlap within that session. Different
 * factory-created adapters can run concurrently. Mutable state captured outside those adapter instances remains shared
 * and must be thread-safe.
 *
 * <p>The writer and readers are request-scoped capabilities. They may be used only by the thread executing the callback
 * that received them and only until that callback returns. Retaining them, passing them to another thread, or using them
 * from a later request throws {@link IllegalStateException} before process I/O occurs.
 *
 * <p>A {@link RuntimeException} thrown by {@link #writeRequest(Object, ProtocolWriter)} is exposed as a
 * {@link ProtocolSessionException} with reason {@link ProtocolSessionException.Reason#FAILURE}; one thrown by
 * {@link #readResponse(ProtocolReaders)} uses {@link ProtocolSessionException.Reason#PROTOCOL_DECODER_FAILED}. A
 * callback-thrown {@code ProtocolSessionException} keeps its reason. These mappings apply when the callback failure
 * wins request arbitration; an already-selected terminal or fatal session outcome remains canonical.
 *
 * <p>A callback-thrown {@link Error} is rethrown as the same object. If a fatal {@code Error} was already selected for
 * the session, that earlier object wins and the callback failure is attached to it as a suppressed exception. Fatal
 * errors preserve object identity.
 *
 * @param <I> request type
 * @param <O> response type
 */
public interface ProtocolAdapter<I extends Object, O extends Object> {

    /**
     * Writes one request to process stdin.
     *
     * @param request request value
     * @param writer deadline-aware, callback-scoped stdin writer
     * @throws ProtocolSessionException when request framing fails; an untyped callback {@code RuntimeException} maps
     *     to reason {@link ProtocolSessionException.Reason#FAILURE}
     */
    void writeRequest(I request, ProtocolWriter writer);

    /**
     * Reads one response from process output streams.
     *
     * @param readers deadline-aware, callback-scoped stdout/stderr readers
     * @return decoded response
     * @throws ProtocolSessionException when response decoding fails; an untyped callback {@code RuntimeException}
     *     maps to reason {@link ProtocolSessionException.Reason#PROTOCOL_DECODER_FAILED}
     */
    O readResponse(ProtocolReaders readers);
}
