# Platforms and terminal support

Published artifacts target Java 17 and run on Java 17 or newer. Java 21 and newer may use virtual threads internally;
Java 17 uses daemon platform threads. This does not change public API behavior, but concurrency performance can differ.

Ordinary direct-process scenarios use the JDK process API on macOS, Linux, and Windows. Shell commands remain platform
specific.

Terminal sessions depend on the configured `PtyProvider`:

- `TerminalPolicy.DISABLED` uses ordinary pipes.
- `AUTO` uses a terminal when the provider is available and otherwise uses ordinary pipes.
- `REQUIRED` fails when terminal capability is unavailable.

On Unix, the built-in system provider requires executable `script`, `stty`, `env`, and `dd` commands in `/usr/bin` or
`/bin`, plus executable `/bin/sh`. It never searches `PATH` for transport helpers. A bounded startup probe verifies the
exact absolute helpers, BSD or util-linux `script` invocation, terminal allocation, `env -i --` behavior, and child
exit-code propagation. An unknown implementation, including BusyBox variants without util-linux `-e`, is unavailable:
`AUTO` uses pipes and `REQUIRED` fails before returning a session.

The provider starts its transport wrapper with a fixed minimal environment. It transfers the resolved child environment
and direct argv through the existing terminal input only after terminal echo is disabled. The wrapper reports `READY`,
consumes one count- and byte-bounded frame, applies and verifies the requested terminal size, and reports `STARTED` before
the input belongs to the target. No bootstrap or control file is created. Preparation, process start, and both handshake
phases share one monotonic bounded admission deadline.

The final launch is a direct `env -i -- NAME=value ... executable argv ...` through the probed absolute `env`; there is no
target shell or macOS `arch` trampoline. Values and arguments remain positional data. A leading `-` executable token is
supported only because availability requires the probed `--` separator. Because portable `env` operand syntax cannot
distinguish an executable token containing `=`, the system provider rejects that rare executable name with a generic
launch failure; ordinary pipe transport has no such restriction.

The planned `0.1.0` release does not ship Windows ConPTY support. Terminal resize and signal behavior depend on the
selected provider and operating system.
