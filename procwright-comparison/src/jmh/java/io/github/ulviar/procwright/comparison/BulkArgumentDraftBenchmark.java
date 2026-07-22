/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.InteractiveScenario;
import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.ProtocolSessionScenario;
import io.github.ulviar.procwright.RunScenario;
import io.github.ulviar.procwright.StreamScenario;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.util.List;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

@Threads(1)
@State(Scope.Thread)
public class BulkArgumentDraftBenchmark extends JmhSettings {

    @Param({"1000", "10000", "20000"})
    public int argumentCount;

    private List<String> arguments;
    private CommandSpec commandSpec;
    private RunScenario.Draft runDraft;
    private InteractiveScenario.Draft interactiveDraft;
    private LineSessionScenario.Draft lineDraft;
    private StreamScenario.Draft streamDraft;
    private ProtocolSessionScenario.Draft<String, String> protocolDraft;

    @Setup
    public void setup() {
        arguments = IntStream.range(0, argumentCount)
                .mapToObj(index -> "argument-" + index)
                .toList();
        commandSpec = CommandSpec.of("tool");
        CommandService service = Procwright.command(commandSpec);
        runDraft = service.run();
        interactiveDraft = service.interactive();
        lineDraft = service.lineSession();
        streamDraft = service.listen();
        protocolDraft = service.protocolSession(BenchmarkProtocolAdapter::new);
    }

    @Benchmark
    public CommandSpec commandSpec() {
        return commandSpec.withArgs(arguments);
    }

    @Benchmark
    public RunScenario.Draft runDraft() {
        return runDraft.withArgs(arguments);
    }

    @Benchmark
    public InteractiveScenario.Draft interactiveDraft() {
        return interactiveDraft.withArgs(arguments);
    }

    @Benchmark
    public LineSessionScenario.Draft lineDraft() {
        return lineDraft.withArgs(arguments);
    }

    @Benchmark
    public StreamScenario.Draft streamDraft() {
        return streamDraft.withArgs(arguments);
    }

    @Benchmark
    public ProtocolSessionScenario.Draft<String, String> protocolDraft() {
        return protocolDraft.withArgs(arguments);
    }

    private static final class BenchmarkProtocolAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(1024);
        }
    }
}
