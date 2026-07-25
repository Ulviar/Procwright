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
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Owns protocol-session output pumps, transcript capture, and response queues. */
final class ProtocolOutputTransport {

    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final ProtocolSessionSettings options;
    private final ProtocolSessionState state;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final ProtocolTranscriptBuffer transcript;
    private final ProtocolTextDecoderState stdoutTextDecoder;
    private final ProtocolTextDecoderState stderrTextDecoder;
    private final ProtocolOutputQueue stdout;
    private final ProtocolOutputQueue stderr;
    private final Supplier<OptionalInt> exitCode;
    private final FailureHandler failureHandler;

    ProtocolOutputTransport(
            ProtocolSessionSettings options,
            ProtocolSessionState state,
            ZeroReadBackoff zeroReadBackoff,
            OutputPumpCoordinator outputPumps,
            ProtocolTranscriptBuffer transcript,
            ProtocolTextDecoderState stdoutTextDecoder,
            ProtocolTextDecoderState stderrTextDecoder,
            LongSupplier nanoTime,
            Supplier<OptionalInt> exitCode,
            FailureHandler failureHandler) {
        this.options = Objects.requireNonNull(options, "options");
        this.state = Objects.requireNonNull(state, "state");
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        this.outputPumps = Objects.requireNonNull(outputPumps, "outputPumps");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.stdoutTextDecoder = Objects.requireNonNull(stdoutTextDecoder, "stdoutTextDecoder");
        this.stderrTextDecoder = Objects.requireNonNull(stderrTextDecoder, "stderrTextDecoder");
        LongSupplier checkedNanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.exitCode = Objects.requireNonNull(exitCode, "exitCode");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.stdout = queue(ProtocolOutputQueue.OverflowPolicy.STRICT, checkedNanoTime);
        this.stderr = queue(ProtocolOutputQueue.OverflowPolicy.FAIL_ON_READ, checkedNanoTime);
    }

    void start(PumpStarter pumpStarter) {
        outputPumps.start(
                pumpStarter,
                "procwright-protocol-stdout-",
                stream -> runPump("stdout", stream, stdout),
                "procwright-protocol-stderr-",
                stream -> runPump("stderr", stream, stderr),
                state::markClosed);
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
                        stdout, options, deadlineNanos, budget, stdoutTextDecoder, failures, capabilityScope),
                new ProtocolResponseReader(
                        stderr, options, deadlineNanos, budget, stderrTextDecoder, failures, capabilityScope));
    }

    void publishTerminal(ProtocolSessionState.TerminalSnapshot outcome) {
        switch (outcome) {
            case ProtocolSessionState.FailureSnapshot failure -> {
                stdout.failAndClear(failure.reason(), failure.primary());
                stderr.failAndClear(failure.reason(), failure.primary());
            }
            case ProtocolSessionState.FatalSnapshot fatal -> publishFatal(fatal.error());
            case ProtocolSessionState.ClosedSnapshot ignored -> closeReaders();
        }
    }

    void failFatal(Error error) {
        ProtocolSessionState.TerminalSnapshot outcome = state.recordFatalError(error);
        Error selected = ((ProtocolSessionState.FatalSnapshot) outcome).error();
        publishFatal(selected);
        failureHandler.closeTerminalPreserving(selected);
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
                    Throwable primary = failOutputBacklogOverflow();
                    failureHandler.closeQuietly(primary);
                    return;
                }
            }
            if (state.isClosed()) {
                return;
            }
            transcript.endStream(streamName);
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            Throwable primary = failTranscriptDecoding(exception);
            failureHandler.closeQuietly(primary);
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
        if (output == stdout) {
            state.recordStdoutEof();
        }
    }

    private boolean endTranscript(String streamName) {
        try {
            transcript.endStream(streamName);
            return true;
        } catch (ProtocolTranscriptBuffer.TranscriptDecodingException exception) {
            if (!state.isClosed()) {
                Throwable primary = failTranscriptDecoding(exception);
                failureHandler.closeQuietly(primary);
            }
            return false;
        }
    }

    private void failRuntime(RuntimeException failure) {
        boolean publishFailure = !state.isClosed();
        ProtocolSessionState.TerminalSnapshot outcome = state.recordTerminalFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not read protocol output", failure);
        Throwable primary = terminalPrimaryOr(outcome, failure);
        try {
            if (publishFailure && outcome instanceof ProtocolSessionState.FailureSnapshot selected) {
                publishFailure(stdout, selected.reason(), selected.primary());
                publishFailure(stderr, selected.reason(), selected.primary());
            }
        } finally {
            failureHandler.closeTerminalPreserving(primary);
        }
    }

    private void failStdoutIo(IOException failure) {
        ProtocolSessionException.Reason reason = reasonFor(failure);
        ProtocolSessionState.TerminalSnapshot outcome =
                state.recordTerminalFailure(reason, "Could not read protocol stdout", failure);
        Throwable primary = terminalPrimaryOr(outcome, failure);
        try {
            if (outcome instanceof ProtocolSessionState.FailureSnapshot selected) {
                publishFailure(stdout, selected.reason(), selected.primary());
                publishFailure(stderr, selected.reason(), selected.primary());
            }
        } finally {
            failureHandler.closeTerminalPreserving(primary);
        }
    }

    private Throwable failOutputBacklogOverflow() {
        CommandExecutionException failure = new CommandExecutionException("Protocol stdout backlog overflow");
        return selectAndPublishFailure(
                ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, "Protocol output backlog overflow", failure);
    }

    private Throwable failTranscriptDecoding(ProtocolTranscriptBuffer.TranscriptDecodingException failure) {
        return selectAndPublishFailure(
                ProtocolSessionException.Reason.DECODE_ERROR, "Could not decode protocol transcript", failure);
    }

    Throwable selectAndPublishFailure(ProtocolSessionException.Reason reason, String message, Throwable failure) {
        ProtocolSessionState.TerminalSnapshot outcome = state.recordTerminalFailure(
                Objects.requireNonNull(reason, "reason"),
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(failure, "failure"));
        publishTerminal(outcome);
        return terminalPrimaryOr(outcome, failure);
    }

    private void publishFailure(ProtocolOutputQueue output, ProtocolSessionException.Reason reason, Throwable failure) {
        try {
            output.failAndClear(reason, failure);
        } catch (Throwable publicationFailure) {
            outputPumps.retainFailure(publicationFailure);
        }
    }

    private static Throwable terminalPrimaryOr(ProtocolSessionState.TerminalSnapshot outcome, Throwable fallback) {
        if (outcome instanceof ProtocolSessionState.FailureSnapshot failure) {
            return failure.primary();
        }
        if (outcome instanceof ProtocolSessionState.FatalSnapshot fatal) {
            return fatal.error();
        }
        return fallback;
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
