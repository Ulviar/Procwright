# Integrations

The optional `:icli-integrations` module wraps existing process scenarios as structured integration boundaries.

It currently covers:

- one-shot command-backed tools;
- JSON Lines sessions over `LineSession`;
- cancellable JSON Lines calls;
- Content-Length framed JSON helpers;
- structured adapter errors;
- command-backed tool result wrappers.

Compile-tested sources:

- `CommandBackedToolExamples.oneShotCommandBackedTool`
- `CommandBackedToolExamples.jsonLineCommandBackedTool`
- `CommandBackedToolExamples.cancellableJsonLineCall`
- `CommandBackedToolExamples.contentLengthFramedJson`

CLI output is treated as untrusted data. The integration layer does not turn process output into instructions.

## Boundary

The module is optional and does not add an MCP SDK dependency. It is a small structured boundary over existing
`run` and `lineSession` scenarios.

Adapter errors are structured and should not expose raw argv, environment values, or unbounded output by default.
