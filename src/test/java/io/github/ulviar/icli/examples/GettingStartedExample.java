package io.github.ulviar.icli.examples;

import io.github.ulviar.icli.CommandService;
import io.github.ulviar.icli.Icli;
import io.github.ulviar.icli.command.CommandResult;

public final class GettingStartedExample {

    private GettingStartedExample() {}

    public static void main(String[] args) {
        CommandService java = Icli.command("java");

        CommandResult result = java.run().execute("--version");

        if (!result.succeeded()) {
            throw result.toException();
        }

        System.out.print(result.stdout());
        System.err.print(result.stderr());
    }
}
