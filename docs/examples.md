# Examples

Public snippets are backed by compile-tested source files. The method names below are used throughout the documentation
so a reader can find the complete source shape when a page shows only a focused fragment.

## Core examples

Source file in the same checkout or release source tree:
`src/test/java/com/github/ulviar/icli/examples/CommandServiceApiExamples.java`

| Example | Demonstrates |
| --- | --- |
| `oneShotScenario` | Finite command execution with `run` and `CommandResult`. |
| `explicitCommandConfiguration` | Reusable `CommandSpec` and `CommandService` defaults. |
| `policyComposition` | Timeout, bounded capture, and shutdown policy composition. |
| `interactiveScenario` | Raw interactive session lifecycle. |
| `lineSessionScenario` | Line-oriented request/response workflow. |
| `expectScenario` | Prompt automation over `Session`. |
| `terminalRequiredSessionScenario` | Required terminal capability for a session workflow. |
| `listenOnlyStreamingScenario` | Streaming output through `listen`. |
| `diagnosticsScenario` | Lifecycle diagnostics through `DiagnosticsOptions`. |
| `pooledLineSessionScenario` | Warm line-session worker pool. |
| `protocolSessionScenario` | Framed or typed request/response worker. |
| `pooledProtocolSessionScenario` | Warm typed protocol worker pool. |
| `scenarioPresetComposition` | Typed `ScenarioPresets` composition. |

## Integration examples

Source file in the same checkout or release source tree:
`icli-integrations/src/test/java/com/github/ulviar/icli/integration/examples/CommandBackedToolExamples.java`

| Example | Demonstrates |
| --- | --- |
| `oneShotCommandBackedTool` | One-shot command-backed structured tool. |
| `jsonLineCommandBackedTool` | JSON Lines tool over `LineSession`. |
| `cancellableJsonLineCall` | Cancellable JSON Lines request. |
| `contentLengthFramedJson` | Content-Length framed JSON read/write helpers. |
