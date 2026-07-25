/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class ProtocolOutputTransportTest extends ProtocolSessionContractSupport {

    @Test
    void losingTransportFailurePublishesTheCanonicalTerminalToBothQueues() {
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        ProtocolTranscriptBuffer transcript =
                new ProtocolTranscriptBuffer(options.transcriptLimit(), options.charsetPolicy());
        OutputPumpCoordinator pumps = new OutputPumpCoordinator(
                rawSession, "ProtocolOutputTransportTest", OutputPumpCoordinator.FailureAttribution.SCENARIO_TERMINAL);
        ProtocolSessionState state = new ProtocolSessionState(
                transcript::snapshot, OptionalInt::empty, pumps::retainFailure, pumps::sealFailureAttribution);
        ProtocolOutputTransport transport = new ProtocolOutputTransport(
                options,
                state,
                ZeroReadBackoff.exponential(),
                pumps,
                transcript,
                decoder(options),
                decoder(options),
                System::nanoTime,
                OptionalInt::empty,
                new ProtocolOutputTransport.FailureHandler() {
                    @Override
                    public void closeTerminalPreserving(Throwable failure) {}

                    @Override
                    public void closeQuietly(Throwable failure) {}
                });
        IllegalStateException winner = new IllegalStateException("winner");
        IllegalArgumentException loser = new IllegalArgumentException("loser");
        try {
            state.recordTerminalFailure(ProtocolSessionException.Reason.FAILURE, "winner", winner);

            assertSame(
                    winner,
                    transport.selectAndPublishFailure(ProtocolSessionException.Reason.DECODE_ERROR, "loser", loser));

            ProtocolResponseBudget budget =
                    new ProtocolResponseBudget(options.maxResponseBytes(), options.maxResponseChars(), state);
            RequestCapabilityScope capability = new RequestCapabilityScope("ProtocolOutputTransportTest");
            capability.activate();
            ProtocolReaders readers = transport.readers(System.nanoTime() + 1_000_000_000L, budget, state, capability);
            ProtocolSessionException stdoutFailure = assertThrows(
                    ProtocolSessionException.class, () -> readers.stdout().readByte());
            ProtocolSessionException stderrFailure = assertThrows(
                    ProtocolSessionException.class, () -> readers.stderr().readByte());

            assertEquals(ProtocolSessionException.Reason.FAILURE, stdoutFailure.reason());
            assertEquals(ProtocolSessionException.Reason.FAILURE, stderrFailure.reason());
            assertSame(winner, stdoutFailure.getCause());
            assertSame(winner, stderrFailure.getCause());
        } finally {
            rawSession.close();
        }
    }

    private static ProtocolTextDecoderState decoder(ProtocolSessionSettings options) {
        return new ProtocolTextDecoderState(
                options.charsetPolicy(),
                ProtocolTextReader.pendingByteLimit(options),
                ProtocolTextReader.outputWithoutInputLimit(options),
                ProtocolTextReader.decodedLineSuffixLimit(options));
    }
}
