# Output Ownership Rationale

iCLI treats process output as a single-owner resource because mixing readers is a common source of deadlocks, lost bytes,
misordered diagnostics, and inconsistent transcripts.

A finite command, a streaming log follower, a prompt automator, and a request/response worker need different output
rules. `run` must drain both streams until completion. `listen` must deliver chunks without retaining all output.
`Expect`, `lineSession`, and `protocolSession` must claim output so their matchers or decoders see a coherent byte
sequence.

The practical rule is simple: choose the workflow that owns output for this process, then let that workflow read it.
Do not add extra pump threads around `run`, and do not read raw `Session.stdout()` after handing the session to a helper.

The contract table lives in [Output Ownership](../reference/output-ownership.md).
