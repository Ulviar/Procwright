/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.SessionExit;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON Lines request/response helper over an existing {@link LineSession}.
 *
 * <p>This class does not launch processes. It only frames payloads for a line-oriented session that already owns
 * lifecycle, timeout, and shutdown behavior.
 */
public final class JsonLineSession implements AutoCloseable {

    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    private final LineSession session;
    private final String workerThreadName;
    private final Object lock = new Object();
    private final Set<CompletableFuture<JsonValue>> pendingCompletions = ConcurrentHashMap.newKeySet();
    private ExecutorService asyncExecutor;
    private boolean closed;

    private JsonLineSession(LineSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.workerThreadName = "procwright-json-line-session-" + SESSION_SEQUENCE.getAndIncrement();
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
     * Sends one JSON request asynchronously on the session-owned worker.
     *
     * <p>Asynchronous requests run on one lazily started daemon worker thread per session, so concurrent calls queue
     * in submission order instead of racing on the serialized line session. The worker stops when the session closes.
     * After {@link #close()}, the returned call completes exceptionally with {@link IllegalStateException}.
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
        synchronized (lock) {
            if (closed) {
                completion.completeExceptionally(new IllegalStateException("JSON line session is closed"));
                return new CancellableCall<>(completion, this::close);
            }
            pendingCompletions.add(completion);
            completion.whenComplete((value, throwable) -> pendingCompletions.remove(completion));
            executorForRequests().execute(() -> {
                try {
                    completion.complete(request(request, timeout));
                } catch (Throwable throwable) {
                    completion.completeExceptionally(throwable);
                }
            });
        }
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
     *
     * <p>Pending asynchronous calls complete exceptionally with {@link CancellationException} and the session worker
     * thread stops. Closing an already closed session has no further effect beyond delegating to the line session.
     */
    @Override
    public void close() {
        ExecutorService executor;
        List<CompletableFuture<JsonValue>> pending;
        synchronized (lock) {
            closed = true;
            executor = asyncExecutor;
            asyncExecutor = null;
            pending = List.copyOf(pendingCompletions);
        }
        for (CompletableFuture<JsonValue> completion : pending) {
            completion.completeExceptionally(new CancellationException("JSON line session closed"));
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        session.close();
    }

    private ExecutorService executorForRequests() {
        if (asyncExecutor == null) {
            asyncExecutor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, workerThreadName);
                thread.setDaemon(true);
                return thread;
            });
        }
        return asyncExecutor;
    }

    private static JsonValue parseSingleResponseLine(LineResponse response) {
        Objects.requireNonNull(response, "response");
        if (response.lines().size() != 1) {
            throw new JsonParseException("Expected exactly one JSON response line");
        }
        return JsonLines.parseLine(response.lines().get(0));
    }
}
