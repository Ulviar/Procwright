/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolOutputTransportTest extends ProtocolSessionContractSupport {

    @Test
    void lateFatalErrorPreservesAndPublishesTheEarlierOutputFailure() {
        TransportHarness harness = openTransport();
        ProtocolSessionSettings options = harness.options();
        ProtocolSessionState state = harness.state();
        ProtocolOutputTransport transport = harness.transport();
        IllegalStateException first = new IllegalStateException("first");
        AssertionError late = new AssertionError("late");
        state.recordOutputFailure(ProtocolSessionException.Reason.FAILURE, "first", first);

        transport.failFatal(late);

        ProtocolSessionState.FailureSnapshot selected = (ProtocolSessionState.FailureSnapshot) state.terminal();
        assertEquals(ProtocolSessionException.Reason.FAILURE, selected.reason());
        assertSame(first, selected.primary());
        assertEquals(List.of(late), harness.reportedFailures());
        assertSame(first, harness.closePrimary().get());

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
        assertSame(first, stdoutFailure.getCause());
        assertSame(first, stderrFailure.getCause());
    }

    private static ProtocolTextDecoderState decoder(ProtocolSessionSettings options) {
        return new ProtocolTextDecoderState(
                options.charsetPolicy(),
                ProtocolTextReader.pendingByteLimit(options),
                ProtocolTextReader.outputWithoutInputLimit(options));
    }

    private static TransportHarness openTransport() {
        ProtocolSessionSettings options = ProtocolSessionSettings.defaults();
        List<Throwable> reportedFailures = new ArrayList<>();
        AtomicReference<Throwable> closePrimary = new AtomicReference<>();
        ProtocolTranscriptBuffer transcript =
                new ProtocolTranscriptBuffer(options.transcriptLimit(), options.charsetPolicy());
        ProtocolSessionState state =
                new ProtocolSessionState(transcript::snapshot, OptionalInt::empty, reportedFailures::add);
        ProtocolOutputTransport transport = new ProtocolOutputTransport(
                options,
                state,
                ZeroReadBackoff.exponential(),
                reportedFailures::add,
                transcript,
                decoder(options),
                decoder(options),
                System::nanoTime,
                OptionalInt::empty,
                new ProtocolOutputTransport.FailureHandler() {
                    @Override
                    public void closeTerminalPreserving(Throwable failure) {
                        closePrimary.compareAndSet(null, failure);
                    }

                    @Override
                    public void closeQuietly(Throwable failure) {}
                });
        return new TransportHarness(options, state, transport, reportedFailures, closePrimary);
    }

    private record TransportHarness(
            ProtocolSessionSettings options,
            ProtocolSessionState state,
            ProtocolOutputTransport transport,
            List<Throwable> reportedFailures,
            AtomicReference<Throwable> closePrimary) {}
}
