/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class OneShotResultAssemblerTest {

    @Test
    void assemblesDecodedTextRawBytesAndExecutionMetadata() {
        byte[] stdout = "stdout".getBytes(StandardCharsets.UTF_8);
        byte[] stderr = "stderr".getBytes(StandardCharsets.UTF_8);
        Duration elapsed = Duration.ofMillis(17);

        CommandResult result = OneShotResultAssembler.assemble(
                new CapturedOutput(stdout, false),
                new CapturedOutput(stderr, true),
                CharsetPolicy.report(StandardCharsets.UTF_8),
                OptionalInt.of(23),
                true,
                elapsed);

        assertEquals(23, result.exitCode().orElseThrow());
        assertArrayEquals(stdout, result.stdoutBytes());
        assertArrayEquals(stderr, result.stderrBytes());
        assertEquals("stdout", result.stdout());
        assertEquals("stderr", result.stderr());
        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertTrue(result.timedOut());
        assertEquals(elapsed, result.elapsed());
    }

    @Test
    void stderrDecodeFailureRetainsRawDataAndExecutionMetadataWithoutUsingDisplayName() {
        IllegalStateException decoderFailure = new IllegalStateException("decoder failed");
        AssertionError displayNameFailure = new AssertionError("displayName must not be called");
        SecondDecoderFailureCharset charset = new SecondDecoderFailureCharset(decoderFailure, displayNameFailure);
        byte[] stdout = {'A'};
        byte[] stderr = {'B'};
        Duration elapsed = Duration.ofMillis(19);

        CommandExecutionException thrown = assertThrows(
                CommandExecutionException.class,
                () -> OneShotResultAssembler.assemble(
                        new CapturedOutput(stdout, false),
                        new CapturedOutput(stderr, true),
                        CharsetPolicy.report(charset),
                        OptionalInt.of(29),
                        true,
                        elapsed));

        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, thrown.reason());
        assertSame(decoderFailure, thrown.getCause());
        assertEquals(0, charset.displayNameCalls());
        CommandResult result = thrown.result().orElseThrow();
        assertEquals(29, result.exitCode().orElseThrow());
        assertArrayEquals(stdout, result.stdoutBytes());
        assertArrayEquals(stderr, result.stderrBytes());
        assertEquals("A", result.stdout());
        assertEquals("B", result.stderr());
        assertFalse(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertTrue(result.timedOut());
        assertEquals(elapsed, result.elapsed());
    }

    private static final class SecondDecoderFailureCharset extends Charset {

        private final RuntimeException decoderFailure;
        private final Error displayNameFailure;
        private final AtomicInteger decoderCreations = new AtomicInteger();
        private final AtomicInteger displayNameCalls = new AtomicInteger();

        private SecondDecoderFailureCharset(RuntimeException decoderFailure, Error displayNameFailure) {
            super("x-procwright-second-decoder-failure", null);
            this.decoderFailure = decoderFailure;
            this.displayNameFailure = displayNameFailure;
        }

        @Override
        public boolean contains(Charset charset) {
            return charset == this;
        }

        @Override
        public String displayName() {
            displayNameCalls.incrementAndGet();
            throw displayNameFailure;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (decoderCreations.incrementAndGet() == 2) {
                throw decoderFailure;
            }
            return StandardCharsets.US_ASCII.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.US_ASCII.newEncoder();
        }

        private int displayNameCalls() {
            return displayNameCalls.get();
        }
    }
}
