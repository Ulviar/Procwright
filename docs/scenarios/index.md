# Scenarios

iCLI exposes workflows instead of a single generic process runner. Each scenario owns a different set of invariants and
returns a type shaped for that workflow.

| User need | API entry point | Compile-tested example |
| --- | --- | --- |
| Run a finite command and inspect the result. | `run` | `oneShotScenario` |
| Define reusable command-level defaults. | `CommandSpec` + `CommandService` | `explicitCommandConfiguration` |
| Compose timeout, capture, and shutdown policies. | `run` policies | `policyComposition` |
| Control a live process directly. | `interactive` | `interactiveScenario` |
| Automate a prompt-oriented dialogue. | `interactive` + `Expect` | `expectScenario` |
| Exchange line-oriented requests and responses. | `lineSession` | `lineSessionScenario` |
| Exchange framed or typed requests and responses. | `protocolSession` | `protocolSessionScenario` |
| Require terminal capability. | `interactive` + `TerminalPolicy.REQUIRED` | `terminalRequiredSessionScenario` |
| Consume output as it arrives. | `listen` | `listenOnlyStreamingScenario` |
| Observe lifecycle and bounded diagnostics. | `DiagnosticsOptions` | `diagnosticsScenario` |
| Reuse warm line workers. | `pooled` | `pooledLineSessionScenario` |
| Reuse warm typed protocol workers. | `pooledProtocol` | `pooledProtocolSessionScenario` |
| Apply typed workflow defaults. | `ScenarioPresets` | `scenarioPresetComposition` |

The example method names above are intentionally mirrored from compile-tested source. The release gate should keep this
page synchronized with the tested public API surface.

`DiagnosticsOptions` and `ScenarioPresets` are cross-cutting APIs, not canonical scenarios. They are included in the
decision map because users often discover them while choosing a workflow.

## Scenario boundaries

- `run` owns finite command execution and typed command results.
- `interactive` owns raw session lifecycle and direct stream access.
- `Expect` owns prompt matching over a claimed interactive session output stream.
- `lineSession` owns serialized line request/response protocols.
- `protocolSession` owns serialized framed request/response protocols through a caller-provided adapter.
- `listen` owns streaming callbacks and bounded diagnostics without retaining full output.
- `pooled` owns reusable line-session worker lifecycle and pool metrics.
- `pooledProtocol` owns reusable typed protocol worker lifecycle and pool metrics.
- integration helpers own structured adapter boundaries over existing scenarios.

## Choosing between similar scenarios

Use `run` when the process should finish and return a complete result. Use `listen` when the process may run for a long
time and the caller needs output chunks while it is still alive.

Use `interactive` when the caller owns protocol parsing. Use `Expect` when the protocol is prompt-oriented and waiting
for text or regex output is the main operation. Use `lineSession` when the protocol is a serialized request/response
line protocol. Use `protocolSession` when the worker protocol is framed, multi-line, binary, or typed.

Use `pooled` or `pooledProtocol` only when the worker protocol is safe to reuse. Pooling is still scenario-specific; it
does not expose raw worker leases.
