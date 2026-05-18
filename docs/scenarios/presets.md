# Scenario Presets

`ScenarioPresets` are typed builder customizers for common workflows. They do not launch processes, allocate runtime
resources, or create separate runners.

Use presets when the scenario is already chosen and the same group of policies appears repeatedly.

## Example

```java
tool.run(call -> {
    call.args("env");
    ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 16 * 1024)
            .accept(call);
});
```

Compile-tested source: `CommandServiceApiExamples.scenarioPresetComposition`.

## Current preset families

- one-shot command automation;
- environment diagnostics;
- binary output capture;
- line-oriented REPL mode;
- prompt automation sessions;
- log following;
- terminal-required sessions;
- warm worker pools.

## Boundary

A preset never chooses the scenario for the caller. It only applies policies that already exist on that scenario
builder. If a repeated workflow needs new behavior, the behavior belongs in the scenario first, then possibly in a
preset.
