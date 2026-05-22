# Portable Command Construction

iCLI keeps command construction explicit. Portability is not achieved by hiding platform differences behind shell
strings; it is achieved by choosing direct argv by default and making platform boundaries visible.

## Direct argv is the default

Use `Icli.command("tool")`, `CommandSpec.of("tool")`, or `CommandSpec.builder("tool")` for direct
execution. Add arguments as separate values with `args(...)`.

Direct argv avoids shell quoting rules and keeps user-provided values from becoming shell syntax.

## Shell is explicit

Use `Icli.shellCommand(...)` or `CommandSpec.shell(...)` only when shell syntax is the actual requirement:
pipelines, redirects, shell built-ins, command substitution, or platform scripts that must be interpreted by a shell.

Do not build shell command lines by concatenating untrusted input. Prefer direct argv and pass untrusted values as
arguments.

## Platform-specific executable selection

The application owns platform-specific executable selection:

- choose `.cmd` or `.bat` on Windows when the tool only ships as a Windows script;
- choose POSIX scripts on Unix-like systems when the tool expects POSIX shell behavior;
- use absolute paths when the surrounding application already resolved a toolchain;
- keep PATH probing, package-manager lookup, and installation outside the current iCLI core.

iCLI validates and launches the command it is given. It does not currently implement a command discovery service.

## Working directory and environment

Use `CommandSpec` for stable working directory and environment defaults. Use scenario methods for operation-specific
overrides.

The default environment policy inherits the current process environment and applies explicit overrides. Use
`cleanEnvironment()` when reproducibility or isolation matters. On Windows, a very small clean environment may need
system variables required by the target executable.

## Newlines and terminal behavior

Do not assume one newline convention across all tools. Normalize output in the application when the domain allows it.

Do not assume that an interactive command can run over ordinary pipes. If a CLI needs terminal behavior, use a
session-family scenario with [`TerminalPolicy.REQUIRED`](../scenarios/terminal.md).

## Related references

- [Command model](command-model.md)
- [Policies](policies.md)
- [Security](security.md)
- [Platforms and PTY](platforms-and-pty.md)
