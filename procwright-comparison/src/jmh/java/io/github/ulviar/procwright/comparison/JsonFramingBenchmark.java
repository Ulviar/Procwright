/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import io.github.ulviar.procwright.integration.ContentLengthJsonFrames;
import io.github.ulviar.procwright.integration.JsonValue;
import io.github.ulviar.procwright.integration.ProtocolAdapters;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class JsonFramingBenchmark extends JmhSettings {

    private static final int STRING_CHARS = 1024 * 1024;

    private JsonValue request;
    private ProtocolAdapter<JsonValue, JsonValue> adapter;
    private DiscardingWriter writer;

    @Setup(Level.Trial)
    public void setupTrial() {
        request = JsonValue.string("x".repeat(STRING_CHARS));
        adapter = ProtocolAdapters.contentLengthJsonSession(Integer.MAX_VALUE).get();
        writer = new DiscardingWriter();
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        writer.reset();
    }

    @Benchmark
    public int materializedFrame() {
        return ContentLengthJsonFrames.frame(request).length;
    }

    @Benchmark
    public long streamedProtocolRequest() {
        adapter.writeRequest(request, writer);
        return writer.writtenBytes;
    }

    private static final class DiscardingWriter implements ProtocolWriter {

        private long writtenBytes;

        @Override
        public void write(byte[] bytes) {
            writtenBytes += bytes.length;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writtenBytes += length;
        }

        @Override
        public void write(String text) {
            throw new UnsupportedOperationException("benchmark expects byte streaming");
        }

        @Override
        public void writeLine(String line) {
            throw new UnsupportedOperationException("benchmark expects byte streaming");
        }

        @Override
        public void flush() {}

        private void reset() {
            writtenBytes = 0;
        }
    }
}
