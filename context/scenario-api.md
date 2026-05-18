# Scenario-first пользовательский API

## Позиция

Пользователь должен выбирать сценарий работы, а не набор низкоуровневых параметров запуска.

Публичные гарантии каждого канонического сценария зафиксированы в
[scenario-contracts.md](scenario-contracts.md). Этот документ описывает форму API и пользовательский язык, а contracts
документирует границы ответственности.

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
- "используй прогретые line workers".

## Внешняя форма

Базовый сервис вокруг команды остается хорошей идеей:

```java
var python = CommandService.forCommand("python");
```

Дальше пользователь выбирает сценарий:

```java
CommandResult version = python.run(call -> call.args("--version"));

try (LineSession repl = python.lineSession(session -> session.args("-i"))) {
    LineResponse answer = repl.request("print(6 * 7)");
}

try (Session shell = python.interactive(session -> session.args("-i"))) {
    shell.sendLine("print('ready')");
}
```

Сценарии — это входные точки, а не отдельные архитектурные миры.

Терминал остается сценарной потребностью, а не набором платформенных flags:

```java
try (Session shell = CommandService.forCommand("sh")
        .interactive(session -> session.terminal(TerminalPolicy.REQUIRED))) {
    shell.sendSignal(TerminalSignal.INTERRUPT);
}
```

`TerminalPolicy.REQUIRED` должен завершаться явной ошибкой, если PTY provider недоступен. `TerminalPolicy.AUTO` может
использовать PTY, когда он есть, и fallback в pipes, когда его нет. `TerminalPolicy.DISABLED` всегда использует pipes.
Для `lineSession` под PTY decoder должен учитывать terminal echo, CRLF и prompts конкретного процесса; default
`ResponseDecoder.firstLine()` остается pipe-oriented безопасным default, а не универсальным TTY protocol parser.

## Минимальные сценарии MVP

### `run`

Однократный запуск команды.

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
- timeout/failure закрывает `LineSession`, чтобы не продолжать работу в неизвестном protocol state;
- transcript bounded и доступен в ошибке.
- stdout response backlog bounded отдельной line-session policy, а не размером transcript window.
- одна незавершенная stdout line bounded отдельной line-length policy.
- EOF, timeout и decoder/read failure различаются.

Пользовательский пример:

```java
try (LineSession python = service.lineSession(call -> call.args("-i"))) {
    LineResponse result = python.request("print(6 * 7)");
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

Сценарная автоматизация prompt-диалогов поверх `Session`.

Инварианты сценария:

- ожидание совпадения владеет timeout;
- transcript ограничен;
- match buffer ограничен отдельной policy;
- send/expect values redacted в transcript по умолчанию; verbatim режим — explicit opt-in.
- EOF и timeout различаются;
- порядок send/expect виден в ошибке.
- optional output filters нормализуют вывод перед matching и записью в transcript.
- один `Expect` владеет output streams сессии; закрытие `Expect` закрывает underlying `Session`.

Пользовательский пример:

```java
try (Session session = service.interactive(call -> call.args("-i"));
        Expect expect = session.expect()) {
    expect.expectRegex(Pattern.compile("Python .*"));
    expect.sendLine("print(6 * 7)");
    expect.expectText("42");
}
```

### `listen`

Listen-only streaming workflow для `tail`, `logs --follow` и похожих процессов.

Инварианты сценария:

- stdout/stderr дренируются параллельно;
- listener получает chunks синхронно из pump thread;
- callback delivery сериализован: один listener не получает два chunks одновременно;
- медленный listener создает backpressure на pipe, а не бесконечную очередь в памяти;
- diagnostics bounded и доступны в `StreamExit`/`StreamException`;
- stdin закрывается на старте по умолчанию;
- если процессу нельзя сразу посылать EOF, caller выбирает `keepStdinOpen()` и позже вызывает `StreamSession.closeStdin()`;
- timeout и listener failure завершают session через общий shutdown path.

Пользовательский пример:

```java
try (StreamSession logs = service.listen(call -> call
        .args("logs", "--follow")
        .onOutput(chunk -> {
            if (chunk.source() == StreamSource.STDERR) {
                System.err.print(chunk.text());
            }
        }))) {
    logs.onExit().join();
}
```

### `pooled`

Pooled line-oriented workers для CLI/REPL с дорогим startup.

Инварианты сценария:

- pool использует existing `LineSession` runtime, а не собственный process launcher;
- worker lease скрыт от пользователя: `request(...)` сам берет worker, выполняет line request и возвращает/retire worker;
- `maxSize` ограничивает live workers, `warmupSize` открывает часть workers заранее;
- acquire timeout — pool-level failure, отдельно от request timeout;
- reset hook и health check работают через `LineSession` primitive;
- close запрещает новые requests и graceful-drain текущих leased workers.

Пользовательский пример:

```java
try (PooledLineSession pool = service.pooled(call -> call
        .args("repl")
        .maxSize(4)
        .warmupSize(1)
        .reset(worker -> worker.request("reset")))) {
    LineResponse result = pool.request("status");
}
```

## Сценарий как preset, не как мешок flags

Каждый сценарий должен иметь свой default profile:

```text
run
  stdin: closed unless input provided
  capture: bounded stdout/stderr
  terminal: disabled; use interactive or lineSession when terminal capability is required
  timeout: command timeout

lineSession
  stdin: open
  capture: transcript window
  terminal: disabled by default; per-call AUTO/REQUIRED available through session invocation
  timeout: request timeout + session idle timeout

interactive
  stdin: open
  capture: caller-driven streams
  terminal: disabled by default; per-call AUTO/REQUIRED available through session invocation
  timeout: idle/lifecycle policy

listen
  stdin: close on start by default
  capture: bounded diagnostics window, not full output
  listener: synchronous chunks with process backpressure
  timeout: optional absolute stream timeout

pooled
  worker: existing lineSession
  size: bounded max workers
  acquisition: bounded acquire timeout
  reset/health: explicit hooks through line-session primitive
  shutdown: idle close immediately, leased close after current request
```

Пользователь может переопределить части профиля:

```java
try (StreamSession stream = service.listen(call -> call
        .args("logs", "--follow")
        .timeout(Duration.ofMinutes(2))
        .onOutput(chunk -> System.out.print(chunk.text())))) {
    stream.onExit().join();
}
```

Но базовый API должен сначала предложить сценарий, а не заставить пользователя собирать все policies вручную.

## Scenario presets

Preset — это typed customizer существующего scenario builder, а не новый runner и не отдельная подсистема. Пользователь
все равно выбирает сценарий:

```java
service.run(call -> {
    call.args("env");
    ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 16 * 1024).accept(call);
});
```

Для listen-only workflow:

```java
try (StreamSession stream = service.listen(call -> {
    call.args("logs", "--follow").onOutput(chunk -> System.out.print(chunk.text()));
    ScenarioPresets.logFollowing(Duration.ZERO).accept(call);
})) {
    stream.onExit().join();
}
```

Инварианты:

- preset не выбирает сценарий вместо пользователя;
- preset не создает process runtime;
- preset применяет только те overrides, которые уже существуют в соответствующем invocation builder;
- порядок применения preset и ручных overrides — обычный порядок builder calls.

## Внутренняя модель

Снаружи:

```text
CommandService.run(...)
CommandService.lineSession(...)
CommandService.interactive(...)
CommandService.listen(...)
CommandService.pooled(...)
Session.expect()
```

Внутри:

```text
ScenarioProfile + CommandSpec + InvocationDraft
  -> ResolvedCommand
  -> LaunchPlan + scenario-specific execution plan
  -> Runtime
```

`ScenarioProfile` — internal concept текущего контракта. Он связывает пользовательский сценарий с наборами policies за
границей public API. Если когда-либо понадобится открыть profile-level API, это потребует отдельного ADR и точного
public use case. У каждого сценария может быть свой draft: one-shot использует `CommandInvocation`, raw session — более
узкий `SessionInvocation`, а line workflow — `LineSessionInvocation`, чтобы не протаскивать неподходящие
one-shot/raw-session options вроде `input`, `capture` или text-send charset.

## Как не повторить старое разрастание

Scenario-first не означает "класс на каждый use case".

Хорошо:

- `run`;
- `lineSession`;
- `interactive`;
- `expect`;
- `listen`;
- `pooled`.

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

Diagnostics не являются отдельным пользовательским workflow. Это наблюдательный слой поверх сценариев:

- `DiagnosticsOptions` подключаются к `CommandService`;
- lifecycle events испускают `run`, `interactive`, `lineSession`, `listen` и worker launches внутри `pooled`; `Expect`
  не испускает отдельные process lifecycle events, потому что работает поверх уже открытой `Session`;
- listener и transcript sink получают structured events асинхронно best-effort;
- failures listener/sink игнорируются runtime и не меняют результат процесса;
- `CommandEcho` не содержит environment values и argument values; он публикует executable, argument count,
  environment names и launch metadata.
- event schema и redaction contract зафиксированы в [diagnostics.md](diagnostics.md).

CLI-backed integrations тоже не являются новым process workflow. Optional module `:icli-integrations` строит helper
слой поверх существующих сценариев:

- `JsonLineSession` использует уже открытый `LineSession`;
- `CommandBackedTool` возвращает `ToolCallResult`, но не запускает собственный runtime;
- Content-Length framed JSON helper работает с streams и не знает о `CommandService`;
- MCP-like protocols должны подключаться как adapter modules поверх integration layer, а не через `CommandService.mcp(...)`.

Tool output в таких интеграциях считается недоверенными данными. Agent harness может передать его как observation, но не
должен трактовать содержимое stdout как инструкции.

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
