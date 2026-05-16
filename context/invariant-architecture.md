# Архитектура изолированных инвариантов

## Главная идея

Широкие возможности не должны появляться через широкий public API. Они должны появляться через небольшое число
компонуемых объектов, каждый из которых удерживает свой инвариант.

Внешний API при этом должен оставаться scenario-first: пользователь выбирает `run`, `lineSession`, `interactive` или
`expect`, а не ручной набор runtime flags. Scenario layer разворачивается во внутренние policies и execution plan.

Пользовательский API должен выглядеть простым:

```java
CommandResult result = service.run(call -> call
        .args("--version")
        .timeout(Duration.ofSeconds(3))
        .capture(CapturePolicy.bounded(64 * 1024)));
```

Внутри эта запись не должна превращаться в набор случайных флагов. Она должна собираться в валидированный план:

```text
CommandSpec + InvocationOverrides + RunOptions
  -> CommandInvocation
  -> ResolvedCommand
  -> ExecutionPlan
  -> ProcessLauncher
```

Каждый переход — отдельная граница инвариантов.

## Что значит изолировать инвариант

Инвариант должен жить в одном месте:

- не в комментарии;
- не в README;
- не в проверках, размазанных по runtime;
- не в соглашении между несколькими builder methods.

Если правило важно, у него должен быть хозяин:

- value object;
- policy object;
- state machine;
- validator;
- test/eval case.

Пример: "timeout не может быть отрицательным" — инвариант `TimeoutPolicy`, а не проверка в каждом методе запуска.

Пример: "direct argv и shell command нельзя смешивать" — инвариант `CommandLine`, а не ветвление в launcher.

## Слои инвариантов

### API draft layer

Builder и lambda customizer — это draft layer. Он может быть удобным, неполным и mutable.

Его задача:

- принять пользовательский ввод;
- дать приятный fluent API;
- собрать данные;
- вызвать `build()` или внутренний resolver.

Он не должен быть доменной моделью.

### Domain layer

Domain layer содержит immutable объекты, которые уже валидны:

- `CommandSpec`;
- `CommandInvocation`;
- `RunOptions`;
- `CapturePolicy`;
- `ShutdownPolicy`;
- `TerminalPolicy`;
- `EnvironmentPatch`;
- `WorkingDirectory`;
- `CharsetPolicy`.

Если объект создан, его базовые инварианты выполнены.

### Resolution layer

Resolution layer соединяет базовую команду, per-call overrides и defaults:

```text
CommandSpec + InvocationDraft + RunOptions -> ResolvedCommand
```

Именно здесь решаются вопросы:

- какие args итоговые;
- какая working directory;
- какие env overrides;
- включен ли shell;
- как объединяются defaults и per-call options;
- какой capture/shutdown/terminal policy действует.

Runtime не должен заново угадывать эти правила.

### Runtime layer

Runtime layer исполняет уже нормализованный план. Его инварианты другие:

- stdout/stderr всегда дренируются;
- process handle закрывается;
- timeout supervision запускается один раз;
- shutdown policy применяется в правильном порядке;
- result собирается после завершения pumps.

Runtime не должен знать, как пользователь собирал builder.

### Transport layer

Transport layer изолирует OS-specific поведение:

- pipe launch;
- PTY launch;
- signal mapping;
- process-tree termination;
- platform quirks.

Transport failures переводятся в типизированные ошибки библиотеки. Raw platform details не должны протекать в
обычный public API.

## Категории инвариантов

### Command invariants

- executable обязателен;
- args сохраняют порядок;
- direct argv и shell command не смешиваются;
- env keys валидируются отдельно от env values;
- working directory — `Path`, а не строка;
- command echo безопасен для диагностики и не теряет quoting intent.

### Options invariants

- timeout не отрицательный;
- capture limits не отрицательные;
- charset задан явно для text capture;
- shutdown grace period согласован с total timeout;
- terminal required не должен silently fallback в pipes.

### I/O invariants

- stdout и stderr не блокируют друг друга;
- bounded capture всегда выставляет truncation flag;
- streaming не обязан хранить весь output;
- binary и text paths не смешиваются неявно;
- line decoder документирует newline normalization.

### Lifecycle invariants

- session имеет явные состояния;
- после close нельзя писать в stdin;
- `onExit` завершается ровно один раз;
- close idempotent;
- timeout и manual close не соревнуются без coordination owner.

### Concurrency invariants

- raw `Session` может иметь минимальные guarantees;
- `LineSession` сериализует request/response cycle;
- async API не должен обходить shutdown policy;
- cancellation должна попадать в тот же lifecycle path, что timeout.

### Error invariants

- non-zero exit — не то же самое, что launch failure;
- timeout — не то же самое, что interrupted caller;
- truncated output явно помечен;
- exception содержит structured data, а не только message;
- raw IOException не должна быть единственным public failure contract.

## Как получить широкие возможности без грязного API

Широта должна появляться через композицию:

```java
service.run(call -> call
        .args("run")
        .workingDirectory(dir)
        .putEnvironment("CI", "true")
        .input(CommandInput.utf8(payload))
        .capture(CapturePolicy.bounded(64 * 1024))
        .shutdown(ShutdownPolicy.interruptThenKill(
                Duration.ofSeconds(2),
                Duration.ofSeconds(5))));
```

Это широкий API по возможностям, но не широкий по сущностям. Пользователь комбинирует политики, а не выбирает из
десятков специализированных runners.

Terminal/PTТY остается частью внутреннего scenario profile до PTY-фазы. Его нельзя закреплять как публичный knob до
того, как появится transport SPI и проверенная platform matrix.

## Анти-паттерны

### Boolean soup

Плохо:

```java
options.mergeError(true).useShell(false).requireTty(true).killTree(true);
```

Лучше:

```java
options
        .stderr(StderrPolicy.mergeIntoStdout())
        .commandLine(CommandLine.direct())
        .shutdown(ShutdownPolicy.killProcessTree());
```

Не каждую boolean-настройку надо запрещать, но boolean не должен скрывать доменную модель.

### Validation in launcher

Launcher не должен быть мусоросборником всех проверок. Если launcher получает невозможную комбинацию, значит
resolution/domain layer пропустил инвариант.

### Public classes for every scenario

Не надо создавать `GitRunner`, `TailRunner`, `PooledLineConversation`, `ListenOnlySessionRunner` на раннем этапе.
Сначала нужны primitives, policies и examples.

### Half-valid objects

Объект public API не должен существовать в полувалидном состоянии. Полувалидность допустима только в builder/draft.

## Предпочтительная форма кода

### Immutable records and sealed policies

Для Java-кода предпочтительны:

- records для immutable data;
- sealed interfaces для policy families;
- package-private constructors для контролируемого создания;
- static factories с говорящими именами;
- small validators рядом с value object.

Пример направления:

```java
public sealed interface CapturePolicy permits BoundedCapture, StreamingCapture, DiscardCapture {
    static CapturePolicy bounded(int byteLimit) {
        return new BoundedCapture(ByteLimit.of(byteLimit));
    }
}
```

### Draft builders

Builders должны быть тонкими:

- собрать пользовательский ввод;
- не держать сложный runtime state;
- не исполнять process;
- возвращать immutable объект или передавать draft в resolver.

### Resolved plan

`ExecutionPlan` или аналог должен быть internal объектом. Это не пользовательская абстракция, а контракт между API
и runtime.

## Тестирование инвариантов

Каждый важный инвариант должен иметь один из видов проверки:

- unit test value object;
- resolver test;
- lifecycle test;
- fixture/eval scenario;
- compile-time API example.

Имена тестов должны отражать правило:

```text
CommandLineTest.rejectsShellAndDirectArgsMix
RunOptionsTest.rejectsNegativeTimeout
LineSessionTest.serializesConcurrentRequests
ProcessRuntimeTest.drainsStdoutAndStderrConcurrently
```

## Практический вывод

Да, API должен быть чистым и давать очень широкие возможности. Но широта должна идти не через разрастание public
классов, а через:

- сильные value objects;
- небольшие policy families;
- один resolver для сборки execution plan;
- узкие runtime ports;
- явные lifecycle state machines;
- тесты, закрепляющие каждый инвариант.

Это и должно быть главным архитектурным отличием новой версии от старой.
