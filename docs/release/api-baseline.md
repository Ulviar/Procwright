# API Surface

The `0.1.0` public API is the documented scenario surface:

- `run`
- `interactive`
- `Expect`
- `lineSession`
- `protocolSession`
- `lineSession().pooled()`
- `protocolSession(factory).pooled()`
- `listen`
- `ScenarioPresets`
- optional integrations helpers
- optional Kotlin extensions
- optional Kotlin scenario DSL helpers

`ProcwrightException` is the common unchecked base class for failures produced by Procwright. Scenario-specific exceptions remain
the source of structured data such as command results, reasons, transcripts, diagnostics, and process exit information.

Generated Java API docs are linked from [API Docs](../api/index.md). Kotlin usage is documented in
[Kotlin API](../reference/kotlin-api.md).

Before `1.0.0`, public API names and option shapes may still change. Breaking changes will be documented in public docs
and examples.
