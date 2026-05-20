package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.command.CharsetPolicy;
import com.github.ulviar.icli.session.ProtocolTranscript;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ProtocolTranscriptBuffer {

    private final BoundedTranscriptBuffer delegate;
    private final CharsetPolicy charsetPolicy;
    private final Map<String, byte[]> pendingBytes = new HashMap<>();
    private boolean malformed;

    ProtocolTranscriptBuffer(int limit, CharsetPolicy charsetPolicy) {
        delegate = new BoundedTranscriptBuffer(limit);
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
    }

    synchronized void appendStream(String label, byte[] bytes, int count) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(0, count, bytes.length);
        byte[] retained = java.util.Arrays.copyOf(bytes, count);
        delegate.appendStream(label, decodeForTranscript(label, retained));
    }

    synchronized ProtocolTranscript snapshot() {
        BoundedTranscriptBuffer.Snapshot snapshot = delegate.snapshot();
        return new ProtocolTranscript(snapshot.text(), snapshot.truncated(), malformed, false);
    }

    private String decodeForTranscript(String label, byte[] bytes) {
        byte[] pending = pendingBytes.remove(label);
        byte[] candidate = pending == null ? bytes : concatenate(pending, bytes);
        int incompleteSuffix = utf8IncompleteSuffixLength(candidate);
        byte[] decodable = candidate;
        if (incompleteSuffix > 0) {
            decodable = Arrays.copyOf(candidate, candidate.length - incompleteSuffix);
            pendingBytes.put(
                    label, Arrays.copyOfRange(candidate, candidate.length - incompleteSuffix, candidate.length));
        }
        try {
            return charsetPolicy.decode(decodable);
        } catch (CharacterCodingException exception) {
            pendingBytes.remove(label);
            malformed = true;
            try {
                return CharsetPolicy.replace(charsetPolicy.charset()).decode(candidate);
            } catch (CharacterCodingException impossible) {
                throw new AssertionError("replacement decoding must not fail", impossible);
            }
        }
    }

    private int utf8IncompleteSuffixLength(byte[] bytes) {
        if (!StandardCharsets.UTF_8.equals(charsetPolicy.charset()) || bytes.length == 0) {
            return 0;
        }
        int index = bytes.length - 1;
        int continuationCount = 0;
        while (index >= 0 && isUtf8Continuation(bytes[index])) {
            continuationCount++;
            index--;
        }
        if (index < 0) {
            return 0;
        }
        int expectedLength = utf8SequenceLength(bytes[index]);
        if (expectedLength <= 1) {
            return 0;
        }
        int availableLength = bytes.length - index;
        return availableLength < expectedLength && continuationCount == availableLength - 1 ? availableLength : 0;
    }

    private static boolean isUtf8Continuation(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private static int utf8SequenceLength(byte value) {
        int unsigned = value & 0xFF;
        if ((unsigned & 0x80) == 0) {
            return 1;
        }
        if ((unsigned & 0xE0) == 0xC0) {
            return 2;
        }
        if ((unsigned & 0xF0) == 0xE0) {
            return 3;
        }
        if ((unsigned & 0xF8) == 0xF0) {
            return 4;
        }
        return -1;
    }

    private static byte[] concatenate(byte[] left, byte[] right) {
        byte[] combined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }
}
