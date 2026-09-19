# Platforms and terminal support

Procwright requires Java 25. Ordinary process execution uses JDK APIs on macOS, Linux, and Windows. Shell syntax remains
platform-specific.

## Terminal policies

Terminal settings are available on interactive, Expect, line, and protocol sessions, including pooled workers.
`run()` and `listen()` use ordinary pipes.

| Policy | Behavior |
| --- | --- |
| `DISABLED` | Use ordinary pipes; this is the default. |
| `AUTO` | Use a terminal when the provider is available, otherwise use pipes. |
| `REQUIRED` | Fail to open when terminal support is unavailable. |

Choose `REQUIRED` when the CLI cannot work correctly through pipes. A terminal can change buffering, prompts, colors,
and output layout. See [run a terminal-dependent command](../how-to/require-terminal.md).

## Built-in provider

The Unix provider requires executable `script`, `stty`, `env`, and `dd` in `/usr/bin` or `/bin`, plus `/bin/sh`.
It does not search `PATH` for these helpers. A startup probe checks terminal allocation, supported BSD or util-linux
`script` behavior, and propagation of the child's exit code. Unsupported helper implementations make the provider
unavailable; `AUTO` falls back to pipes and `REQUIRED` fails.

The provider keeps child arguments and environment values separate from shell syntax and applies the requested terminal
size before starting the child. Its capability detection and startup are bounded. An executable name containing `=` is
not supported by this provider; ordinary pipe execution has no such restriction.

The built-in provider does not support Windows ConPTY. Terminal resize and signal behavior depend on the provider and OS.

## Custom providers

A custom `PtyProvider` is a trusted extension. `available()`, `description()`, `start(...)`, and methods on its returned
`Process` and `ProcessHandle` do not have individual timeout isolation. Metadata and signal operations must return
promptly; timed waits must honor their timeout. Blocking implementations can delay operations and cleanup beyond
configured deadlines.

For the limits of process-tree cleanup, see [timeout and close guarantees](../explanations/process-cleanup-limits.md).
