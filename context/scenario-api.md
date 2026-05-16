# Scenario-first пользовательский API

## Позиция

Пользователь должен выбирать сценарий работы, а не набор низкоуровневых параметров запуска.

Это не противоречит invariant-first архитектуре. Наоборот:

- снаружи API говорит языком сценариев;
- внутри каждый сценарий разворачивается в валидированные policies и execution plan;
- пользователь может переопределить детали, но не обязан знать все runtime flags.

Старая версия правильно двигалась в эту сторону. Эту идею надо сохранить.

## Почему не options-first

Options-first API заставляет пользователя думать как автор process runtime:

```java
service.run(call -> call
        .mergeError(true)
        .closeStdin(true)
        .captureStdout(65536)
        .captureStderr(32768)
        .bufferBytes(8192)
        .idleTimeout(Duration.ZERO));
```

Такой API дает много knobs, но плохо выражает намерение. Пользователь хотел не "набор флагов", а один из сценариев:

- "запусти команду и верни результат";
- "открой REPL и обменивайся строками";
- "открой raw interactive session";
- "слушай вывод процесса";
- позже: "используй прогретые workers".

## Внешняя форма

Базовый сервис вокруг команды остается хорошей идеей:

```java
var python = CommandService.forCommand("python");
```

Дальше пользователь выбирает сценарий:

```java
CommandResult version = python.run(call -> call.args("--version"));

try (LineSession repl = python.lineSession(session -> session.args("-i"))) {
    CommandResult answer = repl.process("print(6 * 7)");
}

try (Session shell = python.interactive(session -> session.args("-i"))) {
    shell.sendLine("print('ready')");
}
```

Сценарии — это входные точки, а не отдельные архитектурные миры.

## Минимальные сценарии MVP

### `run`

One-shot command execution.

Инварианты сценария:

- stdin закрывается или получает явно заданный input;
- stdout/stderr дренируются до завершения;
- result содержит exit code, output, duration и diagnostic metadata;
- timeout применяет shutdown policy;
- capture policy имеет безопасный default.

Пользовательский пример:

```java
CommandResult result = git.run(call -> call.args("status", "--short"));
```

### `lineSession`

Line-oriented request/response workflow для REPL-like процессов.

Инварианты сценария:

- один request не перемешивается с другим;
- decoder владеет правилом завершения ответа;
- timeout относится к request/response cycle;
- transcript bounded и доступен в ошибке.

Пользовательский пример:

```java
try (LineSession python = service.lineSession(call -> call.args("-i"))) {
    CommandResult result = python.process("print(6 * 7)");
}
```

### `interactive`

Raw interactive process handle.

Инварианты сценария:

- lifecycle explicit;
- `close()` idempotent;
- raw streams доступны без лишней line protocol модели;
- caller берет больше ответственности за ordering и parsing.

Пользовательский пример:

```java
try (Session session = service.interactive(call -> call.args("-i"))) {
    session.sendLine("print(6 * 7)");
}
```

### `expect`

Scripted prompt automation поверх `Session`.

Инварианты сценария:

- matching владеет timeout;
- transcript bounded;
- EOF и timeout различаются;
- send/expect order виден в failure.

Пользовательский пример:

```java
try (Session session = service.interactive(call -> call.args("-i"));
        Expect expect = session.expect()) {
    expect.expectRegex("Python .*");
    expect.sendLine("print(6 * 7)");
    expect.expectText("42");
}
```

## Сценарий как preset, не как мешок flags

Каждый сценарий должен иметь свой default profile:

```text
run
  stdin: closed unless input provided
  capture: bounded stdout/stderr
  terminal: disabled or auto-limited
  timeout: command timeout

lineSession
  stdin: open
  capture: transcript window
  terminal: auto
  timeout: request timeout + session idle timeout

interactive
  stdin: open
  capture: caller-driven streams
  terminal: auto/required override
  timeout: idle/lifecycle policy
```

Пользователь может переопределить части профиля:

```java
service.run(call -> call
        .args("logs")
        .scenario(RunScenario.streamingLogs())
        .timeout(Duration.ofMinutes(2)));
```

Но базовый API должен сначала предложить сценарий, а не заставить пользователя собирать все policies вручную.

## Внутренняя модель

Снаружи:

```text
CommandService.run(...)
CommandService.lineSession(...)
CommandService.interactive(...)
Session.expect()
```

Внутри:

```text
ScenarioProfile + CommandSpec + InvocationDraft
  -> ResolvedCommand
  -> ExecutionPlan
  -> Runtime
```

`ScenarioProfile` — internal или narrow public concept. Он связывает пользовательский сценарий с наборами policies.

## Как не повторить старое разрастание

Scenario-first не означает "класс на каждый use case".

Хорошо:

- `run`;
- `lineSession`;
- `interactive`;
- `expect`;
- позже `pooled`.

Плохо на раннем этапе:

- `GitRunner`;
- `TailRunner`;
- `McpCommandRunner`;
- `PooledListenOnlyConversation`;
- `StatefulReplConversation`.

Если сценарий можно выразить комбинацией `CommandService + ScenarioProfile + policy override`, новый public facade не
нужен.

## API и инварианты

Scenario-first API отвечает на вопрос пользователя: "что я хочу сделать?"

Invariant-first runtime отвечает на вопрос библиотеки: "какие правила должны быть истинны, чтобы это безопасно
выполнить?"

Итоговая архитектура должна удерживать оба слоя:

- scenario methods дают clean API;
- scenario profiles задают defaults;
- value objects удерживают локальные инварианты;
- resolver собирает нормализованный plan;
- runtime исполняет plan без знания о fluent API.

## Практический вывод

Первая версия должна сохранить концепцию старого API: выбрать сценарий, получить безопасные defaults, затем точечно
переопределить детали.

Главное отличие новой версии: сценарии не должны плодить независимые подсистемы. Они должны быть тонким
пользовательским слоем над общей invariant-first моделью.
