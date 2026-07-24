/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.parseLength;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.concurrent.atomic.AtomicInteger;

final class ProtocolFramingIntegrationSupport {

    private ProtocolFramingIntegrationSupport() {}

    static final class ExactTextFieldAdapter implements ProtocolAdapter<byte[], String> {

        @Override
        public void writeRequest(byte[] request, ProtocolWriter writer) {
            writer.writeLine(Integer.toString(request.length));
            writer.write(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            return stdout.readTextExactly(length, 32);
        }
    }

    static final class DelimiterBytesAdapter implements ProtocolAdapter<String, byte[]> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public byte[] readResponse(ProtocolReaders readers) {
            return readers.stdout().readUntil((byte) '\n', 512 * 1024);
        }
    }

    static final class AlternatingEncoderCharset extends Charset {

        private final AtomicInteger createdEncoders = new AtomicInteger();

        AlternatingEncoderCharset() {
            super("X-Procwright-Alternating-Encoder", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            boolean duplicate = createdEncoders.getAndIncrement() > 0;
            return new CharsetEncoder(this, 1, duplicate ? 2 : 1) {
                @Override
                protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                    int bytesPerChar = duplicate ? 2 : 1;
                    while (input.hasRemaining()) {
                        if (output.remaining() < bytesPerChar) {
                            return CoderResult.OVERFLOW;
                        }
                        byte value = (byte) input.get();
                        output.put(value);
                        if (duplicate) {
                            output.put(value);
                        }
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        int encoderCreations() {
            return createdEncoders.get();
        }
    }
}
