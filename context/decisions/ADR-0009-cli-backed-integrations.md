# ADR-0009: CLI-backed integrations как optional module

Статус: принято.

## Контекст

Procwright должен быть полезен как process harness для command-backed tools и MCP-like адаптеров, но core runtime не должен
становиться agent framework, tool registry или MCP SDK wrapper. Внешние протоколы должны использовать уже существующие
сценарии `run`, `lineSession`, `interactive`, `listen` и `pooled`.

## Решение

Добавляем отдельный Gradle-модуль `:procwright-integrations` как именованный Java module
`io.github.ulviar.procwright.integrations`.

Модуль содержит:

- минимальную immutable JSON-модель и compact JSON codec;
- JSON Lines helpers для line-oriented CLI workers;
- `JsonLineSession` поверх существующего `LineSession`;
- Content-Length framed JSON helpers для MCP-like stdin/stdout протоколов;
- `CancellableCall`, где cancel фиксируется как structured observation и затем проходит через lifecycle close;
- `CliAdapterError` и `ToolCallResult` для structured success/failure;
- `CommandBackedTool` как узкий wrapper над уже существующими command workflows.

Модуль не содержит:

- MCP dependency;
- agent loop;
- permission system;
- schema generation framework;
- broad `execute anything` API;
- нового process runtime.

## Инварианты

- Integration layer не запускает процессы напрямую, если это можно выразить через существующий scenario.
- JSONL helper использует `LineSession`, поэтому наследует сериализацию request/response, timeout и shutdown path.
- Content-Length helper валидирует headers и frame size до разбора JSON body.
- Adapter errors не включают raw stdout/stderr или raw argv/env values.
- Cancellation должна стать observable `cancelled` outcome, а не случайным timeout/protocol failure.
- Tool output считается недоверенными данными, а не инструкциями для agent harness.
- JPMS descriptor экспортирует только `io.github.ulviar.procwright.integration` и требует core module.

## Последствия

Core остается меньше и стабильнее: CLI-backed integrations расширяют библиотеку через optional module. Реальный MCP SDK
adapter можно добавить позже отдельным модулем поверх `:procwright-integrations`, не меняя core execution kernel.
