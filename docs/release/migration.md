# Migration Notes

Treat the current iCLI API as a new pre-release baseline, not as an incremental update to the earlier prototype.

## What carries forward

- Command service as the main user-facing entry point.
- Fluent scenario builders.
- Typed command results.
- Session-oriented workflows.
- Scenario-first API design.

## What changes

- Public API is rebuilt around canonical scenarios instead of a generic flag-heavy process runner.
- Runtime invariants belong to value objects, policies, resolvers, and scenario runtimes.
- Backend-specific process libraries do not leak into core public signatures.
- Documentation describes only implemented and tested behavior.
- Session-family handles (`Session`, `Expect`, `LineSession`, `StreamSession`, and `PooledLineSession`) are sealed public
  interfaces backed by hidden iCLI implementations. Treat them as iCLI-owned handles, not user implementation SPIs.
- The Java core artifact is the named module `com.github.ulviar.icli` and exports only public API packages. Runtime
  implementations live in non-exported internal packages.

## Migration approach

For code written against the earlier experiment, migrate by user workflow:

1. Use `run` for finite commands.
2. Use `interactive` for raw live process control.
3. Use `Session.expect(...)` or `Expect.on(session)` for prompt automation over sessions created by iCLI.
4. Use `lineSession` for line request/response workers.
5. Use `listen` for streaming output.
6. Use `pooled` for reusable line workers.
7. Use `:icli-integrations` for structured CLI adapter boundaries.

Do not instantiate or subclass session implementations. Create handles through `CommandService` scenarios and keep
custom process behavior behind command arguments, decoders, listeners, policies, or optional integration adapters.
