package io.github.ulviar.procwright.comparison;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

@Threads(1)
@State(Scope.Thread)
public class PooledProcessBenchmark extends JmhSettings {

    private ProcwrightCandidateAdapter candidate;
    private java.util.List<String> lineReplCommand;

    @Setup
    public void setup() {
        candidate = new ProcwrightCandidateAdapter();
        lineReplCommand = BenchmarkSupport.fixtureCommand("line-repl");
    }

    @Benchmark
    public void poolLifecycleWithWarmupAndTwoRequests(Blackhole blackhole) {
        BenchmarkSupport.consume(
                blackhole,
                candidate.pooled(lineReplCommand, BenchmarkSupport.DEFAULT_TIMEOUT),
                BenchmarkSupport::lineSession);
    }
}
