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
public class ExpectProcessBenchmark extends JmhSettings {

    @Param({"icli", "jdk-process-builder", "expectit"})
    public String candidateId;

    private CandidateAdapter candidate;
    private CommandRequest promptRequest;

    @Setup
    public void setup() {
        candidate = BenchmarkSupport.candidate(candidateId);
        promptRequest = BenchmarkSupport.promptRequest();
    }

    @Benchmark
    public void promptAutomation(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole,
                candidate.expectPrompt(promptRequest, BenchmarkSupport.DEFAULT_TIMEOUT),
                BenchmarkSupport::expectPrompt);
    }
}
