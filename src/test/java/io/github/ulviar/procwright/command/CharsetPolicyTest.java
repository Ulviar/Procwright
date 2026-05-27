package io.github.ulviar.procwright.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class CharsetPolicyTest {

    @Test
    void replacePolicyPreservesLenientDefaultDecoding() throws Exception {
        String decoded = CharsetPolicy.replace(StandardCharsets.UTF_8).decode(new byte[] {(byte) 0xFF});

        assertEquals("\uFFFD", decoded);
    }

    @Test
    void reportPolicyRejectsMalformedInput() {
        assertThrows(CharacterCodingException.class, () -> CharsetPolicy.report(StandardCharsets.UTF_8)
                .decode(new byte[] {(byte) 0xFF}));
    }

    @Test
    void rejectsSilentIgnorePolicy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CharsetPolicy(StandardCharsets.UTF_8, CodingErrorAction.IGNORE, CodingErrorAction.REPORT));
    }
}
