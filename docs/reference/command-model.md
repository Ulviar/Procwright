# Commands, arguments, and environment

Pass the executable and arguments separately. `Procwright.command("git")` selects a program;
`withArgs("status", "--short")` supplies two arguments. Do not add shell quotes around an argument containing spaces:
those quotes would become part of the argument.

## Share launch settings

Use `CommandSpec` when several calls share a working directory, environment, or base arguments.
Here, `executable` is your program and `directory` is its working directory:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java#command -->
```java
CommandSpec command =
        CommandSpec.of(executable).withWorkingDirectory(directory).withEnvironment("APP_MODE", "batch");
CommandService tool = Procwright.command(command);
```

Then choose a scenario on `tool`, such as `tool.run().withArgs(arguments).execute()`.
[Complete source and imports](../examples/java/io/github/ulviar/procwright/examples/RunOptionsExample.java).

| Setting | Effect |
| --- | --- |
| `withArg(...)` / `withArgs(...)` | Append arguments; scenario arguments follow the command's base arguments. |
| `withWorkingDirectory(path)` | Set the child's working directory. |
| `withEnvironment(name, value)` | Add or replace one child environment entry. |
| `withCleanEnvironment()` | Start without the parent's environment, keeping explicitly configured entries. |
| `withInheritedEnvironment()` | Inherit the parent's environment and apply explicitly configured entries; this is the default. |

`CommandSpec` and scenario Drafts are immutable. Keep the object returned by each `with*` call. You can reuse a configured
Draft: each `execute()` or `open()` starts an independent process. No configuration call launches a process.
Shared callbacks have their own [thread-safety requirements](policies.md#callback-concurrency-and-lifetime).

## Use a shell deliberately

Ordinary arguments do not expand `*`, `$VARIABLE`, pipes, or redirections. For shell syntax, use
`Procwright.command(CommandSpec.shell(commandLine))`. Shell commands cannot also accept `withArgs(...)`:
put the complete shell expression in `commandLine`.

Shell syntax and escaping depend on the operating system. Never interpolate untrusted input without validating and
escaping it for that shell. Prefer direct arguments when the task does not need a shell. Use an absolute executable
path when you cannot trust `PATH`.
