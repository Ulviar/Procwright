/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class DefaultPooledLineSessionTest {

    @Test
    void explicitProcessExitUsesProcessExitRetirementReason() {
        LineSessionException failure = new LineSessionException(
                LineSessionException.Reason.PROCESS_EXITED, new LineTranscript("", false, false), "process exited");

        assertEquals(PooledWorkerRetireReason.PROCESS_EXITED, DefaultPooledLineSession.retireReasonFor(failure));
    }

    @Test
    void realPooledRequestMetricsIncludeEncodingBeforeAcquire() {
        AtomicLong clock = new AtomicLong(100);
        Charset encodingClock = new AdvancingAsciiCharset(clock, 50);
        IllegalStateException startupFailure = new IllegalStateException("worker startup failed");
        DefaultPooledLineSession pool = new DefaultPooledLineSession(
                () -> {
                    throw startupFailure;
                },
                LineSessionSettings.defaults().withCharsetPolicy(CharsetPolicy.replace(encodingClock)),
                WorkerPoolSettings.defaults(worker -> {}, worker -> true),
                clock::get);
        try {
            PooledLineSessionException failure =
                    assertThrows(PooledLineSessionException.class, () -> pool.request("request"));

            assertEquals(PooledLineSessionException.Reason.STARTUP_FAILED, failure.reason());
            assertEquals(startupFailure, failure.getCause());
            PooledLineSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.failedRequests());
            assertEquals(50, metrics.totalRequestDurationNanos());
            assertEquals(0, metrics.totalAcquireWaitNanos());
        } finally {
            pool.close();
        }
    }

    private static final class AdvancingAsciiCharset extends Charset {

        private final AtomicLong clock;
        private final long encodingNanos;

        private AdvancingAsciiCharset(AtomicLong clock, long encodingNanos) {
            super("X-Procwright-Advancing-ASCII", new String[0]);
            this.clock = clock;
            this.encodingNanos = encodingNanos;
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.US_ASCII.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.US_ASCII.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return new CharsetEncoder(this, 1, 1) {
                @Override
                protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                    clock.compareAndSet(100, 100 + encodingNanos);
                    while (input.hasRemaining()) {
                        if (!output.hasRemaining()) {
                            return CoderResult.OVERFLOW;
                        }
                        char value = input.get();
                        if (value > 0x7f) {
                            return CoderResult.unmappableForLength(1);
                        }
                        output.put((byte) value);
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }
    }
}
