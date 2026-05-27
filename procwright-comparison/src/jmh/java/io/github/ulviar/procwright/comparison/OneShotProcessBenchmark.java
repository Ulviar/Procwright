package io.github.ulviar.procwright.comparison;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

@Threads(1)
@State(Scope.Thread)
public class OneShotProcessBenchmark extends JmhSettings {

    @Param({"procwright", "jdk-process-builder", "commons-exec", "zt-exec", "nuprocess"})
    public String candidateId;

    private CandidateAdapter candidate;
    private CommandRequest successRequest;
    private CommandRequest stdinRequest;
    private CommandRequest largeStdoutRequest;
    private CommandRequest timeoutRequest;

    @Setup
    public void setup() {
        candidate = BenchmarkSupport.candidate(candidateId);
        successRequest = BenchmarkSupport.successRequest();
        stdinRequest = BenchmarkSupport.stdinRequest();
        largeStdoutRequest = BenchmarkSupport.largeStdoutRequest();
        timeoutRequest = BenchmarkSupport.timeoutRequest();
    }

    @Benchmark
    public void success(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole, candidate.run(successRequest, BenchmarkSupport.CAPTURE_LIMIT), BenchmarkSupport::success);
    }

    @Benchmark
    public void stdinEcho(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole, candidate.run(stdinRequest, BenchmarkSupport.CAPTURE_LIMIT), BenchmarkSupport::stdinEcho);
    }

    @Benchmark
    public void largeStdoutBoundedCapture(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole,
                candidate.run(largeStdoutRequest, BenchmarkSupport.CAPTURE_LIMIT),
                BenchmarkSupport::largeStdout);
    }

    @Benchmark
    public void timeout(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole, candidate.run(timeoutRequest, BenchmarkSupport.CAPTURE_LIMIT), BenchmarkSupport::timedOut);
    }
}
