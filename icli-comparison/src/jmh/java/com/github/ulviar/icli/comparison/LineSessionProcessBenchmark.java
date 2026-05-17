package com.github.ulviar.icli.comparison;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

@Threads(1)
@State(Scope.Thread)
public class LineSessionProcessBenchmark extends JmhSettings {

    @Param({"icli", "jdk-process-builder"})
    public String candidateId;

    private CandidateAdapter candidate;
    private CommandRequest lineReplRequest;

    @Setup
    public void setup() {
        candidate = BenchmarkSupport.candidate(candidateId);
        lineReplRequest = BenchmarkSupport.lineReplRequest();
    }

    @Benchmark
    public void twoRequests(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole,
                candidate.lineSession(lineReplRequest, BenchmarkSupport.DEFAULT_TIMEOUT),
                BenchmarkSupport::lineSession);
    }
}
