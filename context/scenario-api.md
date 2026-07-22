# Scenario-first пользовательский API

## Позиция

Procwright описывает намерение пользователя, а не конфигурацию `ProcessBuilder`. API имеет одну грамматику:

```text
Procwright.command(command)
  -> выбрать scenario
  -> уточнить immutable Draft через with*
  -> явно вызвать execute/open
  -> получить typed result или AutoCloseable handle
```

Корневая команда reusable. Scenario Draft persistent: его можно ветвить, повторно использовать и вызывать terminal
конкурентно. Каждый terminal call создает независимый execution/handle.

## Базовая команда

Короткая форма:

```java
CommandService git = Procwright.command("git");
```

Явный reusable launch context:

```java
CommandSpec command = CommandSpec.of("git")
        .withWorkingDirectory(repository)
        .withEnvironment("LC_ALL", "C");

CommandService git = Procwright.command(command);
```

`CommandSpec` не содержит timeout, capture или protocol settings: они получают смысл только после выбора сценария.

## `run`

Конечная команда с bounded result:

```java
CommandResult result = git.run()
        .withArgs("status", "--short")
        .withTimeout(Duration.ofSeconds(5))
        .withCapture(CapturePolicy.bounded(64 * 1024))
        .execute();
```

`RunScenario.Draft` предлагает launch settings, input, capture, output mode, charset/decoding, timeout, shutdown и
diagnostics. `execute()` возвращает exit code, stdout/stderr bytes/text, timeout и truncation metadata. Non-zero exit —
результат процесса; launch/supervision/decode failure — typed exception с доступным result snapshot, когда он есть.

## `interactive`

Raw process handle для caller-owned protocol:

```java
try (Session session = Procwright.command("python")
        .interactive()
        .withArgs("-u", "-i")
        .withIdleTimeout(Duration.ofMinutes(1))
        .open()) {
    session.sendLine("print(6 * 7)");
    session.closeStdin();
}
```

`InteractiveScenario.Draft` предлагает session charset, idle timeout, readiness, terminal capability, shutdown и
diagnostics. Получение raw stdout/stderr wrapper не выбирает режим: его выбирает первая фактическая stream operation либо
`Expect.Draft.open()`. Смешивание режимов отклоняется через `IllegalStateException`.

## `Session.expect()`

Prompt automation создается в два явных шага:

```java
try (Session session = Procwright.command("python").interactive().withArg("-i").open();
        Expect expect = session.expect()
                .withTimeout(Duration.ofSeconds(2))
                .withTranscriptLimit(8 * 1024)
                .open()) {
    expect.expectRegex(Pattern.compile("Python .*"));
    expect.sendLine("print(6 * 7)");
    expect.expectText("42");
}
```

`Session.expect()` только создает неизменяемый `Expect.Draft`; каждый `with*` возвращает новую ветку. `open()` атомарно
захватывает output ownership. Закрытие `Expect` закрывает underlying `Session` и не возвращает raw streams caller. Values,
отправленные через helper, редактируются в transcript по умолчанию.

## `lineSession`

Один line request — один декодированный response:

```java
try (LineSession worker = Procwright.command("line-worker")
        .lineSession()
        .withRequestTimeout(Duration.ofSeconds(2))
        .withMaxResponseLines(100)
        .open()) {
    LineResponse response = worker.request("status");
}
```

`LineSessionScenario.Draft` предлагает request/idle timeout, readiness, charset policy, response decoder, request
byte/char limits, response line/char limits, pending line/char backlog, maximum unfinished line, terminal capability и
diagnostics. Request cycle сериализован. Validation, size, encoding и wait failure сохраняют session, если request не был
передан writer-у и не сможет записаться позже. После передачи writer-у timeout, interruption или write failure закрывает
session, даже если первый byte не подтвержден. EOF, decode и framing failure также закрывают session.

## `protocolSession`

Typed workflow для delimiter/content-length, multi-line, byte или произвольного request/response protocol:

```java
Supplier<ProtocolAdapter<Request, Response>> adapters = WorkerAdapter::new;

try (ProtocolSession<Request, Response> worker = Procwright.command("worker")
        .protocolSession(adapters)
        .withRequestTimeout(Duration.ofSeconds(2))
        .withMaxRequestBytes(64 * 1024)
        .withMaxResponseBytes(256 * 1024)
        .open()) {
    Response response = worker.request(new Request("payload"));
}
```

Factory вызывается отдельно для каждого session и pool worker. Она выполняется до process launch; `null` или failure
не оставляют процесс. Concurrent `open()` могут вызывать factory одновременно, поэтому она должна быть thread-safe и
возвращать свежий adapter. Один instance adapter не принимается, потому что его mutable framing state нельзя безопасно
разделить между sessions.

Adapter записывает request через `ProtocolWriter` и читает ответ через deadline-aware `ProtocolReaders`. Runtime
владеет lifecycle, serialization, byte/char limits, backlog, strict/replace decoding, transcript и typed failures.

## `listen`

Потоковая обработка без full-output retention:

```java
try (StreamSession stream = Procwright.command("tail")
        .listen()
        .withArgs("-f", log.toString())
        .onOutput(chunk -> consume(chunk.source(), chunk.text()))
        .withTimeout(Duration.ofMinutes(10))
        .open()) {
    StreamExit exit = stream.onExit().join();
}
```

`StreamScenario.Draft` предлагает output listener, absolute timeout, charset, bounded diagnostics, shutdown и diagnostic
hooks. Listener вызовы сериализованы; медленный listener создает backpressure. `listen` всегда закрывает stdin при
старте. Если caller должен писать в stdin, используется `interactive()`.

## Line pool

Worker settings задаются до `pooled()`, pool settings — после:

```java
LineSessionScenario.Draft worker = Procwright.command("line-worker")
        .lineSession()
        .withRequestTimeout(Duration.ofSeconds(2));

try (PooledLineSession pool = worker.pooled()
        .withMaxSize(4)
        .withWarmupSize(1)
        .withMinIdle(1)
        .withAcquireTimeout(Duration.ofSeconds(1))
        .open()) {
    LineResponse response = pool.request("status");
}
```

`pooled()` не открывает ресурсов. `PoolDraft.open()` создает pool и выполняет synchronous warmup. Pool не раскрывает
lease: каждый `request` сам получает worker, выполняет cycle, запускает reset/health policy и возвращает либо retire-ит
worker.

## Protocol pool

```java
try (PooledProtocolSession<Request, Response> pool = Procwright.command("worker")
        .protocolSession(WorkerAdapter::new)
        .withRequestTimeout(Duration.ofSeconds(2))
        .pooled()
        .withMaxSize(4)
        .withMaxRequestsPerWorker(1_000)
        .open()) {
    Response response = pool.request(new Request("payload"));
}
```

Каждый worker получает собственный adapter из той же factory. Pool и direct protocol session используют один runtime и
одну failure taxonomy.

## Readiness

Session-family Draft принимает scenario-typed readiness probe:

```java
LineSessionScenario.Draft readyWorker = Procwright.command("worker")
        .lineSession()
        .withReadiness(session -> session.request("ping"))
        .withReadinessTimeout(Duration.ofSeconds(3));
```

Probe выполняется после launch и до возврата handle. В pool worker не становится idle до успешного readiness. Failure
закрывает worker; partial warmup failure закрывает уже созданные workers.

## Kotlin

Kotlin сохраняет ту же последовательность:

```kotlin
val result = Procwright.command("git")
    .run()
    .withArgs("status", "--short")
    .withTimeout(5.seconds)
    .executeAwait()
```

`openFlow()` возвращает cold Flow: процесс не запускается до collection, а каждая collection владеет отдельной
`StreamSession`. `protocolAdapterFactory { ... }` возвращает Java `Supplier`, создающий отдельный adapter wrapper для
каждого session/worker. Kotlin module не добавляет mutable scenario scopes или terminal configuration lambdas.

## Правила расширения

- Настройка появляется только у сценария, где имеет однозначную семантику.
- Новый terminal method требует отдельного ownership/lifecycle contract.
- Новый pool доступен только как nested branch конкретного reusable session scenario.
- Public helper не может читать streams без exclusive ownership.
- Новый adapter/helper использует существующий runtime и остается optional, если требует внешнюю dependency.
- Public surface, examples и context меняются вместе с executable proof.
