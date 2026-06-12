/* SPDX-License-Identifier: Apache-2.0 */

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
public class StreamingProcessBenchmark extends JmhSettings {

    @Param({"procwright", "jdk-process-builder", "commons-exec", "zt-exec", "nuprocess"})
    public String candidateId;

    private CandidateAdapter candidate;
    private CommandRequest streamRequest;

    @Setup
    public void setup() {
        candidate = BenchmarkSupport.candidate(candidateId);
        streamRequest = BenchmarkSupport.streamRequest();
    }

    @Benchmark
    public void stdoutAndStderrCallbacks(Blackhole blackhole) throws Exception {
        BenchmarkSupport.consume(
                blackhole, candidate.stream(streamRequest, BenchmarkSupport.CAPTURE_LIMIT), BenchmarkSupport::stream);
    }
}
