# Platforms and PTY

iCLI core is built on JDK process APIs and keeps platform-specific terminal behavior behind a narrow capability
boundary.

## Java baseline

iCLI keeps one source tree for Java 17, 21, and 25 release variants. The default local target is Java 25; set
`--project-prop=icli.javaRelease=17`, `21`, or `25` to build a specific variant with the matching JDK.

On Java 21 and newer runtimes, iCLI may use virtual threads behind an internal runtime boundary. The Java 17 variant
uses daemon platform-thread fallback. The public API does not expose or require a specific threading implementation.

## Ordinary process execution

The core one-shot and session scenarios use ordinary process pipes by default. POSIX-only and PTY-only tests are
expected to skip through assumptions when the platform capability is unavailable.

## PTY boundary

Terminal capability is requested through `TerminalPolicy` inside session-family scenarios. The public API exposes
terminal policy, requested terminal size, terminal control signals, and `PtyProvider`.

Backend-specific PTY library types must not appear in core public signatures.

The current baseline does not ship a Windows ConPTY provider. ConPTY support should be added as a separate optional
provider or runtime-specific artifact without changing the scenario API or adding native dependencies to the core
module.

## Required terminal mode

Use `TerminalPolicy.REQUIRED` when terminal behavior is mandatory. This makes unavailable terminal support visible
instead of silently falling back to ordinary pipes.
