# Terminal sessions

Add `withTerminal(TerminalPolicy.REQUIRED)` to an interactive, Expect, line, or protocol draft when the CLI needs terminal
behavior. Start with [Require a terminal](../how-to/require-terminal.md) for the complete example.

| Policy | Behavior |
| --- | --- |
| `DISABLED` (default) | Use ordinary process pipes. |
| `AUTO` | Request a terminal; allow ordinary pipes if the provider is unavailable. |
| `REQUIRED` | Fail if the configured provider cannot supply a terminal. |

`run()` and `listen()` use pipes and do not accept a terminal policy.

## Control keys

`sendSignal(...)` writes a terminal control byte. A PTY normally translates that byte into an operating-system signal
for its foreground command; an ordinary pipe receives only the byte. Use `REQUIRED` when your interaction depends on
that terminal behavior.

## Platform requirements

The built-in provider supports compatible macOS and Linux systems. It requires executable `script`, `stty`, `env`, and
`dd` in `/usr/bin` or `/bin`, plus executable `/bin/sh`. It checks that those tools support terminal startup and child
exit-code reporting. Unsupported tool variants make the provider unavailable. Windows ConPTY support is not included.

The system provider rejects executable names containing `=` because its launcher cannot distinguish them from
environment assignments. Ordinary pipe transport has no such restriction.

For custom providers and platform details, see [Platforms and terminal support](../reference/platforms-and-pty.md).
See [session defaults](../reference/defaults.md#interactive-sessions) for the initial provider and terminal size.
