/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.nio.charset.CharacterCodingException;
import java.util.Objects;

final class ProtocolTranscriptBuffer {

    private final BoundedTranscriptBuffer delegate;
    private final IncrementalTextDecoder stdoutDecoder;
    private final IncrementalTextDecoder stderrDecoder;
    private boolean malformed;

    ProtocolTranscriptBuffer(int limit, CharsetPolicy charsetPolicy) {
        delegate = new BoundedTranscriptBuffer(limit);
        CharsetPolicy requestedPolicy = Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        CharsetPolicy transcriptPolicy = CharsetPolicy.replace(requestedPolicy.charset());
        int pendingByteLimit = IncrementalTextDecoder.pendingByteLimitFor(limit);
        int outputWithoutInputLimit = IncrementalTextDecoder.outputWithoutInputLimitFor(limit);
        IncrementalTextDecoder initializedStdoutDecoder =
                createDecoder(transcriptPolicy, pendingByteLimit, outputWithoutInputLimit);
        IncrementalTextDecoder initializedStderrDecoder =
                createDecoder(transcriptPolicy, pendingByteLimit, outputWithoutInputLimit);
        stdoutDecoder = initializedStdoutDecoder;
        stderrDecoder = initializedStderrDecoder;
    }

    synchronized void appendStream(String label, byte[] bytes, int count) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(0, count, bytes.length);
        IncrementalTextDecoder decoder = decoder(label);
        decode(decoder, label, sink -> decoder.decode(bytes, count, sink));
    }

    synchronized void endStream(String label) {
        Objects.requireNonNull(label, "label");
        IncrementalTextDecoder decoder = decoder(label);
        decode(decoder, label, decoder::end);
    }

    synchronized ProtocolTranscript snapshot() {
        BoundedTranscriptBuffer.Snapshot snapshot = delegate.snapshot();
        return new ProtocolTranscript(snapshot.text(), snapshot.truncated(), malformed);
    }

    private IncrementalTextDecoder decoder(String label) {
        return switch (label) {
            case "stdout" -> stdoutDecoder;
            case "stderr" -> stderrDecoder;
            default -> throw new IllegalArgumentException("unknown protocol transcript stream: " + label);
        };
    }

    private static IncrementalTextDecoder createDecoder(
            CharsetPolicy transcriptPolicy, int pendingByteLimit, int outputWithoutInputLimit) {
        return new IncrementalTextDecoder(transcriptPolicy, pendingByteLimit, outputWithoutInputLimit);
    }

    private void decode(IncrementalTextDecoder decoder, String label, DecodeOperation operation) {
        try {
            operation.run((chars, count) -> delegate.appendStream(label, chars, count));
        } catch (CharacterCodingException exception) {
            malformed = true;
            throw new TranscriptDecodingException(exception);
        } finally {
            malformed |= decoder.malformed();
        }
    }

    @FunctionalInterface
    private interface DecodeOperation {

        void run(IncrementalTextDecoder.Sink sink) throws CharacterCodingException;
    }

    static final class TranscriptDecodingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private TranscriptDecodingException(CharacterCodingException cause) {
            super("Could not decode protocol transcript", cause);
        }
    }
}
