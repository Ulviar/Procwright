package io.github.ulviar.icli.comparison;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

@Threads(1)
@State(Scope.Thread)
public class ToolObservationBenchmark extends JmhSettings {

    private IcliCandidateAdapter candidate;
    private java.util.List<String> successCommand;
    private java.util.List<String> failureCommand;

    @Setup
    public void setup() {
        candidate = new IcliCandidateAdapter();
        successCommand = BenchmarkSupport.fixtureCommand("success");
        failureCommand = BenchmarkSupport.fixtureCommand("non-zero");
    }

    @Benchmark
    public void commandBackedToolSuccessAndFailure(Blackhole blackhole) {
        BenchmarkSupport.consume(
                blackhole,
                candidate.structuredToolObservation(successCommand, failureCommand),
                outcome -> outcome.status() == OutcomeStatus.PASS);
    }
}
