/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.preset.ScenarioPresets;
import java.time.Duration;

public final class PresetExample {

    private PresetExample() {}

    public static void main(String[] args) {
        var base = Procwright.command(ExampleSupport.workerCommand("finite")).run();
        var diagnostics = ScenarioPresets.environmentDiagnostics(base, Duration.ofSeconds(5), 16 * 1024);

        CommandResult result = diagnostics.execute();
        if (!result.succeeded()) {
            throw result.toException();
        }
    }
}
