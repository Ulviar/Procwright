# Идеи пользовательского API

## Позиция

Пользовательские API-идеи старой версии считаются сильной стороной проекта. Их надо переносить как продуктовый
замысел, но не обязательно как прежнюю class graph.

Сохраняем:

- сервис вокруг базовой команды;
- выбор сценария вместо ручного набора параметров запуска;
- fluent customization per call;
- безопасные дефолты;
- typed result вместо "строка или exception";
- похожую модель для one-shot и session workflows;
- Java-first API, удобный из Kotlin;
- низкоуровневые настройки через options objects.
- широкие возможности через композицию policy/value objects, а не через множество runners.

Не переносим автоматически:

- разрастание `runner`, `client`, `processor`, `conversation`, `delegate`;
- отдельный public facade под каждый сценарий;
- raw/session pooling, affinity и lease scope в MVP;
- `Essential` / `Advanced` как публичный маркетинговый слой.

## Базовый стиль

API должен читаться как маленькая библиотека вокруг конкретной CLI-программы:

```java
var python = CommandService.forCommand("python");

CommandResult result = python.run(call -> call.args("--version"));
```

Важно: `run`, `lineSession`, `interactive`, `expect` — это сценарии пользователя. Они должны задавать безопасный
профиль defaults. Пользователь уточняет детали, но не собирает runtime вручную.

Более явная конфигурация:

```java
var command = CommandSpec.builder("python")
        .workingDirectory(projectDir)
        .putEnvironment("PYTHONUTF8", "1")
        .build();

var python = new CommandService(command, RunOptions.defaults());

CommandResult result = python.run(call -> call.args("--version"));
```

## One-shot command

```java
CommandResult result = service.run(call -> call.args("status", "--short"));

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

## Per-call builder

Старая builder lambda остается хорошей идеей:

```java
CommandResult result = service.run(call -> call
        .args("run", "--rm", "alpine", "echo", "hello")
        .workingDirectory(projectDir)
        .putEnvironment("CI", "true")
        .timeout(Duration.ofSeconds(10))
        .capture(CapturePolicy.bounded(128 * 1024)));
```

Builder должен быть коротким. Редкие настройки должны жить в options object, а не в отдельном facade.

Builder — это draft layer. После `build()` или перед запуском данные должны пройти resolver и превратиться в
валидированный execution plan.

## Interactive session

```java
try (Session session = service.interactive(call -> call.args("-i"))) {
    session.sendLine("print(6 * 7)");
    session.closeStdin();
}
```

Line-oriented обертка:

```java
try (LineSession session = service.lineSession(call -> call.args("-i"))) {
    LineResponse response = session.request("print(6 * 7)");
}
```

`Session` остается raw handle, а `LineSession` владеет сериализацией request/response и decoder policy.

## Expect helper

```java
try (Session session = service.interactive(call -> call.args("-i"));
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

Идея `service.pooled()` возвращается только как узкий line-oriented scenario поверх `LineSession`, без отдельного
runtime и без lease objects.

Первый вариант должен выглядеть как естественное расширение сервиса:

```java
try (PooledLineSession pool = service.pooled(pool -> pool
        .args("repl")
        .maxSize(4)
        .warmupSize(1))) {
    LineResponse result = pool.request("status");
}
```

Public API не должен включать affinity, lease scope и conversation classes. Retirement остается policy внутри
`PooledLineSessionOptions`: `maxRequestsPerWorker`, `maxWorkerAge`, health check и reset hook.

## Scenario presets

Готовые профили должны быть не runners, а typed builder customizers:

```java
service.run(call -> {
    call.args("env");
    ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 16 * 1024).accept(call);
});
```

Это сохраняет важную идею: пользователь сначала выбирает сценарий (`run`, `listen`, `lineSession`, `pooled`), а preset
лишь применяет осмысленный набор overrides. Если preset начинает требовать новый lifecycle, state или transport, это уже
не preset, а кандидат на отдельный сценарий и ADR.

## CLI-backed integrations

Интеграции должны выглядеть как тонкий adapter layer поверх сценариев, а не как новый `Runner`:

```java
try (LineSession line = service.lineSession(call -> call.args("json-worker"));
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
