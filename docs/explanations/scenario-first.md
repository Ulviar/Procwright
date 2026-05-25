# Scenario-first Design

iCLI is not a thin wrapper over `ProcessBuilder`. The public API starts with user workflows:

- finite command execution;
- live interactive process control;
- prompt automation;
- line-oriented protocols;
- streaming output;
- reusable workers;
- structured CLI-backed integration boundaries.

Each workflow has different invariants. A timeout in a one-shot command, a request timeout in a line worker, and an
idle timeout in an interactive session are not the same concept. iCLI keeps these decisions close to the scenario that
owns them.

## Design rule

When a new option looks like a low-level process flag, it should first be tested against the scenario model:

- Which user workflow needs it?
- Which invariant owns it?
- What result or failure type exposes it?
- Can invalid combinations fail before process launch?

If the answer is unclear, the feature should stay out of the public API until the scenario, owner, and failure surface
are clear.
