/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Owns bounded execution of one line response decoder and the reader capability granted to it.
 *
 * <p>The reader is valid only while the callback is active. Response line and character limits are
 * cumulative for that callback.
 */
final class LineResponseDecoder {

    private final LineSessionSettings options;
    private final LineSessionState state;
    private final LineOutputTransport output;
    private final OutputPumpCoordinator outputPumps;
    private final BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();

    LineResponseDecoder(
            LineSessionSettings options,
            LineSessionState state,
            LineOutputTransport output,
            OutputPumpCoordinator outputPumps) {
        this.options = Objects.requireNonNull(options, "options");
        this.state = Objects.requireNonNull(state, "state");
        this.output = Objects.requireNonNull(output, "output");
        this.outputPumps = Objects.requireNonNull(outputPumps, "outputPumps");
    }

    List<String> decode(long deadlineNanos, LineSessionState.Request request) {
        RequestCapabilityScope capability = new RequestCapabilityScope("ResponseDecoder.Reader");
        ResponseReader reader = new ResponseReader(deadlineNanos, request, capability);
        try {
            return BoundedTaskRunner.runWithAbandonment(
                    BoundedTaskLimits.PROTOCOL_CALLBACKS,
                    "procwright-line-decoder-",
                    deadlineNanos,
                    cancellation,
                    failure -> {
                        capability.invalidate();
                        selectAbandonment(request, failure);
                    },
                    () -> {
                        capability.activate();
                        try {
                            return List.copyOf(options.responseDecoder().decode(reader));
                        } finally {
                            capability.invalidate();
                        }
                    });
        } catch (TimeoutException exception) {
            throw state.selectCallbackFailure(request, state::timeout);
        } catch (BoundedTaskRunner.TaskCancelledException exception) {
            throw state.selectCallbackFailure(request, () -> state.closed(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw state.selectCallbackFailure(
                    request, () -> state.failure("Interrupted while decoding line response", exception));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            LineSessionState.TerminalSnapshot outcome = state.terminal();
            if (outcome instanceof LineSessionState.FatalSnapshot fatal) {
                if (fatal.error() != cause) {
                    outputPumps.retainFailure(cause);
                }
                throw fatal.error();
            }
            if (cause instanceof Error error) {
                throw error;
            }
            request.throwIfFailed();
            if (cause instanceof LineSessionException lineSessionException) {
                throw lineSessionException;
            }
            throw state.failure(LineSessionException.Reason.DECODER_FAILED, "Response decoder failed", cause);
        } finally {
            capability.invalidate();
        }
    }

    void cancel() {
        cancellation.cancel();
    }

    private void selectAbandonment(LineSessionState.Request request, Throwable cause) {
        if (cause instanceof TimeoutException) {
            state.recordRequestTimeout(request);
        } else if (cause instanceof BoundedTaskRunner.TaskCancelledException cancellationFailure) {
            state.recordRequestFailure(request, () -> state.closed(cancellationFailure));
        } else if (cause instanceof InterruptedException interruption) {
            state.recordRequestFailure(
                    request, () -> state.failure("Interrupted while decoding line response", interruption));
        } else {
            throw new IllegalArgumentException("Unsupported callback abandonment", cause);
        }
    }

    private final class ResponseReader implements ResponseDecoder.Reader {

        private final long deadlineNanos;
        private final LineSessionState.Request request;
        private final RequestCapabilityScope capability;
        private long linesRead;
        private long charactersRead;

        private ResponseReader(
                long deadlineNanos, LineSessionState.Request request, RequestCapabilityScope capability) {
            this.deadlineNanos = deadlineNanos;
            this.request = request;
            this.capability = Objects.requireNonNull(capability, "capability");
        }

        @Override
        public String readLine() {
            capability.verifyAccess();
            while (true) {
                if (deadlineNanos - System.nanoTime() <= 0) {
                    throw state.recordRequestTimeout(request);
                }

                LineOutputTransport.Event event = output.take(deadlineNanos, request);
                if (event instanceof LineOutputTransport.LineEvent line) {
                    linesRead++;
                    if (linesRead > options.maxResponseLines()) {
                        throw track(() -> state.failure(
                                LineSessionException.Reason.RESPONSE_TOO_LARGE,
                                "Line response exceeds maxResponseLines",
                                null));
                    }
                    int lineLength = line.value().length();
                    if (lineLength > options.maxResponseChars() - charactersRead) {
                        throw track(() -> state.failure(
                                LineSessionException.Reason.RESPONSE_TOO_LARGE,
                                "Line response exceeds maxResponseChars",
                                null));
                    }
                    charactersRead += lineLength;
                    return line.value();
                }
                if (event instanceof LineOutputTransport.EofEvent) {
                    throw track(state::eof);
                }
                if (event instanceof LineOutputTransport.ClosedEvent) {
                    throw track(() -> state.closed(null));
                }
                if (event instanceof LineOutputTransport.FailureEvent failure) {
                    throw track(() -> state.failure(failure.reason(), failure.message(), failure.failure()));
                }
                if (event instanceof LineOutputTransport.FatalEvent fatal) {
                    throw fatal.error();
                }
                throw new AssertionError("Unknown line output event: " + event);
            }
        }

        private LineSessionException track(Supplier<LineSessionException> failureFactory) {
            return state.recordRequestFailure(request, failureFactory);
        }
    }
}
