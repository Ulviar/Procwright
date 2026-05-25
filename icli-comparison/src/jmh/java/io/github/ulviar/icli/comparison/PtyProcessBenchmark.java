package io.github.ulviar.icli.comparison;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

@Threads(1)
@State(Scope.Thread)
public class PtyProcessBenchmark extends JmhSettings {

    @Param({"icli", "pty4j"})
    public String candidateId;

    private CandidateAdapter candidate;

    @Setup
    public void setup() {
        candidate = BenchmarkSupport.candidate(candidateId);
    }

    @Benchmark
    public void terminalProbe(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole, candidate.ptyProbe(BenchmarkSupport.DEFAULT_TIMEOUT), BenchmarkSupport::pty);
    }
}
