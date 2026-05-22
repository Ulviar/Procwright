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

    private final BoundedTranscriptBuffer delegate;
    private final CharsetPolicy charsetPolicy;
    private final Map<String, StreamDecoder> decoders = new HashMap<>();
    private boolean malformed;

    ProtocolTranscriptBuffer(int limit, CharsetPolicy charsetPolicy) {
        delegate = new BoundedTranscriptBuffer(limit);
        this.charsetPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
    }

    synchronized void appendStream(String label, byte[] bytes, int count) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(0, count, bytes.length);
        delegate.appendStream(label, decoder(label).decode(bytes, count));
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
        private ByteBuffer input = ByteBuffer.allocate(0);

        private String decode(byte[] bytes, int count) {
            appendInput(bytes, count);
            CharBuffer output = CharBuffer.allocate(128);
            StringBuilder text = new StringBuilder(Math.min(count, 128));
            while (true) {
                CoderResult result = decoder.decode(input, output, false);
                appendOutput(output, text);
                if (result.isOverflow()) {
                    continue;
                }
                if (result.isUnderflow()) {
                    input.compact();
                    return text.toString();
                }
                appendReplacement(text);
                skipMalformedInput(result);
            }
        }

        private void appendInput(byte[] bytes, int count) {
            input.flip();
            ByteBuffer combined = ByteBuffer.allocate(input.remaining() + count);
            combined.put(input);
            combined.put(bytes, 0, count);
            combined.flip();
            input = combined;
        }

        private void appendOutput(CharBuffer output, StringBuilder text) {
            output.flip();
            text.append(output);
            output.clear();
        }

        private void skipMalformedInput(CoderResult result) {
            malformed = true;
            input.position(Math.min(input.limit(), input.position() + result.length()));
        }

        private void appendReplacement(StringBuilder text) {
            text.append(decoder.replacement());
        }
    }
}
