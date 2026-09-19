/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ProtocolTextFieldDecoderTest extends ProtocolResponseReaderTestSupport {

    @Test
    void completeFieldsShareTheResponseCharacterBudgetWithoutSharingDecoderState() {
        byte[] encoded = "aé".getBytes(StandardCharsets.UTF_8);
        ChunkedInput input = new ChunkedInput(encoded);
        ProtocolResponseBudget budget = new ProtocolResponseBudget(encoded.length, 2, FAILURES);
        ProtocolTextFieldDecoder decoder = new ProtocolTextFieldDecoder(
                CharsetPolicy.report(StandardCharsets.UTF_8), budget, FAILURES, input::read);

        assertEquals("a", decoder.decode(1, 1));
        assertEquals("é", decoder.decode(2, 1));
        assertEquals(0, budget.remainingChars());
        assertEquals(encoded.length, input.position);
    }

    @Test
    void internalFieldContractRejectsNonPositiveLimitsBeforeReading() {
        ChunkedInput input = new ChunkedInput(new byte[] {'a'});
        ProtocolTextFieldDecoder decoder = new ProtocolTextFieldDecoder(
                CharsetPolicy.report(StandardCharsets.UTF_8),
                new ProtocolResponseBudget(1, 1, FAILURES),
                FAILURES,
                input::read);

        assertThrows(IllegalArgumentException.class, () -> decoder.decode(0, 1));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(1, 0));
        assertEquals(0, input.position);
    }

    @Test
    void oversizedFieldStopsBeforeReadingItsRemainingChunks() {
        byte[] encoded = new byte[128 * 1024];
        Arrays.fill(encoded, (byte) 'a');
        ChunkedInput input = new ChunkedInput(encoded, 8192);
        ProtocolTextFieldDecoder decoder = new ProtocolTextFieldDecoder(
                CharsetPolicy.report(StandardCharsets.UTF_8),
                new ProtocolResponseBudget(encoded.length, encoded.length, FAILURES),
                FAILURES,
                input::read);

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> decoder.decode(encoded.length, 9000));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertTrue(input.position > 9000);
        assertTrue(input.position <= 9000 + 8192, "refusal may consume only the next bounded chunk");
        assertTrue(input.position < encoded.length, "the remaining field must not be read");
    }

    @Test
    void partialReadIsDecodedBeforeRequestingMoreInput() {
        byte[] malformed = {(byte) 0xff};
        int[] reads = {0};
        ProtocolTextFieldDecoder decoder = new ProtocolTextFieldDecoder(
                CharsetPolicy.report(StandardCharsets.UTF_8),
                new ProtocolResponseBudget(8192, 8192, FAILURES),
                FAILURES,
                (target, offset, length) -> {
                    assertEquals(0, reads[0]++, "a partial read must be decoded without filling the input chunk");
                    assertTrue(length > malformed.length, "source should be allowed to return an available short read");
                    target[offset] = malformed[0];
                    return malformed.length;
                });

        ProtocolSessionException exception =
                assertThrows(ProtocolSessionException.class, () -> decoder.decode(8192, 8192));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertEquals(1, reads[0]);
    }

    private static final class ChunkedInput {

        private final byte[] bytes;
        private final int chunkSize;
        private int position;

        private ChunkedInput(byte[] bytes) {
            this(bytes, 1);
        }

        private ChunkedInput(byte[] bytes, int chunkSize) {
            this.bytes = bytes;
            this.chunkSize = chunkSize;
        }

        private int read(byte[] target, int offset, int length) {
            int count = Math.min(chunkSize, Math.min(length, bytes.length - position));
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }
    }
}
