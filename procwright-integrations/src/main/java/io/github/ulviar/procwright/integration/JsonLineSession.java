/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * JSON Lines request/response helper over an existing {@link LineSession}.
 *
 * <p>This class does not launch processes. It only frames payloads for a line-oriented session that already owns
 * lifecycle, timeout, and shutdown behavior.
 */
public final class JsonLineSession implements AutoCloseable {

    private final LineSession session;

    private JsonLineSession(LineSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Wraps an existing line session.
     *
     * @param session line session
     * @return JSON line helper
     */
    public static JsonLineSession over(LineSession session) {
        return new JsonLineSession(session);
    }

    /**
     * Sends one JSON request and parses one JSON response.
     *
     * @param request request payload
     * @return response payload
     */
    public JsonValue request(JsonValue request) {
        LineResponse response = session.request(JsonLines.line(request));
        return parseSingleResponseLine(response);
    }

    /**
     * Sends one JSON request and parses one JSON response with an explicit request timeout.
     *
     * @param request request payload
     * @param timeout request timeout
     * @return response payload
     */
    public JsonValue request(JsonValue request, Duration timeout) {
        LineResponse response = session.request(JsonLines.line(request), timeout);
        return parseSingleResponseLine(response);
    }

    /**
     * Returns the current bounded line-session transcript.
     *
     * @return transcript snapshot
     */
    public LineTranscript transcript() {
        return session.transcript();
    }

    /**
     * Returns the underlying process exit future view.
     *
     * @return exit future
     */
    public CompletableFuture<SessionExit> onExit() {
        return session.onExit();
    }

    /**
     * Closes the underlying line session.
     *
     * <p>Closing an already closed session has no further effect beyond delegating to the line session.
     */
    @Override
    public void close() {
        session.close();
    }

    private static JsonValue parseSingleResponseLine(LineResponse response) {
        Objects.requireNonNull(response, "response");
        if (response.lines().size() != 1) {
            throw new JsonParseException("Expected exactly one JSON response line");
        }
        return JsonLines.parseLine(response.lines().get(0));
    }
}
