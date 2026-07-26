/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.CloseTrackingInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.FeedInputStream;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.eventually;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.expect;
import static io.github.ulviar.procwright.internal.session.ExpectTestFixtures.passthroughDecoder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ExpectSettings;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultExpectOutputDecodingTest {

    @Test
    void ansiStrippingIsIncrementalAndAppliedToMatchingAndTranscript() throws Exception {
        FeedInputStream stdout = new FeedInputStream();
        FeedInputStream stderr = new FeedInputStream();
        DefaultExpect expect = expect(
                new ControllableProcess(stdout, stderr),
                ExpectSettings.defaults().withAnsiControlSequenceStripping());
        try {
            stdout.offer("\u001B[");
            stderr.offer("warning:\u001B[");
            stdout.offer("31mREADY");
            stderr.offer("1mFAIL\u001B[0m");
            stdout.offer("\u001B[0m");

            ExpectMatch match = expect.expectRegexMatch(Pattern.compile("^READY$"), Duration.ofSeconds(1));

            assertEquals("READY", match.matched());
            assertTrue(eventually(() -> {
                String transcript = expect.transcript().text();
                return transcript.contains("warning:") && transcript.contains("FAIL");
            }));
            assertFalse(expect.transcript().text().contains("\u001B"));
        } finally {
            expect.close();
        }
    }

    @Test
    void outputOnlyDecoderIsBoundedAndTerminatesExpectForEitherStream() throws Exception {
        for (String failingSource : List.of("stdout", "stderr")) {
            ThreadSelectedOutputOnlyCharset charset = new ThreadSelectedOutputOnlyCharset(failingSource);
            CloseTrackingInputStream failing = new CloseTrackingInputStream(new byte[] {1});
            BlockingUntilClosedInputStream other = new BlockingUntilClosedInputStream();
            InputStream stdout = failingSource.equals("stdout") ? failing : other;
            InputStream stderr = failingSource.equals("stderr") ? failing : other;
            ControllableProcess process = new ControllableProcess(stdout, stderr);
            DefaultExpect expect = expect(
                    process,
                    charset,
                    ExpectSettings.defaults().withTranscriptLimit(16).withMatchBufferLimit(16));
            try {
                ExpectException failure =
                        assertThrows(ExpectException.class, () -> expect.expectText("never", Duration.ofSeconds(1)));

                assertEquals(ExpectException.Reason.FAILURE, failure.reason());
                assertInstanceOf(IncrementalTextDecoder.DecoderStateException.class, failure.getCause());
                assertTrue(failure.transcript().malformed());
                assertTrue(failure.transcript().text().length() <= 16);
                ExecutionException exitFailure = assertThrows(
                        ExecutionException.class, () -> expect.onExit().get(1, TimeUnit.SECONDS));
                ExpectException terminal = assertInstanceOf(ExpectException.class, exitFailure.getCause());
                assertEquals(ExpectException.Reason.FAILURE, terminal.reason());
                assertSame(failure.getCause(), terminal.getCause());
                assertFalse(process.isAlive());
                assertTrue(failing.awaitClose());
                assertTrue(other.awaitClose());
                assertEquals(1, failing.closeCalls());
                assertEquals(1, other.closeCalls());
            } finally {
                expect.close();
            }
        }
    }

    private static final class ThreadSelectedOutputOnlyCharset extends Charset {

        private final String failingThreadFragment;

        private ThreadSelectedOutputOnlyCharset(String failingThreadFragment) {
            super("X-Procwright-Expect-Output-Only-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains(failingThreadFragment)) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (output.hasRemaining()) {
                        output.put('x');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class BlockingUntilClosedInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() {
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }
}
