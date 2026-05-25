# Scenario Contracts

This is the reference index for iCLI scenario contracts. Use the how-to guides when choosing a workflow for a concrete
task, and the explanation pages for the rationale behind the scenario-first model.

| Contract | Entry point | What iCLI handles | Result or failure surface | Example |
| --- | --- | --- | --- | --- |
| [Run](run.md) | `Icli.command(...).run()` | finite process completion, bounded capture, timeout, shutdown | `CommandResult`, `CommandExecutionException` | [`oneShotScenario`](../examples.md#one-shot-command), [`explicitCommandConfiguration`](../examples.md#core-examples), [`policyComposition`](../examples.md#core-examples) |
| [Interactive sessions](interactive.md) | `Icli.command(...).interactive()` | raw live process lifecycle and output ownership | `Session`, `SessionExit` | [`interactiveScenario`](../examples.md#core-examples) |
| [Expect automation](expect.md) | `Session.expect(...)` | prompt matching over a claimed session output stream | `ExpectException`, bounded transcript | [`expectScenario`](../examples.md#core-examples) |
| [Line sessions](line-session.md) | `Icli.command(...).lineSession()` | serialized line request/response cycles | `LineResponse`, `LineSessionException` | [`lineSessionScenario`](../examples.md#line-worker) |
| [Protocol sessions](protocol-session.md) | `Icli.command(...).protocolSession(...)` | adapter-owned framed or typed request/response cycles | typed response, `ProtocolSessionException` | [`protocolSessionScenario`](../examples.md#framed-protocol-worker) |
| [Streaming](streaming.md) | `Icli.command(...).listen()` | output callback delivery without retaining full output | `StreamSession`, `StreamExit`, `StreamException` | [`listenOnlyStreamingScenario`](../examples.md#core-examples), [`daemonReadinessScenario`](../how-to/choose-process-scenario.md#local-daemon-startup-with-readiness-check) |
| [Pooling](pooling.md) | `lineSession().pooled()`, `protocolSession(factory).pooled()` | worker acquisition, reuse, retirement, metrics | pool response, pooled session exceptions | [`pooledLineSessionScenario`](../examples.md#worker-pool), [`pooledProtocolSessionScenario`](../examples.md#typed-protocol-worker-pool) |
| [Terminal capability](terminal.md) | session builders with `TerminalPolicy.REQUIRED` | explicit terminal capability request and unavailable-terminal failure | session failure before silent pipe fallback | [`terminalRequiredSessionScenario`](../examples.md#core-examples) |
| [Scenario presets](presets.md) | `ScenarioPresets` | typed defaults without a second runtime | customized scenario builders | [`scenarioPresetComposition`](../examples.md#core-examples) |
| [Integrations](integrations.md) | `io.github.ulviar:icli-integrations` | structured adapter boundary over existing scenarios | adapter-specific result/error types | [`CommandBackedToolExamples`](../examples.md#integration-examples) |

`DiagnosticsOptions` is cross-cutting observation, not a standalone process scenario. Its contract is described in
[Diagnostics](../reference/diagnostics.md). Example: [`diagnosticsScenario`](../examples.md#core-examples).
