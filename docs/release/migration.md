# Migration Notes

iCLI is a clean rewrite, not an incremental refactor of the earlier implementation.

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

## Migration approach

For code written against the earlier experiment, migrate by user workflow:

1. Use `run` for finite commands.
2. Use `interactive` for raw live process control.
3. Use `Expect` for prompt automation.
4. Use `lineSession` for line request/response workers.
5. Use `listen` for streaming output.
6. Use `pooled` for reusable line workers.
7. Use `:icli-integrations` for structured CLI adapter boundaries.
