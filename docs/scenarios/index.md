# Scenario Contracts

This is the reference index for iCLI scenario contracts. Use the how-to guides when choosing a workflow for a concrete
task, and the explanation pages for the rationale behind the scenario-first model.

| Contract | Entry point | Owned invariant | Result or failure surface | Compile-tested example |
| --- | --- | --- | --- | --- |
| [Run](run.md) | `Icli.command(...).run()` | finite process completion, bounded capture, timeout, shutdown | `CommandResult`, `CommandExecutionException` | `oneShotScenario`, `explicitCommandConfiguration`, `policyComposition` |
| [Interactive sessions](interactive.md) | `Icli.command(...).interactive()` | raw live process lifecycle and output ownership | `Session`, `SessionExit` | `interactiveScenario` |
| [Expect automation](expect.md) | `Session.expect(...)` | prompt matching over a claimed session output stream | `ExpectException`, bounded transcript | `expectScenario` |
| [Line sessions](line-session.md) | `Icli.command(...).lineSession()` | serialized line request/response cycles | `LineResponse`, `LineSessionException` | `lineSessionScenario` |
| [Protocol sessions](protocol-session.md) | `Icli.command(...).protocolSession(...)` | adapter-owned framed or typed request/response cycles | typed response, `ProtocolSessionException` | `protocolSessionScenario` |
| [Streaming](streaming.md) | `Icli.command(...).listen()` | output callback delivery without retaining full output | `StreamSession`, `StreamExit`, `StreamException` | `listenOnlyStreamingScenario` |
| [Pooling](pooling.md) | `lineSession().pooled()`, `protocolSession(factory).pooled()` | worker acquisition, reuse, retirement, metrics | pool response, pooled session exceptions | `pooledLineSessionScenario`, `pooledProtocolSessionScenario` |
| [Terminal capability](terminal.md) | session builders with `TerminalPolicy.REQUIRED` | explicit terminal capability request and unavailable-terminal failure | session failure before silent pipe fallback | `terminalRequiredSessionScenario` |
| [Scenario presets](presets.md) | `ScenarioPresets` | typed defaults without a second runtime | customized scenario builders | `scenarioPresetComposition` |
| [Integrations](integrations.md) | `:icli-integrations` | structured adapter boundary over existing scenarios | adapter-specific result/error types | `CommandBackedToolExamples` |

`DiagnosticsOptions` is cross-cutting observation, not a standalone process scenario. Its contract is described in
[Diagnostics](../reference/diagnostics.md). Compile-tested example: `diagnosticsScenario`.
