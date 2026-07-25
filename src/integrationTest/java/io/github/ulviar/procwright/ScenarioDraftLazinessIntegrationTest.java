/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScenarioDraftLazinessIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void configurationAndPoolingDoNotLaunchBeforeATerminalOperation() {
        Path missingExecutable = temporaryDirectory.resolve("missing-executable");
        AtomicInteger diagnosticEvents = new AtomicInteger();
        AtomicInteger adapters = new AtomicInteger();
        CommandService service = Procwright.command(missingExecutable.toString());

        service.run()
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused");
        service.interactive()
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused");
        service.lineSession()
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused");
        service.lineSession()
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused")
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1);
        service.listen()
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused");
        service.protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new FramedStringAdapter();
                })
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused");
        service.protocolSession(() -> {
                    adapters.incrementAndGet();
                    return new FramedStringAdapter();
                })
                .withDiagnosticListener(event -> diagnosticEvents.incrementAndGet())
                .withArg("unused")
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1);

        assertEquals(0, diagnosticEvents.get());
        assertEquals(0, adapters.get());
    }
}
