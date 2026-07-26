/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns protocol-session output pumps, transcript capture, and response queues. */
final class ProtocolOutputTransport {

    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final ProtocolSessionSettings options;
    private final ProtocolSessionState state;
    private final ZeroReadBackoff zeroReadBackoff;
    private final Consumer<? super Throwable> failureReporter;
    private final ProtocolTranscriptBuffer transcript;
    private final ProtocolTextReader.StreamState stdoutText;
    private final ProtocolTextReader.StreamState stderrText;
    private final ProtocolOutputQueue stdout;
    private final ProtocolOutputQueue stderr;
    private final Supplier<OptionalInt> exitCode;
    private final FailureHandler failureHandler;

    ProtocolOutputTransport(
            ProtocolSessionSettings options,
            ProtocolSessionState state,
            ZeroReadBackoff zeroReadBackoff,
            Consumer<? super Throwable> failureReporter,
            ProtocolTranscriptBuffer transcript,
            ProtocolTextDecoderState stdoutTextDecoder,
            ProtocolTextDecoderState stderrTextDecoder,
            LongSupplier nanoTime,
            Supplier<OptionalInt> exitCode,
            FailureHandler failureHandler) {
        this.options = Objects.requireNonNull(options, "options");
        this.state = Objects.requireNonNull(state, "state");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        stdoutText = new ProtocolTextReader.StreamState(
                options, Objects.requireNonNull(stdoutTextDecoder, "stdoutTextDecoder"));
        stderrText = new ProtocolTextReader.StreamState(
                options, Objects.requireNonNull(stderrTextDecoder, "stderrTextDecoder"));
        LongSupplier checkedNanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.stdout = queue(ProtocolOutputQueue.OverflowPolicy.STRICT, checkedNanoTime);
        this.stderr = queue(ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ, checkedNanoTime);
    }

    void start(PumpStarter pumpStarter, OutputPumpCoordinator outputPumps) {
        Objects.requireNonNull(outputPumps, "outputPumps");
        outputPumps.start(
                pumpStarter,
                "procwright-protocol-stdout-",
                stream -> runPump("stdout", stream, stdout),
                "procwright-protocol-stderr-",
                stream -> runPump("stderr", stream, stderr));
    }

    ProtocolTranscript transcript() {
        return transcript.snapshot();
    }

    ProtocolReaders readers(
            long deadlineNanos,
            ProtocolResponseBudget budget,
            ProtocolRuntimeFailures failures,
            RequestCapabilityScope capabilityScope) {
        return new Readers(
                new ProtocolResponseReader(
                        stdout, options, deadlineNanos, budget, stdoutText, failures, capabilityScope),
                new ProtocolResponseReader(
                        stderr, options, deadlineNanos, budget, stderrText, failures, capabilityScope));
    }

    void publishTerminal(ProtocolSessionState.TerminalSnapshot outcome) {
        if (outcome instanceof ProtocolSessionState.FailureSnapshot failure) {
            stdout.failAndClear(failure.reason(), failure.primary());
            stderr.failAndClear(failure.reason(), failure.primary());
            return;
        }
        if (outcome instanceof ProtocolSessionState.FatalSnapshot fatal) {
            publishFatal(fatal.error());
            return;
        }
        if (outcome instanceof ProtocolSessionState.ClosedSnapshot) {
            closeReaders();
            return;
        }
        throw new AssertionError("Unknown protocol terminal outcome: " + outcome);
    }

    void failFatal(Error error) {
        ProtocolSessionState.OutputSelection selection = state.recordOutputFatalError(error);
        if (selection.rejectedAfterClose()) {
            return;
        }
        ProtocolSessionState.TerminalSnapshot outcome = Objects.requireNonNull(selection.selected(), "outcome");
        publishTerminal(outcome);
        failureHandler.closeTerminalPreserving(outcome.primary());
    }

    private ProtocolOutputQueue queue(ProtocolOutputQueue.OverflowPolicy overflowPolicy, LongSupplier nanoTime) {
        return new ProtocolOutputQueue(
                options.outputBacklogLimit(), overflowPolicy, nanoTime, () -> {}, () -> {}, exitCode, null);
    }

    private void closeReaders() {
        stdout.close();
        stderr.close();
    }

    private void publishFatal(Error error) {
        publishFailure(stdout, ProtocolSessionException.Reason.DECODE_ERROR, error);
        publishFailure(stderr, ProtocolSessionException.Reason.DECODE_ERROR, error);
    }

    private void runPump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        try {
            pump(streamName, stream, output);
        } catch (RuntimeException failure) {
            failRuntime(failure);
        } catch (Error error) {
            failFatal(error);
        }
    }

    private void pump(String streamName, InputStream stream, ProtocolOutputQueue output) {
        byte[] buffer = new byte[8192];
        try (stream) {
            int consecutiveZeroReads = 0;
            while (!state.isClosed()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, state::isClosed)) {
                        if (!state.isClosed() && Thread.currentThread().isInterrupted()) {
                            throw new CommandExecutionException("Protocol output pump was interrupted during backoff");
                        }
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                transcript.appendStream(streamName, buffer, count);
                if (!output.offer(Arrays.copyOf(buffer, count))) {
                    closeAfter(failOutputBacklogOverflow());
                    return;
                }
            }
            if (state.isClosed()) {
                return;
            }
            transcript.endStream(streamName);
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            closeAfter(failTranscriptDecoding(exception));
            return;
        } catch (IOException exception) {
            if (!endTranscript(streamName)) {
                return;
            }
            if (!state.isClosed()) {
                if (output == stdout) {
                    failStdoutIo(exception);
                } else {
                    output.failure(reasonFor(exception), exception);
                }
            }
            return;
        }
        output.eof(exitCode.get());
    }

    private boolean endTranscript(String streamName) {
        try {
            transcript.endStream(streamName);
            return true;
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            if (!state.isClosed()) {
                closeAfter(failTranscriptDecoding(exception));
            }
            return false;
        }
    }

    private void failRuntime(RuntimeException failure) {
        ProtocolSessionState.OutputSelection selection = selectAndPublishOutputFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not read protocol output", failure);
        if (!selection.rejectedAfterClose()) {
            failureHandler.closeTerminalPreserving(
                    Objects.requireNonNull(selection.selected(), "outcome").primary());
        }
    }

    private void failStdoutIo(IOException failure) {
        ProtocolSessionException.Reason reason = reasonFor(failure);
        ProtocolSessionState.OutputSelection selection =
                selectAndPublishOutputFailure(reason, "Could not read protocol stdout", failure);
        if (!selection.rejectedAfterClose()) {
            failureHandler.closeTerminalPreserving(
                    Objects.requireNonNull(selection.selected(), "outcome").primary());
        }
    }

    private ProtocolSessionState.OutputSelection failOutputBacklogOverflow() {
        CommandExecutionException failure = new CommandExecutionException("Protocol stdout backlog overflow");
        return selectAndPublishOutputFailure(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "Protocol output backlog overflow", failure);
    }

    private ProtocolSessionState.OutputSelection failTranscriptDecoding(
            ProtocolTranscriptBuffer.TranscriptDecodingException failure) {
        return selectAndPublishOutputFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol transcript", failure);
    }

    private ProtocolSessionState.OutputSelection selectAndPublishOutputFailure(
            ProtocolSessionException.Reason reason, String message, Throwable failure) {
        ProtocolSessionState.OutputSelection selection = state.recordOutputFailure(
                Objects.requireNonNull(reason, "reason"),
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(failure, "failure"));
        ProtocolSessionState.TerminalSnapshot outcome = selection.selected();
        if (!selection.rejectedAfterClose()) {
            publishTerminal(Objects.requireNonNull(outcome, "outcome"));
        }
        return selection;
    }

    private void closeAfter(ProtocolSessionState.OutputSelection selection) {
        if (!selection.rejectedAfterClose()) {
            failureHandler.closeQuietly(
                    Objects.requireNonNull(selection.selected(), "outcome").primary());
        }
    }

    private void publishFailure(ProtocolOutputQueue output, ProtocolSessionException.Reason reason, Throwable failure) {
        try {
            output.failAndClear(reason, failure);
        } catch (Throwable publicationFailure) {
            failureReporter.accept(publicationFailure);
        }
    }

    private static ProtocolSessionException.Reason reasonFor(IOException exception) {
        return exception instanceof CharacterCodingException
                ? ProtocolSessionException.Reason.DECODE_ERROR
                : ProtocolSessionException.Reason.FAILURE;
    }

    interface FailureHandler {

        void closeTerminalPreserving(Throwable failure);

        void closeQuietly(Throwable failure);
    }

    private record Readers(ProtocolReader stdout, ProtocolReader stderr) implements ProtocolReaders {}
}
