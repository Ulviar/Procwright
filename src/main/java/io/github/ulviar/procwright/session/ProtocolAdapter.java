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
 * before {@link #readResponse(ProtocolReaders)}, and adapter calls do not overlap within that session. Neither callback
 * has a stable thread identity across requests. Different
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
 * <p>A callback-thrown {@link Error} remains fatal when it wins arbitration; otherwise it does not replace an
 * already-selected terminal or fatal session outcome.
 *
 * <p>For a worker whose requests and responses are each one line (prefer the dedicated line-session scenario
 * when no custom protocol is needed), a minimal adapter is:
 * {@snippet file="io/github/ulviar/procwright/examples/ApiUsageExamples.java" region="adapter"}
 *
 * @param <I> request type
 * @param <O> response type
 */
public interface ProtocolAdapter<I extends Object, O extends Object> {

    /**
     * Writes one complete request to process stdin and flushes it before returning.
     *
     * <p>The adapter owns framing and must call {@link ProtocolWriter#flush()} after writing the request. Returning
     * from this method, including after {@link ProtocolWriter#writeLine(String)}, does not flush automatically. The
     * response callback starts only after this method returns; do not wait here for a response from the process.
     *
     * @param request request value
     * @param writer deadline-aware, callback-scoped stdin writer
     * @throws ProtocolSessionException when request framing fails; an untyped callback {@code RuntimeException} maps
     *     to reason {@link ProtocolSessionException.Reason#FAILURE}
     */
    void writeRequest(I request, ProtocolWriter writer);

    /**
     * Reads exactly one logical response from process output streams.
     *
     * <p>Consume all framing that belongs to this response before returning, leaving later responses unread. Stdout
     * and stderr readers share this request's byte and decoded-character budgets. A callback must not return null.
     *
     * @param readers deadline-aware, callback-scoped stdout/stderr readers
     * @return decoded response
     * @throws ProtocolSessionException when response decoding fails; an untyped callback {@code RuntimeException}
     *     maps to reason {@link ProtocolSessionException.Reason#PROTOCOL_DECODER_FAILED}
     */
    O readResponse(ProtocolReaders readers);
}
