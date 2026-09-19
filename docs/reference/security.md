# Security

Procwright controls local child processes; it is not a sandbox.

## Commands and arguments

Prefer direct argv through `Procwright.command(executable)` or `CommandSpec.of(executable)`. Resolve executable paths when
PATH can be influenced by an untrusted user. Use `CommandSpec.shell` only for intentional shell syntax, and never insert
untrusted text without platform-correct validation and escaping.

On Windows, shell mode resolves `cmd.exe` only from the canonical absolute `SystemRoot\\System32` path and fails before
launch when that interpreter is unavailable or unusable. Child `PATH` and the working directory do not select the shell.

## Environment and working directory

Inherited environment can expose credentials or alter executable and library lookup. Use a clean environment and explicit
entries for hostile or reproducible workloads. Validate working directories and files before passing them to a child.

The built-in Unix terminal provider keeps child arguments and environment values separate from its helper commands.
A custom provider must preserve those boundaries. See [platform and provider requirements](platforms-and-pty.md).

## Output and diagnostics

Treat stdout, stderr, protocol frames, and exit metadata as untrusted input. Bound capture, request, response, and
transcript sizes; unread output buffers follow the response limits. Use strict charset decoding when replacement
characters would hide corruption.

Built-in diagnostic events omit argument values, environment values, and raw stdin/stdout/stderr. Their command metadata
still includes the executable, working directory, and environment variable names. Scenario transcripts can contain
process output and request data. Review and redact transcripts and exceptions before exporting them; truncation alone
does not remove secrets. See [diagnostics](diagnostics.md).

Use different paths for file input and output: opening an output redirect overwrites existing content.

For separate file capture, Procwright checks immediately before launch that stdout and stderr do not resolve to the same
file, including aliases reached through symlinked directories. It fails closed when filesystem identity cannot be read.
Two existing files are compared by their filesystem identity, so distinct files may have names such as `Capture.log`
and `capture.log`. If either target does not exist, names that differ only by case, canonical Unicode representation,
or trailing dots and spaces are conservatively rejected. Do not replace or relink capture paths concurrently with launch:
`ProcessBuilder` cannot atomically verify two path identities and open both redirects. Protect attacker-controlled output directories with
operating-system permissions.

## Lifecycle boundary

Timeout and close apply best-effort cleanup to the process tree visible through JDK `ProcessHandle`. Detached or inaccessible
descendants can survive. Use an OS sandbox, container, job object, service manager, or equivalent containment when the child
is untrusted.

User callbacks for readiness, protocol adapters, pool hooks, and diagnostics run in the application process. Keep them
bounded, interruption-aware, and free of untrusted blocking calls.
