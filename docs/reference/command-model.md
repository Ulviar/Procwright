# Command Model

Procwright separates reusable command configuration from per-scenario invocation details.

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

```java
Path projectDir = Path.of(".");

CommandSpec command = CommandSpec.builder("python")
        .workingDirectory(projectDir)
        .putEnvironment("PYTHONUTF8", "1")
        .build();

CommandService python = Procwright.command(command);

python.run().execute("--version");
```

## CommandService

`CommandService` binds a `CommandSpec` to scenario defaults and launches scenario workflows:

- `run`;
- `interactive`;
- `lineSession`;
- `protocolSession`;
- `listen`;
- `lineSession().pooled()`;
- `protocolSession(factory).pooled()`.

Scenario methods first select the workflow and then expose only configuration relevant to that workflow. The primary API
shape is scenario object plus `with...` configuration.

## Override precedence

Base command configuration belongs to `CommandSpec`. Per-call or per-session overrides belong to scenario objects. The
resolver combines both layers before launch.

Use base configuration for stable defaults, such as working directory or environment overrides shared by all calls. Use
scenario invocation callbacks for operation-specific arguments, timeout, capture, listener, terminal, or pooling
choices.
