# Security Policy

## Reporting a vulnerability

Report privately via GitHub: open the **Security** tab on
[`Ulviar/Procwright`](https://github.com/Ulviar/Procwright/security/advisories/new) and choose **Report a
vulnerability**. Do not open public issues for vulnerabilities.

The report will be reviewed privately before any public disclosure.

## Supported versions

There is no supported release yet. This section will name supported versions when the first public release is
published.

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
- documented behavior of `Procwright.command(CommandSpec.shell(...))`, which explicitly hands a command string to the
  operating-system shell.
