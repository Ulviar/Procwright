# Public API freeze audit 2026-05-18

## Назначение

Аудит проверяет, готова ли текущая public surface к первому release-candidate freeze без добавления новых runtime
возможностей. Фокус: имена, package placement, Javadocs, exception/result model, defaults, module boundaries и
соответствие scenario-first философии.

## Scope

- Core module `com.github.ulviar.icli`.
- Public packages:
  - `com.github.ulviar.icli`;
  - `com.github.ulviar.icli.command`;
  - `com.github.ulviar.icli.session`;
  - `com.github.ulviar.icli.diagnostics`;
  - `com.github.ulviar.icli.terminal`;
  - `com.github.ulviar.icli.preset`.
- Optional modules:
  - `com.github.ulviar.icli.kotlin`;
  - `com.github.ulviar.icli.integration`.

## Итог

P0/P1 findings не обнаружены. Кодовых изменений для freeze не требуется.

Нужные решения зафиксированы в ADR:

- `CommandService` остается главным entry point.
- Convenience overloads не добавляются перед первым RC.
- `SessionOptions.idleTimeout` сохраняет имя и caller-visible semantics.
- Текущий набор `ScenarioPresets` замораживается.
- Session-family handles остаются sealed non-SPI contracts.
- Diagnostics остается best-effort unordered.
- Expect-level diagnostics и pool worker lifecycle events откладываются.

## Проверенные границы

### Root API

`CommandService` хорошо выражает идею "сервис вокруг команды". Он не является только executor-ом: через него открываются
one-shot, interactive, line-session, streaming и pooled сценарии. Текущие constructor overloads длинные, но они нужны
для service-level defaults и не обязаны быть главным happy path, потому что `forCommand(...)` покрывает базовый вход.

Риск: добавление short static one-liners может создать второй API dialect. Решение: не добавлять shortcuts перед RC.

### Command model

`CommandSpec`, `CommandInvocation`, `RunOptions`, `CapturePolicy`, `ShutdownPolicy`, `CommandInput` и `CommandResult`
образуют понятную policy/value-object группу. Direct argv остается default. Shell mode явный.

Риск: public advanced constructor `CommandResult` позволяет caller-у создать несогласованный bytes/text snapshot.
Это уже закрыто контрактом: iCLI-produced results остаются runtime-owned, advanced constructor документирует
ответственность caller-а.

### Session family

`Session`, `Expect`, `LineSession`, `StreamSession` и `PooledLineSession` являются handles, а не пользовательским SPI.
Sealed contracts и JPMS encapsulation уменьшают риск случайной зависимости от internal implementations.

`SessionOptions.idleTimeout` является единственным именем с повышенным semantic risk. Javadocs достаточно явно
фиксируют caller-visible activity, поэтому переименование перед RC не требуется.

### Diagnostics

Diagnostics API намеренно узкий: listener, transcript sink, lifecycle events, redaction-friendly command echo.
Unordered best-effort delivery документирован и проверяется tests. Это лучше, чем обещать strict ordering без
реального пользовательского сценария.

### Terminal boundary

Terminal capability корректно изолирован в `terminal` package и session-family invocation/options. `run` и `listen`
не получают PTY knobs. `TerminalPolicy.REQUIRED` делает unavailable terminal explicit.

### Scenario presets

`ScenarioPresets` остается typed builder customizer layer. Он не создает новые runners и не выбирает сценарий за
пользователя. Текущий набор достаточен для RC и не должен расширяться без ADR/eval.

### Integrations module

`:icli-integrations` не добавляет внешние runtime dependencies и не тянет MCP SDK в core. JSON/JSONL, Content-Length
framing, cancellable calls и command-backed tool wrappers являются тонким layer поверх существующих сценариев.

### Kotlin module

`:icli-kotlin` является optional ergonomics module. KDoc coverage check есть; generated Dokka docs отложены отдельным
решением. Это приемлемо для первого RC, если public release docs явно фиксируют limitation.

## Residual risks

- API остается pre-1.0 и может получить breaking changes до стабильного релиза.
- Stable Maven Central artifact еще не опубликован.
- Windows ConPTY provider не входит в shipped capability.
- Generated Kotlin API docs не публикуются в public site.
- Raw session pooling, stateful affinity pools и real MCP SDK adapter отложены.

## Рекомендации

- Не добавлять новые public packages до первого RC.
- Не добавлять новые сценарии или shortcuts без ADR/eval.
- При изменении public method/class names обновлять compile-tested examples, public docs и `PublicApiSurfaceTest`.
- Перед реальной публикацией выполнить отдельный publishing implementation step по ADR-0017.
