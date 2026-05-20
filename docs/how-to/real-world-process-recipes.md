# Real-world Process Recipes

These recipes map common open-source process patterns to iCLI scenarios. They are intentionally scenario-shaped rather
than library-shaped: the goal is to remove hand-written process harnesses without turning iCLI into a bag of flags.

## Version or availability probes

Use `run` for commands such as `git --version`, `docker info`, `java --version`, or a tool-specific `doctor` command.

Keep executable discovery in the application layer for now. iCLI launches commands; it does not currently own PATH
lookup, Windows extension probing, package-manager installation, or toolchain discovery.

Recommended shape:

- create a `CommandService` for the executable or resolved path;
- set a short timeout;
- use bounded capture;
- convert unsuccessful results with `CommandResult.toException()` when fail-fast flow is wanted.

See [Run a finite command](run-finite-command.md) and [`run`](../scenarios/run.md).

## Release or build automation command

Use `run` when a release step must complete before the next step starts. This matches common wrappers around Git, Maven,
Gradle, Docker, code generators, and validators.

Use stable command defaults for working directory and environment. Put per-step arguments, timeout, capture, and
shutdown policy in the scenario invocation.

See [Stop hung processes](stop-hung-processes.md), [Command model](../reference/command-model.md), and
[Policies](../reference/policies.md).

## Long-running log follower

Use `listen` when the process is a stream source: `tail -f`, `kubectl logs -f`, a local server log, or a watcher.

The listener should be bounded and fast. Slow listeners create backpressure on the process pipe instead of unbounded
memory growth. If the caller needs to stop watching, close the `StreamSession`.

See [Follow logs](follow-logs.md) and [Streaming](../scenarios/streaming.md).

## Local daemon startup with readiness check

Use `listen` when readiness is an external observation such as HTTP polling or a known log line. Use `interactive`,
`lineSession`, or `protocolSession` readiness probes when readiness can be checked through the worker protocol itself.

Typical readiness checks are HTTP polling, socket availability, a PID file, a status command, or a known log line. iCLI
should own stream draining, timeout, shutdown, and diagnostics. The application still owns the domain-specific definition
of "ready"; iCLI only owns when the probe runs and how the process is closed on readiness failure.

Do not turn this into a raw background `Process` unless the caller truly wants to own every stream and shutdown detail.

## Prompt-driven installer or configurator

Use `interactive` plus `Expect` when a CLI asks questions and emits prompts.

Keep terminal requirements explicit. Some tools work over ordinary pipes; others require terminal capability and should
be launched with [`TerminalPolicy.REQUIRED`](../scenarios/terminal.md).

See [Automate prompts](automate-prompts.md), [Require a terminal](require-terminal.md), and
[Expect Automation](../scenarios/expect.md).

## Line-oriented worker

Use `lineSession` when the process behaves like a request/response worker where one request produces one logical
response. Use `pooled` when worker startup is expensive and the protocol can be reset or checked safely between
requests.

See [Talk to a line worker](talk-to-line-worker.md), [Reuse workers](reuse-workers.md), and
[Line Sessions](../scenarios/line-session.md).

## Framed or typed protocol worker

Use `protocolSession` when requests or responses are multi-line, byte-oriented, content-length framed, delimiter-framed,
or mapped to domain types. Use `pooledProtocol` when startup is expensive and reset/health semantics are clear.

See [Protocol Sessions](../scenarios/protocol-session.md), [Reuse workers](reuse-workers.md), and
[Integrations](../scenarios/integrations.md).

## JSON Lines or Content-Length tool adapter

Use the optional integrations module when a CLI should be treated as a structured adapter rather than raw process text.

The adapter layer still builds on core scenarios. It should validate output as untrusted data and keep cancellation,
diagnostics, and protocol bounds explicit.

See [Wrap a CLI tool](wrap-cli-tool.md) and [Integrations](../scenarios/integrations.md).

## Tee output to logs and keep diagnostics

First decide which invariant matters more:

- use `run` when the completed result is the primary artifact;
- use `listen` when real-time output consumption is the primary artifact;
- use diagnostics when lifecycle observability is needed without exposing raw output.

The current public API does not provide a single "run and tee all output while also returning a full captured result"
shortcut. That is intentional for the current baseline: output ownership and memory bounds must stay visible.
