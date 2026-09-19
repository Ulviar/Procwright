# Portable command construction

Pass the executable and each argument separately. The JDK process API then preserves argument boundaries without a shell.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java -->
```java
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
```

[Open `RunExample.java`](../examples/java/io/github/ulviar/procwright/examples/RunExample.java).

Resolve executable paths in the application when PATH contents are not trusted. Do not pass untrusted values through
`CommandSpec.shell`; shell escaping is platform-specific and remains the caller's responsibility.

Use `CommandSpec.of(executable).withArgs(...)` for reusable base argv. Scenario-level `withArgs(...)` appends arguments
for one workflow branch.
