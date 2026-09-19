/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.OutputMode;
import java.nio.file.Path;

public final class RunOptionsExample {

    private RunOptionsExample() {}

    public static CommandService configure(String executable, Path directory) {
        // docs:start command
        CommandSpec command =
                CommandSpec.of(executable).withWorkingDirectory(directory).withEnvironment("APP_MODE", "batch");
        CommandService tool = Procwright.command(command);
        // docs:end command
        return tool;
    }

    public static CommandResult textInput(CommandSpec command, String input) {
        // docs:start text-input
        CommandResult result =
                Procwright.command(command).run().withInput(input).execute();
        // docs:end text-input
        return result;
    }

    public static CommandResult files(CommandSpec command, Path input, Path stdout, Path stderr) {
        // docs:start files
        CommandResult result = Procwright.command(command)
                .run()
                .withInput(CommandInput.fromPath(input))
                .withCapture(CapturePolicy.toPath(stdout, stderr))
                .execute();
        // docs:end files
        return result;
    }

    public static CommandResult mergedFile(CommandSpec command, Path log) {
        // docs:start merged-file
        CommandResult result = Procwright.command(command)
                .run()
                .withOutput(OutputMode.MERGED)
                .withCapture(CapturePolicy.toPath(log))
                .execute();
        // docs:end merged-file
        return result;
    }
}
