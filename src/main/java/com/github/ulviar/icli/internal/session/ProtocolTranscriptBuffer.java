package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.command.CharsetPolicy;
import com.github.ulviar.icli.session.ProtocolTranscript;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ProtocolTranscriptBuffer {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private final BoundedTranscriptBuffer delegate;
    private final CharsetPolicy charsetPolicy;
    private final Map<String, StreamDecoder> decoders = new HashMap<>();
    private boolean malformed;

    ProtocolTranscriptBuffer(int limit, CharsetPolicy charsetPolicy) {
        delegate = new BoundedTranscriptBuffer(limit);
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
    }

    synchronized void appendStream(String label, byte[] bytes, int count) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(0, count, bytes.length);
        decoder(label).decodeInto(label, bytes, count);
    }

    synchronized ProtocolTranscript snapshot() {
        BoundedTranscriptBuffer.Snapshot snapshot = delegate.snapshot();
        return new ProtocolTranscript(snapshot.text(), snapshot.truncated(), malformed, false);
    }

    private StreamDecoder decoder(String label) {
        return decoders.computeIfAbsent(label, ignored -> new StreamDecoder());
    }

    private final class StreamDecoder {

        private final CharsetDecoder decoder = charsetPolicy
                .charset()
                .newDecoder()
                .onMalformedInput(charsetPolicy.malformedInputAction())
                .onUnmappableCharacter(charsetPolicy.unmappableCharacterAction());
        private byte[] pending = EMPTY_BYTES;
        private int pendingCount;

        private void decodeInto(String label, byte[] bytes, int count) {
            ByteBuffer input = sourceBuffer(bytes, count);
            CharBuffer output = CharBuffer.allocate(128);
            while (true) {
                CoderResult result = decoder.decode(input, output, false);
                appendOutput(label, output);
                if (result.isOverflow()) {
                    continue;
                }
                if (result.isUnderflow()) {
                    retainPending(input);
                    return;
                }
                appendReplacement(label);
                skipMalformedInput(input, result);
            }
        }

        private ByteBuffer sourceBuffer(byte[] bytes, int count) {
            if (pendingCount == 0) {
                return ByteBuffer.wrap(bytes, 0, count);
            }
            ByteBuffer combined = ByteBuffer.allocate(pendingCount + count);
            combined.put(pending, 0, pendingCount);
            combined.put(bytes, 0, count);
            combined.flip();
            pending = EMPTY_BYTES;
            pendingCount = 0;
            return combined;
        }

        private void retainPending(ByteBuffer input) {
            int remaining = input.remaining();
            if (remaining == 0) {
                pending = EMPTY_BYTES;
                pendingCount = 0;
                return;
            }
            if (pending.length < remaining) {
                pending = new byte[remaining];
            }
            input.get(pending, 0, remaining);
            pendingCount = remaining;
        }

        private void appendOutput(String label, CharBuffer output) {
            output.flip();
            int count = output.remaining();
            if (count > 0) {
                delegate.appendStream(label, output.array(), count);
            }
            output.clear();
        }

        private void skipMalformedInput(ByteBuffer input, CoderResult result) {
            malformed = true;
            input.position(Math.min(input.limit(), input.position() + result.length()));
        }

        private void appendReplacement(String label) {
            delegate.appendStream(label, decoder.replacement());
        }
    }
}
