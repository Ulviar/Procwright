/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.consumer.examples;

import io.github.ulviar.procwright.examples.AnsiExpectExample;
import io.github.ulviar.procwright.examples.DiagnosticsExample;
import io.github.ulviar.procwright.examples.ExpectExample;
import io.github.ulviar.procwright.examples.InteractiveExample;
import io.github.ulviar.procwright.examples.LinePoolExample;
import io.github.ulviar.procwright.examples.LineSessionExample;
import io.github.ulviar.procwright.examples.ListenExample;
import io.github.ulviar.procwright.examples.ProtocolPoolExample;
import io.github.ulviar.procwright.examples.ProtocolSessionExample;
import io.github.ulviar.procwright.examples.ReadinessExample;
import io.github.ulviar.procwright.examples.RunExample;
import io.github.ulviar.procwright.examples.StopHungCommandExample;
import io.github.ulviar.procwright.examples.TerminalExample;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class CanonicalExamplesTest {

    @Test
    void runExampleExecutes() {
        RunExample.main(new String[0]);
    }

    @Test
    void stopHungCommandExampleExecutes() {
        StopHungCommandExample.main(new String[0]);
    }

    @Test
    void interactiveExampleExecutes() throws Exception {
        InteractiveExample.main(new String[0]);
    }

    @Test
    void expectExampleExecutes() {
        ExpectExample.main(new String[0]);
    }

    @Test
    void ansiExpectExampleExecutes() {
        AnsiExpectExample.main(new String[0]);
    }

    @Test
    void readinessExampleRunsProbeBeforeReturningSession() {
        ReadinessExample.main(new String[0]);
    }

    @Test
    void listenExampleExecutesAndStopsAtItsDeadline() {
        ListenExample.main(new String[0]);
    }

    @Test
    void lineSessionExampleExecutes() {
        LineSessionExample.main(new String[0]);
    }

    @Test
    void protocolSessionExampleExecutes() {
        ProtocolSessionExample.main(new String[0]);
    }

    @Test
    void linePoolExampleExecutes() {
        LinePoolExample.main(new String[0]);
    }

    @Test
    void protocolPoolExampleExecutes() {
        ProtocolPoolExample.main(new String[0]);
    }

    @Test
    void diagnosticsExampleWaitsForAsynchronousDelivery() throws Exception {
        DiagnosticsExample.main(new String[0]);
    }

    @Test
    void terminalExampleExecutesWhenSystemPtyCapabilityIsPresent() {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Assumptions.assumeTrue(
                Files.isExecutable(Path.of("/usr/bin/script")) || Files.isExecutable(Path.of("/bin/script")));

        TerminalExample.main(new String[0]);
    }
}
