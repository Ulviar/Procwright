# ADR-0009: Optional protocol adapters

Статус: принято.

## Контекст

Пользователям нужны готовые wire adapters для распространенных CLI-протоколов, но core runtime не должен зависеть от
JSON library или содержать protocol-specific parsing. Integration layer также не должен превращаться в tool framework,
дублировать failure taxonomy core или вводить собственную JSON-модель.

## Решение

Модуль `:procwright-integrations` содержит только:

- factories `ProtocolAdapter` для JSON Lines, delimiter-framed bytes и Content-Length JSON;
- typed mapping между domain types и Jackson `JsonNode`;
- `IntegrationProtocolException` для ошибок framing, UTF-8 и JSON.

Jackson Databind является transitive dependency optional-модуля, потому что `JsonNode` входит в его public API. Core и
Kotlin module от Jackson не зависят. Все adapters подключаются через `protocolSession(factory)` и поэтому используют
единственный process runtime Procwright.

## Инварианты

- каждый factory call создает новый adapter для отдельной session или pool worker;
- JSON Lines, Content-Length body и delimiter frame имеют явные byte limits;
- Content-Length parser ограничивает header block, валидирует его до чтения body и считает длину в bytes;
- JSON bytes декодируются строгим UTF-8, а parser принимает ровно одно JSON value;
- typed callbacks могут выполняться конкурентно на разных workers и должны быть thread-safe;
- module не содержит process launcher, generic tool wrapper, error-mapping framework или MCP SDK.

## Последствия

Пользователь работает с широко известной Jackson model и получает framing без второго runtime. Domain-level tool API и
маппинг ошибок остаются в приложении, где известны его протокол и требования.
