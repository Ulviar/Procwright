/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandResult;

public final class GettingStartedExample {

    private GettingStartedExample() {}

    public static void main(String[] args) {
        CommandService java = Procwright.command("java");

        CommandResult result = java.run().withArg("--version").execute();

        if (!result.succeeded()) {
            throw result.toException();
        }

        System.out.print(result.stdout());
        System.err.print(result.stderr());
    }
}
