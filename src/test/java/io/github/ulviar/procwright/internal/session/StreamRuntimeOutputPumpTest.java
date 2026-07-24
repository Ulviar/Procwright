/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StreamRuntimeOutputPumpTest extends StreamRuntimeOutputPumpTestSupport {

    @Test
    void outputReadFailureHasStableReason() throws Exception {
        IOException readFailure = new IOException("read failed");
        ControllableProcess process =
                new ControllableProcess(new FailingInputStream(readFailure), InputStream.nullInputStream(), null);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
            assertSame(readFailure, streamFailure.getCause());
        } finally {
            stream.close();
        }
    }

    @Test
    void decoderInitializationFailureHasStableReasonForEitherPump() throws Exception {
        for (String source : List.of("stdout", "stderr")) {
            IllegalArgumentException cause = new IllegalArgumentException(source + " decoder initialization failed");
            Charset charset = new ThreadSelectedNewDecoderFailureCharset(source, cause);
            ControllableProcess process = new ControllableProcess();
            DefaultSession rawSession = session(process);
            StreamSession stream = new DefaultStreamSession(rawSession, plan(charset, 16), diagnostics());
            try {
                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
                assertSame(cause, streamFailure.getCause());
                assertEquals(true, streamFailure.diagnostics().text().length() <= 16);
                assertFalse(process.isAlive());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    void decoderRuntimeFailureHasStableReasonAndCleansUp() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("stream decoder failed");
        Charset charset = new RuntimeFailureCharset(cause);
        ControllableProcess process =
                new ControllableProcess(new ByteArrayInputStream(new byte[] {1}), InputStream.nullInputStream(), null);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(charset, 16), diagnostics());
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
            assertEquals(true, causeChainContains(streamFailure, cause));
            assertEquals(true, streamFailure.diagnostics().text().length() <= 16);
            assertFalse(process.isAlive());
        } finally {
            stream.close();
        }
    }

    @Test
    void outputOnlyDecoderIsBoundedForEitherPump() throws Exception {
        for (String failingSource : List.of("stdout", "stderr")) {
            Charset charset = new ThreadSelectedOutputOnlyCharset(failingSource);
            CloseTrackingInputStream failing = new CloseTrackingInputStream(new byte[] {1});
            BlockingUntilClosedInputStream other = new BlockingUntilClosedInputStream();
            InputStream stdout = failingSource.equals("stdout") ? failing : other;
            InputStream stderr = failingSource.equals("stderr") ? failing : other;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            AtomicInteger listenerCalls = new AtomicInteger();
            DefaultSession rawSession = session(process);
            StreamSession stream = new DefaultStreamSession(
                    rawSession, plan(charset, 16, chunk -> listenerCalls.incrementAndGet()), diagnostics());
            try {
                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
                assertInstanceOf(IncrementalTextDecoder.DecoderStateException.class, streamFailure.getCause());
                assertTrue(streamFailure.diagnostics().text().length() <= 16);
                assertEquals(0, listenerCalls.get(), "transactional decoding must not publish hostile staged output");
                assertTrue(failing.awaitClose());
                assertEquals(1, failing.closeCalls());
                assertEquals(1, other.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    void zeroLengthPumpsBackOffAndStopAfterCloseForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            CloseTrackingInputStream eofStream = new CloseTrackingInputStream(new byte[0]);
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : eofStream;
            InputStream stderr = zeroStdout ? eofStream : zeroStream;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            DefaultSession rawSession = session(process);
            StreamSession stream =
                    new DefaultStreamSession(rawSession, plan(), diagnostics(), backoff, PumpStarter.threading());
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads());

                stream.close();
                backoff.release();
                stream.onExit().get(1, TimeUnit.SECONDS);

                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive());
                assertEquals(1, zeroStream.reads());
                assertTrue(zeroStream.awaitClose());
                assertTrue(eofStream.awaitClose());
                assertEquals(1, zeroStream.closeCalls());
                assertEquals(1, eofStream.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                backoff.release();
                stream.close();
            }
        }
    }
}
