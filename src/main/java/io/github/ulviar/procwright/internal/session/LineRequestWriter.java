/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.LineSessionException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Owns one line request's bounded stdin write and its retry-safe handoff boundary.
 *
 * <p>A timeout or interruption is retryable only while the write task is proven not to have reached
 * the process stdin.
 */
final class LineRequestWriter {

    private final DefaultSession session;
    private final LineSessionState state;
    private final TaskRunner taskRunner;

    LineRequestWriter(DefaultSession session, LineSessionState state, TaskRunner taskRunner) {
        this.session = Objects.requireNonNull(session, "session");
        this.state = Objects.requireNonNull(state, "state");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner");
    }

    void write(byte[] encodedLine, long deadlineNanos, LineSessionState.Request request)
            throws RetryablePreWriteFailure {
        TaskStart start = new TaskStart();
        try {
            taskRunner.run("procwright-line-stdin-", deadlineNanos, start, () -> {
                java.io.OutputStream stdin = session.stdin();
                stdin.write(encodedLine);
                stdin.flush();
                return null;
            });
        } catch (SessionStdinClosedException exception) {
            throw state.recordRequestFailure(request, () -> state.closed(exception));
        } catch (IllegalStateException exception) {
            throw state.recordRequestFailure(
                    request,
                    () -> state.failure(
                            LineSessionException.Reason.FAILURE, "Could not write line-session stdin", exception));
        } catch (TimeoutException exception) {
            if (!start.started()) {
                throw retryable(request, state.timeout());
            }
            throw state.recordRequestTimeout(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LineSessionException interrupted = state.failure(
                    LineSessionException.Reason.FAILURE, "Interrupted while writing line-session stdin", exception);
            if (!start.started()) {
                throw retryable(request, interrupted);
            }
            throw state.recordRequestFailure(request, () -> interrupted);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (!start.started()) {
                throw retryable(
                        request,
                        state.failure(
                                LineSessionException.Reason.FAILURE,
                                "Could not start line-session stdin writer",
                                cause));
            }
            if (cause instanceof ProcessExitedException processExited) {
                throw state.recordRequestFailure(
                        request,
                        () -> state.failure(
                                LineSessionException.Reason.PROCESS_EXITED,
                                "Line-session process exited before the request could be written",
                                processExited));
            }
            if (cause instanceof SessionStdinClosedException stdinClosed) {
                throw state.recordRequestFailure(request, () -> state.closed(stdinClosed));
            }
            if (cause instanceof IOException ioException) {
                throw state.recordRequestFailure(
                        request,
                        () -> state.failure(
                                LineSessionException.Reason.BROKEN_PIPE,
                                "Could not write line-session stdin",
                                ioException));
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw state.recordRequestFailure(
                        request,
                        () -> state.failure(
                                LineSessionException.Reason.FAILURE,
                                "Could not write line-session stdin",
                                runtimeException));
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw state.recordRequestFailure(
                    request,
                    () -> state.failure(
                            LineSessionException.Reason.FAILURE, "Could not write line-session stdin", cause));
        }
    }

    private RetryablePreWriteFailure retryable(LineSessionState.Request request, LineSessionException candidate) {
        return new RetryablePreWriteFailure(state.releaseRetryablePreWrite(request, candidate));
    }

    @FunctionalInterface
    interface TaskRunner {

        void run(String threadPrefix, long deadlineNanos, TaskStart start, TimedTaskRunner.Task<Void> task)
                throws TimeoutException, InterruptedException, ExecutionException;
    }

    static final class RetryablePreWriteFailure extends Exception {

        private static final long serialVersionUID = 1L;

        private final LineSessionException failure;

        private RetryablePreWriteFailure(LineSessionException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        LineSessionException failure() {
            return failure;
        }
    }
}
