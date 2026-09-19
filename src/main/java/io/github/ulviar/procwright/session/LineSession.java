/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultLineSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Line-oriented request/response workflow over an interactive process.
 *
 * <p>Only one request is decoded at a time. Custom response decoders consume stdout lines through a deadline-aware
 * reader, while stderr is drained into the bounded transcript for diagnostics. The process must accept repeated
 * newline-delimited requests while remaining alive; responses need not be single-line when a custom decoder is used.
 * Concurrent callers are supported, but request order across threads is not specified. Close the handle with
 * try-with-resources when its work is complete.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive line
 * sessions from {@code CommandService}.
 *
 * @see io.github.ulviar.procwright.CommandService#lineSession()
 * @see ResponseDecoder
 */
public sealed interface LineSession extends AutoCloseable permits DefaultLineSession {

    /**
     * Sends one LF-terminated line, flushes stdin, and decodes one response with the configured request timeout.
     *
     * <p>The initial default is five seconds and the default decoder returns one stdout line. The timeout covers
     * preparation, waiting for another request, writing, and response decoding. Cleanup after a terminal timeout uses
     * the separate shutdown policy and can extend the time before this call returns.
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
     * @param line request text containing neither CR nor LF; an empty line is allowed
     * @return decoded response
     * @throws IllegalArgumentException if the line contains CR or LF
     * @throws LineSessionException when the request cannot be completed safely
     */
    LineResponse request(String line);

    /**
     * Sends one line and decodes one response with an explicit request timeout.
     *
     * <p>Failure handling is the same as {@link #request(String)}.
     *
     * @param line request text containing neither CR nor LF; an empty line is allowed
     * @param timeout positive timeout for this entire request, including its serialized wait
     * @return decoded response
     * @throws IllegalArgumentException if the line contains CR or LF, or the timeout is zero or negative
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
     * <p>Each call returns an independent view. Cancelling or completing it does not affect the process or other views.
     * Keep synchronous completion actions short; use asynchronous continuations for blocking work.
     *
     * @return cancellation-isolated process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Stops accepting requests and closes the process through its configured shutdown policy.
     *
     * <p>Calling this method more than once has no effect. A concurrent terminal action may already own cleanup;
     * use {@link #onExit()} to await the logical outcome. Physical stream closes can continue after that outcome.
     */
    @Override
    void close();
}
