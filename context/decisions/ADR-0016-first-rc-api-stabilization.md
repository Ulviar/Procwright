# ADR-0016: Стабилизация public API перед первым release candidate

## Статус

Accepted for the first release-candidate baseline.

## Контекст

После фаз `run`, `interactive`, `lineSession`, `expect`, `listen`, `pooled`, scenario presets, Kotlin ergonomics,
CLI-backed integrations, документации и release hardening проект готов к API freeze audit. Перед первым release
candidate нужно решить спорные имена и границы, пока они не стали публичным compatibility debt.

## Решение

### `CommandService` остается основным entry point

Имя `CommandService` сохраняется для первого release candidate: пользователь создает сервис вокруг базовой команды, а
затем выбирает сценарий. Альтернативы вроде `CommandExecutor` хуже отражают sessions, streaming, pooling и structured
integrations.

### Convenience overloads не добавляются перед первым RC

Минимальная форма остается:

```java
CommandService git = CommandService.forCommand("git");
CommandResult result = git.run(call -> call.args("status", "--short"));
```

Это оставляет явный выбор сценария и не превращает API в каталог shortcuts. Новые convenience методы допустимы только
после отдельного eval, если они не скрывают сценарий и не создают второй public dialect.

### `SessionOptions.idleTimeout` сохраняет текущее имя

`idleTimeout` остается именем для caller-visible inactivity timeout. Семантика уже зафиксирована в Javadocs:
учитываются успешные записи в stdin, закрытие stdin и успешные чтения caller-а из session streams. Выход процесса,
который пишет данные, но caller их не читает, не обязан сбрасывать этот timeout.

Переименование перед RC не требуется, потому что:

- текущее имя короткое и согласовано с session lifecycle;
- Javadoc явно раскрывает семантику;
- behavior покрыт integration tests;
- более длинные имена вроде `callerVisibleIdleTimeout` ухудшают API, но не снимают необходимости читать контракт.

### Начальный набор `ScenarioPresets` замораживается

Для первого release candidate сохраняется текущий набор:

- `commandAutomation(...)`;
- `environmentDiagnostics(...)`;
- `binaryOutputCapture(...)`;
- `replLineMode(...)`;
- `promptAutomationSession(...)`;
- `logFollowing(...)`;
- `terminalRequiredSession(...)`;
- `warmWorkerPool(...)`.

Новые presets требуют ADR или eval. Preset остается typed builder customizer и не может становиться runner, выбирать
сценарий вместо пользователя или обходить resolver.

### Session-family handles остаются sealed non-SPI contracts

`Session`, `Expect`, `LineSession`, `StreamSession` и `PooledLineSession` остаются sealed public interfaces с
единственной internal implementation. Пользователь получает handles через `CommandService`, а не реализует их сам.

### Diagnostics остается best-effort unordered

Order-preserving dispatcher не входит в RC. Diagnostics не должен менять поведение execution и не должен блокировать
runtime из-за пользовательского listener-а или transcript sink-а.

### Expect-level process diagnostics не добавляются

`Expect` остается helper над уже открытой `Session`. Process lifecycle events принадлежат owning session scenario.
Если позже понадобится action-level diagnostics для send/expect операций, это будет отдельный feature с новым
контрактом redaction.

### Pool worker lifecycle diagnostics откладывается

Первый RC сохраняет локальный `PooledLineSessionMetrics` и service-owned worker launch diagnostics. Отдельные события
pool worker checkout/reset/retire могут появиться позже, если появится наблюдаемый пользовательский сценарий.

## Последствия

Плюсы:

- API freeze остается совместимым с основной философией: выбрать сценарий, затем уточнить policies.
- Стабилизация не добавляет новые dialects.
- Открытые решения становятся проверяемыми через ADR, docs и release checklist.

Минусы:

- Совсем короткие one-liners не добавлены.
- Некоторым пользователям придется читать contract `idleTimeout`, а не угадывать семантику по имени.
- Diagnostics остается intentionally coarse-grained для `Expect` и pool lifecycle.
