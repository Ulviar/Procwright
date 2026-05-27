# Идеи пользовательского API

## Позиция

Пользовательский API строится вокруг сценариев. Пользователь сначала выбирает workflow, затем уточняет детали через
fluent configuration и policy/value objects.

Сохраняем как текущие принципы:

- сервис вокруг базовой команды;
- выбор сценария вместо ручного набора параметров запуска;
- fluent customization per call;
- безопасные дефолты;
- typed result вместо "строка или exception";
- похожую модель для one-shot и session workflows;
- Java-first API, удобный из Kotlin;
- низкоуровневые настройки через options objects.
- широкие возможности через композицию policy/value objects, а не через множество runners.

Не добавляем без отдельного обоснования:

- разрастание `runner`, `client`, `processor`, `conversation`, `delegate`;
- отдельный public facade под каждый сценарий;
- raw/session pooling, affinity и lease scope в baseline `0.1.0`;
- `Essential` / `Advanced` как публичный маркетинговый слой.

## Базовый стиль

API должен читаться как маленькая библиотека вокруг конкретной CLI-программы:

```java
var python = Icli.command("python");

CommandResult result = python.run().execute("--version");
```

Важно: `run`, `lineSession`, `interactive`, `expect` — это сценарии пользователя. Они должны задавать безопасный
профиль defaults. Пользователь уточняет детали, но не собирает runtime вручную.

Более явная конфигурация:

```java
var command = CommandSpec.builder("python")
        .workingDirectory(projectDir)
        .putEnvironment("PYTHONUTF8", "1")
        .build();

var python = Icli.command(command);

CommandResult result = python.run().execute("--version");
```

## One-shot command

```java
CommandResult result = service.run().execute("status", "--short");

if (result.succeeded()) {
    System.out.println(result.stdout());
} else {
    throw result.toException();
}
```

Важные свойства:

- exit code доступен всегда;
- stdout/stderr доступны раздельно;
- есть truncation flags;
- timeout и shutdown outcome отражены в результате или исключении;
- command echo доступен для диагностики.

## Per-scenario configuration

Сценарные методы `with...` остаются основным способом точечной настройки вызова:

```java
CommandResult result = service.run()
        .withArgs("run", "--rm", "alpine", "echo", "hello")
        .withWorkingDirectory(projectDir)
        .withEnvironment("CI", "true")
        .withTimeout(Duration.ofSeconds(10))
        .withCapture(CapturePolicy.bounded(128 * 1024))
        .execute();
```

Callback `configuredBy(...)` остается escape hatch для preset/integration кода, который уже работает с builder.

Builder — это draft layer. После `build()` или перед запуском данные должны пройти resolver и превратиться в
валидированный execution plan.

## Interactive session

```java
try (Session session = service.interactive().withArg("-i").open()) {
    session.sendLine("print(6 * 7)");
    session.closeStdin();
}
```

Line-oriented обертка:

```java
try (LineSession session = service.lineSession().withArg("-i").open()) {
    LineResponse response = session.request("print(6 * 7)");
}
```

`Session` остается raw handle, а `LineSession` владеет сериализацией request/response и decoder policy.

## Expect helper

```java
try (Session session = service.interactive().withArg("-i").open();
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
    expect.expectRegex(Pattern.compile("Python .*"));
    expect.sendLine("print(6 * 7)");
    expect.expectText("42");
}
```

Требования:

- bounded transcript в ошибке;
- literal и regex matching;
- понятная timeout exception;
- никакой большой DSL до реальной потребности.

## Pooled line sessions

Идея pooled workers возвращается только как nested mode поверх конкретного session-сценария, без отдельного runtime и
без lease objects.

Первый вариант должен выглядеть как естественное расширение сервиса:

```java
try (PooledLineSession pool = service.lineSession()
        .withArgs("repl")
        .pooled()
        .withMaxSize(4)
        .withWarmupSize(1)
        .open()) {
    LineResponse result = pool.request("status");
}
```

Public API не должен включать affinity, lease scope и conversation classes. Retirement остается policy внутри
`PooledLineSessionOptions`: `maxRequestsPerWorker`, `maxWorkerAge`, health check и reset hook.

## Scenario presets

Готовые профили должны быть не runners, а typed builder customizers:

```java
service.run()
        .withArgs("env")
        .configuredBy(ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 16 * 1024))
        .execute();
```

Это сохраняет важную идею: пользователь сначала выбирает сценарий (`run`, `listen`, `lineSession`,
`lineSession().pooled()`), а preset лишь применяет осмысленный набор overrides. Если preset начинает требовать новый
lifecycle, state или transport, это уже не preset, а кандидат на отдельный сценарий и ADR.

## CLI-backed integrations

Интеграции должны выглядеть как тонкий adapter layer поверх сценариев, а не как новый `Runner`:

```java
try (LineSession line = service.lineSession().withArg("json-worker").open();
        JsonLineSession json = JsonLineSession.over(line)) {
    CommandBackedTool<String, JsonValue> tool = CommandBackedTool.jsonLine(
            json,
            input -> JsonValue.object(Map.of("input", JsonValue.string(input))),
            Function.identity());

    ToolCallResult<JsonValue> result = tool.call("payload");
}
```

Сохраняем:

- structured `ToolCallResult` вместо исключений как единственного observation;
- JSON/JSONL helpers для command-backed tools;
- Content-Length framed JSON helpers для MCP-like stdin/stdout протоколов;
- cancellation как явный outcome, который закрывает underlying session;
- security note: output CLI — это недоверенные данные, а не инструкции.

Не переносим в core:

- MCP SDK dependency;
- registry tools;
- permission framework;
- agent loop;
- broad generic tool execution API.
