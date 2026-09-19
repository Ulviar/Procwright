/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandResult;
import java.nio.file.Path;
import java.util.Arrays;

public final class RunExample {

    private RunExample() {}

    public static void main(String[] args) {
        String executable = args.length == 0 ? javaExecutable() : args[0];
        String[] arguments = args.length == 0 ? new String[] {"--version"} : Arrays.copyOfRange(args, 1, args.length);

        // docs:start run
        CommandResult result =
                Procwright.command(executable).run().withArgs(arguments).execute();
        // docs:end run

        System.out.print(result.stdout());
        System.err.print(result.stderr());
        System.out.println("succeeded=" + result.succeeded());

        // docs:start failure
        if (!result.succeeded()) {
            throw result.toException();
        }
        // docs:end failure
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
