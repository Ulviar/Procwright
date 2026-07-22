/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

/** Isolates bounded capture allocations from process startup and public result access. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Timeout(time = 15, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@State(Scope.Thread)
public class OneShotCaptureAllocationBenchmark {

    private static final int CAPTURE_LIMIT = 64 * 1024;
    private static final byte[] EMPTY_BYTES = new byte[0];

    @Param({"128", "8192", "20000", "262144"})
    public int inputBytes;

    private CapturePolicy.Bounded policy;
    private ByteArrayInputStream chunkInput;
    private ByteArrayInputStream oldShapeInput;
    private CommandResult result;

    @Setup
    public void setup() throws IOException {
        byte[] input = new byte[inputBytes];
        Arrays.fill(input, (byte) 'x');
        policy = CapturePolicy.bounded(CAPTURE_LIMIT);
        chunkInput = new ByteArrayInputStream(input);
        oldShapeInput = new ByteArrayInputStream(input);

        int retainedBytes = Math.min(input.length, CAPTURE_LIMIT);
        byte[] retained = Arrays.copyOf(input, retainedBytes);
        result = new CommandResult(
                OptionalInt.of(0),
                retained,
                EMPTY_BYTES,
                new String(retained, StandardCharsets.ISO_8859_1),
                "",
                input.length > CAPTURE_LIMIT,
                false,
                false,
                Duration.ZERO);

        verifyEquivalentCapture();
    }

    @Benchmark
    public CapturedOutput chunkAccumulatorCapture() throws IOException {
        chunkInput.reset();
        return CapturedOutput.capture(chunkInput, policy);
    }

    @Benchmark
    public OldCapturedOutput oldByteArrayOutputStreamControl() throws IOException {
        oldShapeInput.reset();
        return oldShapeCapture(oldShapeInput, CAPTURE_LIMIT);
    }

    @Benchmark
    public byte[] documentedCommandResultAccessorClone() {
        return result.stdoutBytes();
    }

    private void verifyEquivalentCapture() throws IOException {
        CapturedOutput current = chunkAccumulatorCapture();
        OldCapturedOutput control = oldByteArrayOutputStreamControl();
        if (!Arrays.equals(current.bytes(), control.bytes()) || current.truncated() != control.truncated()) {
            throw new IllegalStateException("capture benchmark implementations are not semantically equivalent");
        }
    }

    private static OldCapturedOutput oldShapeCapture(ByteArrayInputStream input, int limit) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = limit - retained.size();
            if (remaining > 0) {
                retained.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new OldCapturedOutput(retained.toByteArray(), truncated);
    }

    public record OldCapturedOutput(byte[] bytes, boolean truncated) {}
}
