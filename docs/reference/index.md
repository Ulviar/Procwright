# Reference

- [Scenario contracts](../scenarios/index.md): starting and closing processes, output ownership, concurrency, and failures.
- [Command model](command-model.md): arguments, working directories, environment, and shell commands.
- [Settings and lifecycle](policies.md): timeouts, capture, charset, readiness, callbacks, and completion futures.
- [Scenario defaults](defaults.md): exact behavior before the first `with*` call.
- [Results and errors](results-and-errors.md): result fields and stable failure reasons.
- [Timeout and close guarantees](../explanations/process-cleanup-limits.md): shutdown deadlines, output draining, and surviving descendants.
- [Output ownership](output-ownership.md): which component may read each stream.
- [Diagnostics](diagnostics.md): observe process events and inspect retained transcripts.
- [Security](security.md): untrusted arguments, output, secrets, and executable selection.
- [Platforms and PTY](platforms-and-pty.md): Java versions and terminal availability.
- [Kotlin API](kotlin-api.md): durations, coroutine calls, and Flow.
- [Generated API docs](../api/index.md): exact Java signatures and Kotlin overloads.
