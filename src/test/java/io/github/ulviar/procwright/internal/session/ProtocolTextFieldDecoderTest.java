/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import java.nio.charset.StandardCharsets;
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

    private static final class ChunkedInput {

        private final byte[] bytes;
        private int position;

        private ChunkedInput(byte[] bytes) {
            this.bytes = bytes;
        }

        private int read(byte[] target, int offset, int length) {
            int count = Math.min(1, Math.min(length, bytes.length - position));
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }
    }
}
