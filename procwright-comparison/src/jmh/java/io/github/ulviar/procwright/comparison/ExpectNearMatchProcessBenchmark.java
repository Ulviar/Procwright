/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.comparison;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectMatch;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.testcli.TestCli;
import java.time.Duration;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

@Threads(1)
@State(Scope.Thread)
public class ExpectNearMatchProcessBenchmark extends JmhSettings {

    private static final Duration MATCH_TIMEOUT = Duration.ofSeconds(5);
    private static final int MATCH_BUFFER_LIMIT = 64 * 1024;
    private static final String NEAR_MATCH = "a".repeat(63) + "b";

    private Session session;
    private Expect expect;

    @Setup(Level.Trial)
    public void openWorker() {
        List<String> command = BenchmarkSupport.javaCommand(
                TestCli.class, "expect-near-match-repl", "--chunk-count=256", "--chunk-bytes=2048", "--delay-millis=1");
        CommandSpec commandSpec = CommandSpec.of(command.get(0)).withArgs(command.subList(1, command.size()));
        Session openedSession = Procwright.command(commandSpec)
                .interactive()
                .withIdleTimeout(Duration.ofSeconds(30))
                .open();
        try {
            Expect openedExpect = openedSession
                    .expect()
                    .withTimeout(MATCH_TIMEOUT)
                    .withTranscriptLimit(4 * 1024)
                    .withMatchBufferLimit(MATCH_BUFFER_LIMIT)
                    .open();
            openedExpect.expectText("ready> ", MATCH_TIMEOUT);
            session = openedSession;
            expect = openedExpect;
        } catch (RuntimeException | Error failure) {
            openedSession.close();
            throw failure;
        }
    }

    @Benchmark
    public int pacedNearMatchRound() {
        expect.sendLine("next");
        ExpectMatch match = expect.expectTextMatch(NEAR_MATCH, MATCH_TIMEOUT);
        return match.before().length() + match.matched().length();
    }

    @TearDown(Level.Trial)
    public void closeWorker() {
        if (expect != null) {
            expect.close();
        }
        if (session != null) {
            session.close();
        }
    }
}
