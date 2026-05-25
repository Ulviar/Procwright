# Scenario Contracts

This is the reference index for iCLI scenario contracts. Use the how-to guides when choosing a workflow for a concrete
task, and the explanation pages for the rationale behind the scenario-first model.

| Contract | Entry point | What iCLI handles | Result or failure surface | User guide |
| --- | --- | --- | --- | --- |
| [Run](run.md) | `Icli.command(...).run()` | finite process completion, bounded capture, timeout, shutdown | `CommandResult`, `CommandExecutionException` | [Run a finite command](../how-to/run-finite-command.md) |
| [Interactive sessions](interactive.md) | `Icli.command(...).interactive()` | raw live process lifecycle and output ownership | `Session`, `SessionExit` | [Choose a process scenario](../how-to/choose-process-scenario.md#prompt-driven-installer-or-configurator) |
| [Expect automation](expect.md) | `Session.expect(...)` | prompt matching over a claimed session output stream | `ExpectException`, bounded transcript | [Automate prompts](../how-to/automate-prompts.md) |
| [Line sessions](line-session.md) | `Icli.command(...).lineSession()` | serialized line request/response cycles | `LineResponse`, `LineSessionException` | [Talk to a line worker](../how-to/talk-to-line-worker.md) |
| [Protocol sessions](protocol-session.md) | `Icli.command(...).protocolSession(...)` | adapter-owned framed or typed request/response cycles | typed response, `ProtocolSessionException` | [Choose a process scenario](../how-to/choose-process-scenario.md#framed-or-typed-protocol-worker) |
| [Streaming](streaming.md) | `Icli.command(...).listen()` | output callback delivery without retaining full output | `StreamSession`, `StreamExit`, `StreamException` | [Follow logs](../how-to/follow-logs.md) |
| [Pooling](pooling.md) | `lineSession().pooled()`, `protocolSession(factory).pooled()` | worker acquisition, reuse, retirement, metrics | pool response, pooled session exceptions | [Reuse workers](../how-to/reuse-workers.md) |
| [Terminal capability](terminal.md) | session builders with `TerminalPolicy.REQUIRED` | explicit terminal capability request and unavailable-terminal failure | session failure before silent pipe fallback | [Require a terminal](../how-to/require-terminal.md) |
| [Scenario presets](presets.md) | `ScenarioPresets` | typed defaults without a second runtime | customized scenario builders | [Examples](../examples.md) |
| [Integrations](integrations.md) | `io.github.ulviar:icli-integrations` | structured adapter boundary over existing scenarios | adapter-specific result/error types | [Wrap a CLI tool](../how-to/wrap-cli-tool.md) |

`DiagnosticsOptions` is cross-cutting observation, not a standalone process scenario. Its contract is described in
[Diagnostics](../reference/diagnostics.md).
