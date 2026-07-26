/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionOutputDecodingTest extends DefaultLineSessionTestSupport {

    @Test
    void overflowPrefixIsNeverPublishedWhenSameDecodeCallEndsMalformed() throws Exception {
        OverflowThenMalformedCharset charset = new OverflowThenMalformedCharset();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new ByteArrayInputStream(new byte[] {'x', 'y'}),
                InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(
                rawSession, options(charset), LineSessionTestDependencies.withBackoff(ZeroReadBackoff.exponential()))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<LineResponse> request = executor.submit(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
                assertTrue(charset.awaitBeforeMalformed());
                charset.releaseMalformed();

                ExecutionException requestFailure =
                        assertThrows(ExecutionException.class, () -> request.get(1, TimeUnit.SECONDS));
                LineSessionException failure = assertInstanceOf(LineSessionException.class, requestFailure.getCause());

                assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
                assertFalse(failure.transcript().text().contains("ok"));
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
            } finally {
                charset.releaseMalformed();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void stderrDecodeFailureIdentifiesDiagnosticStream() throws Exception {
        InputStream stdout = new BlockingUntilClosedInputStream();
        InputStream stderr = new ByteArrayInputStream(new byte[] {(byte) 0xC3});
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, options(StandardCharsets.UTF_8))) {
            lineSession.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);

            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> lineSession.request("request"));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
            assertTrue(failure.getMessage().contains("Could not decode line-session stderr"));
            assertTrue(failure.transcript().malformed());
        }
    }

    private static final class OverflowThenMalformedCharset extends Charset {

        final CountDownLatch beforeMalformed = new CountDownLatch(1);
        final CountDownLatch releaseMalformed = new CountDownLatch(1);

        OverflowThenMalformedCharset() {
            super("X-Procwright-Line-Transactional-Overflow-Malformed", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                boolean overflowed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!overflowed) {
                        if (!input.hasRemaining()) {
                            return CoderResult.UNDERFLOW;
                        }
                        input.get();
                        char[] line = {'o', 'k', '\n'};
                        int index = 0;
                        while (output.hasRemaining()) {
                            output.put(line[index++ % line.length]);
                        }
                        overflowed = true;
                        return CoderResult.OVERFLOW;
                    }
                    beforeMalformed.countDown();
                    awaitUninterruptibly(releaseMalformed);
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        boolean awaitBeforeMalformed() throws InterruptedException {
            return beforeMalformed.await(1, TimeUnit.SECONDS);
        }

        void releaseMalformed() {
            releaseMalformed.countDown();
        }
    }
}
