# Security Policy

## Reporting a vulnerability

Report privately via GitHub: open the **Security** tab on
[`Ulviar/Procwright`](https://github.com/Ulviar/Procwright/security/advisories/new) and choose **Report a
vulnerability**. Do not open public issues for vulnerabilities.

You can expect an acknowledgment within a few days. Fixes are coordinated with the reporter before public disclosure.

## Supported versions

The latest `0.x` release line receives security fixes.

## Scope

Procwright runs external processes, and the caller always owns command selection and input trust. The security model is
documented in [docs/reference/security.md](docs/reference/security.md).

We treat as vulnerabilities:

- command injection through library APIs used as documented, e.g. direct argv arguments leaking into a shell;
- violations of invariants the library owns: process-tree cleanup, clean environment, bounded output and transcripts;
- secret leakage through diagnostics or transcripts that the documentation promises to redact, e.g. environment values
  in diagnostic command echoes or unredacted `Expect` transcript values.

Not vulnerabilities:

- executing untrusted commands or building shell command lines from untrusted input — running arbitrary CLIs is
  inherently dangerous;
- documented behavior of `Procwright.shellCommand(...)` / `CommandSpec.shell(...)`, which explicitly hand a command
  string to the operating-system shell.
