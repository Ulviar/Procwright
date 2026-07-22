/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Timeout(time = 15, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@State(Scope.Thread)
public class ExpectMatchBufferBenchmark {

    private static final int CHUNKS = 4_096;
    private static final int CHUNK_SIZE = 1_024;
    private static final int MATCH_BUFFER_LIMIT = 1024 * 1024;

    @Param({"x"})
    public String fill;

    private String chunk;
    private BoundedMatchBuffer buffer;
    private BoundedMatchBuffer.LiteralMatcher matcher;

    @Setup(Level.Invocation)
    public void setupInvocation() {
        chunk = fill.repeat(CHUNK_SIZE);
        buffer = new BoundedMatchBuffer(MATCH_BUFFER_LIMIT);
        matcher = buffer.literalMatcher("needle-not-present");
    }

    @Benchmark
    @OperationsPerInvocation(CHUNKS)
    public long largeUnmatchedOutputInSmallChunks() {
        for (int index = 0; index < CHUNKS; index++) {
            buffer.append(chunk);
            if (matcher.find(0) != null) {
                throw new IllegalStateException("unexpected literal match");
            }
        }
        return buffer.revision() ^ buffer.startOffset() ^ buffer.endOffset();
    }
}
