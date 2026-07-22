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

The built-in Unix terminal provider does not expose the requested child environment to `script`, `/bin/sh`, `stty`, or
its bounded bootstrap reader. Those absolute transport helpers run with a fixed minimal environment. After terminal echo
is disabled, Java sends one bounded binary frame over the already-open terminal input; no pathname, temporary file, or
control file carries child data. The wrapper consumes the complete frame before reporting `STARTED` and leaves the same
input open for the target.

The complete child environment is applied only by the final probed `env -i --` direct-argv launch. There is no
child-environment shell or `arch` trampoline, so shell-control and dynamic-loader variables intended for the child cannot
change the trusted wrapper. The system provider fails closed for an executable token containing `=` and redacts that token
from the launch error. A custom `PtyProvider` is responsible for preserving equivalent boundaries.

## Output and diagnostics

Treat stdout, stderr, protocol frames, and exit metadata as untrusted input. Bound capture, request, response, backlog, and
transcript sizes. Use strict charset decoding when replacement characters would hide corruption.

Diagnostics and transcripts can contain arguments, environment values, request bodies, output, paths, and exception
messages. Redact before exporting. Truncation limits memory; it does not remove secrets.

For separate file capture, Procwright checks immediately before launch that stdout and stderr do not resolve to the same
file, including aliases reached through symlinked directories. It fails closed when filesystem identity cannot be read.
Any two target names that differ only by case, canonical Unicode representation, or trailing dots and spaces are rejected
on every OS, even when both files exist and the local filesystem treats them as distinct. Supported filesystems disagree
about those identities. Do not replace or relink capture paths concurrently with launch: `ProcessBuilder` cannot
atomically verify two path identities and open both redirects. Protect attacker-controlled output directories with
operating-system permissions.

## Lifecycle boundary

Timeout and close apply best-effort cleanup to the process tree visible through JDK `ProcessHandle`. Detached or inaccessible
descendants can survive. Use an OS sandbox, container, job object, service manager, or equivalent containment when the child
is untrusted.

User callbacks for readiness, protocol adapters, pool hooks, and diagnostics run in the application process. Keep them
bounded, interruption-aware, and free of untrusted blocking calls.
