/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultLineSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Line-oriented request/response workflow over an interactive process.
 *
 * <p>Only one request is decoded at a time. Custom response decoders consume stdout lines through a deadline-aware
 * reader, while stderr is drained into the bounded transcript for diagnostics.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive line
 * sessions from {@code CommandService}.
 */
public sealed interface LineSession extends AutoCloseable permits DefaultLineSession {

    /**
     * Sends one line and decodes one response with the default request timeout.
     *
     * <p>Only one request can be active at a time. A local request-preparation or wait failure that completes before the
     * request is handed off for stdin writing, when no later write can occur, leaves the session open so the caller may
     * retry. This includes line validation, request-size checks, encoding, and deadlines while waiting for an earlier
     * request or for stdin writing to become available.
     * Once the request is handed off for writing, a timeout, interruption, or write failure is terminal even when the
     * caller cannot confirm that the process received a byte. EOF, decode error, oversized response or pending stdout,
     * decoder failure, and other response/protocol failures are also terminal. A terminal failure closes the
     * process and completes {@link #onExit()}; a pre-write failure leaves it incomplete unless the process exits
     * independently. The thrown {@link LineSessionException} contains a stable reason and bounded transcript snapshot.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     * @throws LineSessionException when the request cannot be completed safely
     */
    LineResponse request(String line);

    /**
     * Sends one line and decodes one response with an explicit request timeout.
     *
     * <p>Failure handling is the same as {@link #request(String)}.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     * @throws LineSessionException when the request cannot be completed safely
     */
    LineResponse request(String line, Duration timeout);

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    LineTranscript transcript();

    /**
     * Returns the underlying process exit future view.
     *
     * <p>The future completes after the process has a terminal outcome and line output has either drained naturally or
     * been logically abandoned during shutdown. After a request timeout, a decoder that ignores interruption or an
     * output read blocked in the JDK may still be running; neither delays this future, and a late result cannot replace
     * the selected outcome. The future does not wait for a potentially blocking physical close of the process streams. A
     * terminal line-session failure accepted before the public outcome is selected completes it exceptionally.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Closes the underlying interactive session. Calling this method more than once has no effect.
     */
    @Override
    void close();
}
