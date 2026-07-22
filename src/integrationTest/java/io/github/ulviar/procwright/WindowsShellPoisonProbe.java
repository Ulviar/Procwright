/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import java.time.Duration;

/** Child-JVM fixture whose process working directory is controlled by the integration test. */
public final class WindowsShellPoisonProbe {

    private WindowsShellPoisonProbe() {}

    public static void main(String[] arguments) {
        boolean cleanEnvironment = Boolean.parseBoolean(arguments[0]);
        RunScenario.Draft draft = Procwright.command(CommandSpec.shell("echo procwright-shell-probe"))
                .run()
                .withTimeout(Duration.ofSeconds(10));
        if (cleanEnvironment) {
            draft = draft.withCleanEnvironment();
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null && !systemRoot.isBlank()) {
                draft = draft.withEnvironment("SystemRoot", systemRoot);
            }
        }

        CommandResult result = draft.execute();
        String output = result.stdout().replace("\r\n", "\n").trim();
        if (!result.succeeded() || !output.equals("procwright-shell-probe")) {
            throw new AssertionError("trusted Windows shell probe failed: " + output);
        }
    }
}
