/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionDecoderLifecycleFailureIntegrationTest {

    @Test
    void decoderInitializationRuntimeFailureClosesLineSessionBeforeEitherPumpStarts() {
        for (int failingCreation : List.of(1, 2)) {
            IllegalArgumentException cause =
                    new IllegalArgumentException("decoder creation " + failingCreation + " failed");
            IndexedNewDecoderFailureCharset charset = new IndexedNewDecoderFailureCharset(failingCreation, cause);
            LineSessionScenario.Draft service =
                    fixtureScenario().withTranscriptLimit(32).withCharsetPolicy(CharsetPolicy.report(charset));

            LineSessionException exception = assertThrows(
                    LineSessionException.class,
                    () -> openLineSession(
                            service, call -> call.withArgs("partial", "--stdout=", "--stderr=", "--hold-millis=5000")));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertSame(cause, exception.getCause());
            assertTrue(exception.transcript().text().length() <= 32);
            assertEquals(failingCreation, charset.decoderCreations());
        }
    }

    @Test
    void decoderRuntimeFailureClosesLineSessionForEitherOutputStream() throws Exception {
        for (FailingOutput output : FailingOutput.values()) {
            IllegalArgumentException cause = new IllegalArgumentException("line decoder failed");
            LineSessionScenario.Draft service = fixtureScenario()
                    .withTranscriptLimit(32)
                    .withCharsetPolicy(CharsetPolicy.report(new MarkerRuntimeFailureCharset((byte) 'x', cause)));
            LineSession session = openLineSession(
                    service,
                    call -> call.withArgs(
                            "partial",
                            "--stdout=" + output.stdout(),
                            "--stderr=" + output.stderr(),
                            "--hold-millis=5000"));
            try {
                LineSessionException exception =
                        assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

                assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
                assertTrue(causeChainContains(exception, cause));
                assertTrue(exception.transcript().text().length() <= 32);
                session.onExit().get(2, TimeUnit.SECONDS);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void decoderFlushRuntimeFailureClosesLineSession() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("line decoder flush failed");
        LineSessionScenario.Draft service = fixtureScenario()
                .withTranscriptLimit(32)
                .withCharsetPolicy(CharsetPolicy.report(new RuntimeFailureOnFlushCharset(cause)));
        LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=100"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, cause));
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    private static boolean causeChainContains(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static CharsetDecoder passthroughDecoder(Charset charset) {
        return new CharsetDecoder(charset, 1, 1) {
            @Override
            protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((char) Byte.toUnsignedInt(input.get()));
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }

    private enum FailingOutput {
        STDOUT("x", ""),
        STDERR("", "x");

        private final String stdout;
        private final String stderr;

        FailingOutput(String stdout, String stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private String stdout() {
            return stdout;
        }

        private String stderr() {
            return stderr;
        }
    }

    private static final class IndexedNewDecoderFailureCharset extends Charset {

        private final int failingCreation;
        private final RuntimeException failure;
        private int decoderCreations;

        private IndexedNewDecoderFailureCharset(int failingCreation, RuntimeException failure) {
            super("X-Procwright-Line-Indexed-New-Decoder-Failure-" + failingCreation, new String[0]);
            this.failingCreation = failingCreation;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            decoderCreations++;
            if (decoderCreations == failingCreation) {
                throw failure;
            }
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int decoderCreations() {
            return decoderCreations;
        }
    }

    private static final class MarkerRuntimeFailureCharset extends Charset {

        private final byte marker;
        private final RuntimeException failure;

        private MarkerRuntimeFailureCharset(byte marker, RuntimeException failure) {
            super("X-Procwright-Line-Marker-Runtime-Failure", new String[0]);
            this.marker = marker;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        if (input.get(input.position()) == marker) {
                            throw failure;
                        }
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class RuntimeFailureOnFlushCharset extends Charset {

        private final RuntimeException failure;

        private RuntimeFailureOnFlushCharset(RuntimeException failure) {
            super("X-Procwright-Line-Runtime-Failure-On-Flush", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(java.nio.CharBuffer output) {
                    throw failure;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }
}
