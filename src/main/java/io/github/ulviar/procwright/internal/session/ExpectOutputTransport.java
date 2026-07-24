/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.ExpectSettings;
import java.io.InputStream;
import java.util.Objects;

/**
 * Owns expect stdout/stderr pumping, decoding, and physical output cleanup.
 */
final class ExpectOutputTransport {

    private static final String OUTPUT_OWNER = "Expect";
    private static final int ZERO_READ_BACKOFF_STEPS = 8;

    private final ExpectSettings options;
    private final CharsetPolicy charsetPolicy;
    private final ZeroReadBackoff zeroReadBackoff;
    private final OutputPumpCoordinator outputPumps;
    private final ExpectSessionState state;

    ExpectOutputTransport(
            DefaultSession session, ExpectSettings options, ZeroReadBackoff zeroReadBackoff, ExpectSessionState state) {
        Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNull(options, "options");
        charsetPolicy = CharsetPolicy.replace(options.charsetFor(session.charset()));
        this.zeroReadBackoff = Objects.requireNonNull(zeroReadBackoff, "zeroReadBackoff");
        outputPumps = new OutputPumpCoordinator(session, OUTPUT_OWNER);
        this.state = Objects.requireNonNull(state, "state");
    }

    void start(PumpStarter pumpStarter) {
        outputPumps.start(
                Objects.requireNonNull(pumpStarter, "pumpStarter"),
                "procwright-expect-stdout-",
                stream -> pump("stdout", stream, true),
                "procwright-expect-stderr-",
                stream -> pump("stderr", stream, false),
                state::abortStartup);
    }

    void closeSession() {
        outputPumps.closeSession();
    }

    private void pump(String streamName, InputStream stream, boolean matchable) {
        IncrementalTextDecoder decoder = null;
        try (stream) {
            int configuredLimit = matchable ? options.matchBufferLimit() : options.transcriptLimit();
            decoder = new IncrementalTextDecoder(
                    charsetPolicy,
                    IncrementalTextDecoder.pendingByteLimitFor(configuredLimit),
                    IncrementalTextDecoder.outputWithoutInputLimitFor(configuredLimit));
            IncrementalTextDecoder activeDecoder = decoder;
            IncrementalAnsiControlSequenceStripper ansiStripper =
                    options.ansiControlSequenceStripping() ? new IncrementalAnsiControlSequenceStripper() : null;
            IncrementalTextDecoder.Sink sink = (chars, count) ->
                    state.publishDecoded(streamName, matchable, applyAnsiStripping(ansiStripper, chars, count));
            byte[] buffer = new byte[1024];
            int consecutiveZeroReads = 0;
            while (!state.isStopping()) {
                int count = stream.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    consecutiveZeroReads = Math.min(consecutiveZeroReads + 1, ZERO_READ_BACKOFF_STEPS);
                    if (!zeroReadBackoff.pause(consecutiveZeroReads, state::isStopping)) {
                        return;
                    }
                    continue;
                }
                consecutiveZeroReads = 0;
                if (state.isStopping()) {
                    return;
                }
                activeDecoder.decode(buffer, count, sink);
                state.markMalformed(activeDecoder.malformed());
            }
            if (state.isStopping()) {
                return;
            }
            activeDecoder.end(sink);
            state.markMalformed(activeDecoder.malformed());
            if (ansiStripper != null) {
                state.publishDecoded(streamName, matchable, ansiStripper.finish());
            }
            if (matchable) {
                state.recordStdoutEof();
            }
        } catch (Throwable throwable) {
            if (!state.isClosed() || throwable instanceof RuntimeException || throwable instanceof Error) {
                state.markMalformed(decoder != null && decoder.malformed());
                failOutput(throwable);
            }
        }
    }

    private void failOutput(Throwable failure) {
        Thread failureThread = Thread.currentThread();
        ExpectSessionState.OutputFailureDecision decision = state.recordOutputFailure(failure);
        Error fatalToPublish = decision.fatalToPublish();
        Runnable publication =
                fatalToPublish == null ? null : () -> state.reportLateFatal(failureThread, fatalToPublish);
        if (decision.first()) {
            if (publication == null) {
                outputPumps.closeSessionPreserving(failure);
            } else {
                outputPumps.closeSessionPreserving(failure, publication);
            }
        } else if (publication != null) {
            outputPumps.publishAfterOutputCleanup(publication);
        }
    }

    private static String applyAnsiStripping(
            IncrementalAnsiControlSequenceStripper ansiStripper, char[] chars, int count) {
        String decoded = new String(chars, 0, count);
        return ansiStripper == null ? decoded : ansiStripper.append(decoded);
    }
}
