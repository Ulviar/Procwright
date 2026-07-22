/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolTranscript;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Timeout(time = 15, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
public class ProtocolTextFramingBenchmark {

    private static final int CONTENT_BYTES = 1024 * 1024;
    private static final ProtocolRuntimeFailures FAILURES = new ProtocolRuntimeFailures() {
        @Override
        public ProtocolSessionException timeout(Throwable cause) {
            return failure(ProtocolSessionException.Reason.TIMEOUT, "timeout", cause);
        }

        @Override
        public ProtocolSessionException closed(Throwable cause) {
            return failure(ProtocolSessionException.Reason.CLOSED, "closed", cause);
        }

        @Override
        public ProtocolSessionException eof() {
            return failure(ProtocolSessionException.Reason.EOF, "eof", null);
        }

        @Override
        public ProtocolSessionException failure(
                ProtocolSessionException.Reason reason, String message, Throwable cause) {
            return new ProtocolSessionException(reason, new ProtocolTranscript("", false, false), message, cause);
        }
    };

    private byte[] frame;
    private ProtocolResponseReader reader;

    @Setup(Level.Trial)
    public void setupTrial() {
        frame = new byte[CONTENT_BYTES + 1];
        Arrays.fill(frame, 0, CONTENT_BYTES, (byte) 'a');
        frame[CONTENT_BYTES] = '\n';
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        ProtocolOutputQueue queue = new ProtocolOutputQueue(frame.length, ProtocolOutputQueue.OverflowPolicy.STRICT);
        queue.offer(frame);
        ProtocolSessionSettings settings = ProtocolSessionSettings.defaults()
                .withOutputBacklogLimit(frame.length)
                .withMaxResponseBytes(frame.length)
                .withMaxResponseChars(frame.length);
        reader = new ProtocolResponseReader(
                queue,
                settings,
                System.nanoTime() + Duration.ofSeconds(10).toNanos(),
                new ProtocolResponseBudget(frame.length, frame.length, FAILURES),
                new ProtocolTextDecoderState(settings.charsetPolicy(), frame.length),
                FAILURES,
                RequestCapabilityScope.unrestricted("JMH protocol reader"));
    }

    @Benchmark
    public int oneMegabyteUtf8Line() {
        return reader.readLine(CONTENT_BYTES).length();
    }
}
