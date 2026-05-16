package com.github.ulviar.icli.integration;

import com.github.ulviar.icli.LineResponse;
import com.github.ulviar.icli.LineSession;
import com.github.ulviar.icli.LineTranscript;
import com.github.ulviar.icli.SessionExit;
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
     * Sends one JSON request asynchronously on a virtual thread.
     *
     * <p>Calling {@link CancellableCall#cancel()} closes the underlying line session. This intentionally maps
     * cancellation to the same lifecycle path used by line-session failures instead of leaving a half-decoded protocol
     * exchange alive.
     *
     * @param request request payload
     * @param timeout request timeout
     * @return cancellable call handle
     */
    public CancellableCall<JsonValue> requestAsync(JsonValue request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(timeout, "timeout");
        CompletableFuture<JsonValue> completion = new CompletableFuture<>();
        Thread.ofVirtual().name("icli-json-line-request-", 0).start(() -> {
            try {
                completion.complete(request(request, timeout));
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        });
        return new CancellableCall<>(completion, this::close);
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
        return JsonLines.parseLine(response.lines().getFirst());
    }
}
