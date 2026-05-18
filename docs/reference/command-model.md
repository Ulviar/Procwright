# Command Model

iCLI separates reusable command configuration from per-scenario invocation details.

## CommandSpec

`CommandSpec` is the immutable base command configuration. It owns:

- executable or explicit shell command line;
- base arguments;
- base working directory;
- base environment overrides;
- environment policy.

Use `CommandSpec.of("tool")` or `CommandSpec.builder("tool")` for direct argv execution.

Use `CommandSpec.shell(...)` only when shell syntax is required. Do not build shell command strings from untrusted
input.

## CommandService

`CommandService` binds a `CommandSpec` to scenario defaults and launches scenario workflows:

- `run`;
- `interactive`;
- `lineSession`;
- `listen`;
- `pooled`.

Scenario callbacks receive mutable builders, but those builders are drafts. They are resolved into immutable validated
plans before runtime code launches a process.

## Override precedence

Base command configuration belongs to `CommandSpec`. Per-call or per-session overrides belong to scenario invocation
builders. The resolver combines both layers before launch.

Use base configuration for stable defaults, such as working directory or environment overrides shared by all calls. Use
scenario invocation callbacks for operation-specific arguments, timeout, capture, listener, terminal, or pooling
choices.
