# Why Scenarios Instead of Flags

Procwright is not a thin wrapper over `ProcessBuilder`. The public API starts with the workflow a caller is trying to run:

- finite command execution;
- live interactive process control;
- prompt automation;
- line-oriented protocols;
- framed or typed protocols;
- streaming output;
- reusable workers;
- optional JSON and byte-framing protocol adapters.

Each workflow needs different behavior. A timeout in a one-shot command, a request timeout in a line worker, and an idle
timeout in an interactive session are not the same concept. Procwright keeps those settings next to the workflow that uses
them.

## What this means for users

Instead of building a large process helper with many optional flags, choose the scenario first:

- use `run` when the process should finish and return a result;
- use `listen` when output is an event stream;
- use `interactive` when the caller needs raw process control;
- add `Expect` when prompt matching owns the session output;
- use `lineSession` or `protocolSession` when a long-lived worker speaks a request/response protocol;
- add pooling only when the worker protocol can be reused.

This keeps invalid combinations visible. For example, terminal controls belong to session-family workflows, while full
captured stdout/stderr belongs to `run`.
