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
- pooled API в MVP;
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
    System.out.println(result.stdoutText());
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
try (Session session = service.openSession(call -> call.args("-i"))) {
    session.sendLine("print(6 * 7)");
    String output = session.readUntil("42", Duration.ofSeconds(2));
    session.closeStdin();
}
```

Возможная line-oriented обертка:

```java
try (LineSession session = service.openLineSession(call -> call.args("-i"))) {
    CommandResult response = session.process("print(6 * 7)");
}
```

Сначала стабилизируем `Session`, затем добавляем `LineSession`.

## Expect helper

```java
try (Session session = service.openSession(call -> call.args("-i"));
        Expect expect = Expect.on(session).withTimeout(Duration.ofSeconds(2))) {
    expect.expectRegex("Python .*");
    expect.sendLine("print(6 * 7)");
    expect.expectText("42");
}
```

Требования:

- bounded transcript в ошибке;
- literal и regex matching;
- понятная timeout exception;
- никакой большой DSL до реальной потребности.

## Будущий pooling

Идея `service.pooled()` хорошая как направление, но не для MVP.

Когда pooling вернется, он должен выглядеть как естественное расширение сервиса:

```java
try (PooledCommandService pool = service.pooled(pool -> pool.maxSize(4))) {
    CommandResult result = pool.run("version");
}
```

До этого момента public API не должен включать affinity, retirement, lease scope и conversation classes.
