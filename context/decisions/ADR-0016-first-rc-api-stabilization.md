# ADR-0016: Стабилизация public API baseline 0.1.0

## Статус

Принято.

## Контекст

Baseline `0.1.0` фиксирует public API для `run`, `interactive`, `lineSession`, `expect`, `listen`, `pooled`, scenario
presets, Kotlin ergonomics и CLI-backed integrations. Спорные имена и границы должны быть решены на уровне baseline,
чтобы не создавать случайный compatibility debt.

## Решение

### `CommandService` остается основным entry point

Имя `CommandService` сохраняется в baseline `0.1.0`: пользователь создает сервис вокруг базовой команды, а затем
выбирает сценарий. Название должно отражать sessions, streaming, pooling и structured integrations, а не только
one-shot execution.

### Convenience overloads не входят в baseline 0.1.0

Минимальная форма остается:

```java
CommandService git = Icli.command("git");
CommandResult result = git.run().execute("status", "--short");
```

Это оставляет явный выбор сценария и не превращает API в каталог shortcuts. Новые convenience методы допустимы только
после отдельного eval, если они не скрывают сценарий и не создают второй public dialect.

### `SessionOptions.idleTimeout` сохраняет текущее имя

`idleTimeout` остается именем для caller-visible inactivity timeout. Семантика уже зафиксирована в Javadocs:
учитываются успешные записи в stdin, закрытие stdin и успешные чтения caller-а из session streams. Выход процесса,
который пишет данные, но caller их не читает, не обязан сбрасывать этот timeout.

Переименование не требуется, потому что:

- текущее имя короткое и согласовано с session lifecycle;
- Javadoc явно раскрывает семантику;
- behavior покрыт integration tests;
- более длинные имена вроде `callerVisibleIdleTimeout` ухудшают API, но не снимают необходимости читать контракт.

### Начальный набор `ScenarioPresets` замораживается

В baseline `0.1.0` входит текущий набор:

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

Order-preserving dispatcher не входит в baseline `0.1.0`. Diagnostics не должен менять поведение execution и не должен
блокировать runtime из-за пользовательского listener-а или transcript sink-а.

### Expect-level process diagnostics не добавляются

`Expect` остается helper над уже открытой `Session`. Process lifecycle events принадлежат owning session scenario.
Если позже понадобится action-level diagnostics для send/expect операций, это будет отдельный feature с новым
контрактом redaction.

### Pool worker lifecycle diagnostics откладывается

Первый релиз сохраняет локальный `PooledLineSessionMetrics` и service-owned worker launch diagnostics. Отдельные события
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
