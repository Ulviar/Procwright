/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.OneShotIntegrationFixtures.boxed;
import static io.github.ulviar.procwright.OneShotIntegrationFixtures.fixtureService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunDecoderFailureIntegrationTest {

    @Test
    void truncatedDecodeRejectsOverflowWithoutInputOrOutputProgress() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=4142")
                .withCapture(CapturePolicy.bounded(1))
                .withCharsetPolicy(CharsetPolicy.report(new OverflowProbeCharset(false)))
                .execute());

        assertDecodeFailureRetainsCapturedPrefix(exception);
    }

    @Test
    void truncatedDecodeBoundsOutputProducedWithoutConsumingInput() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=4142")
                .withCapture(CapturePolicy.bounded(1))
                .withCharsetPolicy(CharsetPolicy.report(new OverflowProbeCharset(true)))
                .execute());

        assertDecodeFailureRetainsCapturedPrefix(exception);
    }

    @Test
    void decoderCreationRuntimeFailureIsTypedAndRetainsResult() {
        IllegalStateException decoderFailure = new IllegalStateException("decoder creation failed");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(
                        CharsetPolicy.report(new FailingCharset(DecoderFailureStage.NEW_DECODER, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderConfigurationRuntimeFailureIsTypedAndRetainsResult() {
        IllegalStateException decoderFailure = new IllegalStateException("decoder configuration failed");

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(
                        CharsetPolicy.report(new FailingCharset(DecoderFailureStage.CONFIGURE, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderMalfunctionDuringDecodeIsTypedAndRetainsResult() {
        CoderMalfunctionError decoderFailure = new CoderMalfunctionError(new IllegalStateException("decode failed"));

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(CharsetPolicy.report(new FailingCharset(DecoderFailureStage.DECODE, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderMalfunctionDuringFlushIsTypedAndRetainsResult() {
        CoderMalfunctionError decoderFailure = new CoderMalfunctionError(new IllegalStateException("flush failed"));

        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(CharsetPolicy.report(new FailingCharset(DecoderFailureStage.FLUSH, decoderFailure)))
                .execute());

        assertTypedDecodeFailure(exception, decoderFailure, false);
    }

    @Test
    void decoderErrorOutsideTypedBoundaryPreservesIdentity() {
        AssertionError decoderFailure = new AssertionError("decoder failed irrecoverably");

        AssertionError thrown = assertThrows(AssertionError.class, () -> fixtureService()
                .run()
                .withArgs("binary", "--pattern=hex", "--hex=41")
                .withCharsetPolicy(
                        CharsetPolicy.report(new FailingCharset(DecoderFailureStage.NEW_DECODER, decoderFailure)))
                .execute());

        assertSame(decoderFailure, thrown);
    }

    static void assertDecodeFailureRetainsCapturedPrefix(CommandExecutionException exception) {
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
        CommandResult result = exception.result().orElseThrow();
        assertEquals(List.of((byte) 'A'), boxed(result.stdoutBytes()));
        assertEquals("A", result.stdout());
        assertTrue(result.stdoutTruncated());
    }

    static void assertTypedDecodeFailure(
            CommandExecutionException exception, Throwable decoderFailure, boolean truncated) {
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, exception.reason());
        assertSame(decoderFailure, exception.getCause());
        CommandResult result = exception.result().orElseThrow();
        assertEquals(List.of((byte) 'A'), boxed(result.stdoutBytes()));
        assertEquals("A", result.stdout());
        assertEquals(truncated, result.stdoutTruncated());
    }

    abstract static class TestCharset extends Charset {

        TestCharset(String canonicalName) {
            super(canonicalName, null);
        }

        @Override
        public boolean contains(Charset charset) {
            return charset == this;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    static final class OverflowProbeCharset extends TestCharset {

        private final boolean produceOutput;
        private int decoderCreations;

        OverflowProbeCharset(boolean produceOutput) {
            super(produceOutput ? "x-procwright-output-overflow" : "x-procwright-no-progress-overflow");
            this.produceOutput = produceOutput;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (decoderCreations++ > 0) {
                return StandardCharsets.US_ASCII.newDecoder();
            }
            return new CharsetDecoder(this, 1, 1) {
                private int calls;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (input.hasRemaining() && calls++ < 8) {
                        if (produceOutput) {
                            output.put('x');
                        }
                        return CoderResult.OVERFLOW;
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }
    }

    enum DecoderFailureStage {
        NEW_DECODER,
        CONFIGURE,
        DECODE,
        FLUSH
    }

    static final class FailingCharset extends TestCharset {

        private final DecoderFailureStage stage;
        private final Throwable failure;

        FailingCharset(DecoderFailureStage stage, Throwable failure) {
            super("x-procwright-failing-" + stage.name().toLowerCase(java.util.Locale.ROOT));
            this.stage = stage;
            this.failure = failure;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (stage == DecoderFailureStage.NEW_DECODER) {
                throwUnchecked(failure);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected void implOnMalformedInput(CodingErrorAction newAction) {
                    if (stage == DecoderFailureStage.CONFIGURE) {
                        throwUnchecked(failure);
                    }
                }

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (stage == DecoderFailureStage.DECODE) {
                        throwUnchecked(failure);
                    }
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(CharBuffer output) {
                    if (stage == DecoderFailureStage.FLUSH) {
                        throwUnchecked(failure);
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }
    }

    static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("test failure must be unchecked", failure);
    }
}
