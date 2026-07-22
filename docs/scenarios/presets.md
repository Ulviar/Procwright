# Scenario presets

`ScenarioPresets` contains small functions that transform one immutable Draft into another. A preset does not start a
process and can be applied to any compatible Draft branch.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/PresetExample.java -->
```java
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
```

[Open `PresetExample.java`](../examples/java/io/github/ulviar/procwright/examples/PresetExample.java) and the
[shared example sources](../examples.md#core).

Use a preset only when its documented policy matches the application. Apply additional `with*` calls after it to create a
new branch; the original Draft remains unchanged.
