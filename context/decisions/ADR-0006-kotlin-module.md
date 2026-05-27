# ADR-0006: Optional Kotlin ergonomics module

## Статус

Принято.

## Контекст

Kotlin users должны получать idiomatic API: receiver-style builders, Kotlin duration overloads, suspending wrappers,
Flow adapters и небольшие scenario-scoped DSL scopes. При этом core artifact должен оставаться Java library без Kotlin
runtime, coroutines и Kotlin compiler dependency.

## Решение

Добавить отдельный Gradle subproject `:procwright-kotlin`.

Состав module:

- `runCommand { ... }` как receiver-style extension над `CommandService.run(...)`;
- `runCommandAwait { ... }` как coroutine-friendly wrapper;
- `openSession { ... }` и `Session.awaitExit()`;
- `LineSession.requestAwait(...)`;
- `StreamSession.awaitExit()`;
- Kotlin `Duration` overloads для Kotlin-facing helpers и DSL scopes;
- `listenFlow { ... }` как `Flow<StreamChunk>` adapter с отдельным `ListenFlowInvocation`, который не дает caller
  заменить internal listener;
- `pooledLineSession { worker { ... } ... }` как scenario-scoped DSL поверх существующего pooled line-session runtime;
- `protocolAdapter { writeRequest { ... } readResponse { ... } }` как Kotlin builder для core `ProtocolAdapter`.

Java core остается root project и не зависит от Kotlin. Kotlin module зависит от core и `kotlinx-coroutines-core`.
Kotlin-friendly nullability задается сигнатурами extension API. Аннотации nullability в Java core не
добавляются, чтобы не вносить новую dependency в core artifact до отдельного решения.

`Session.awaitExit()` и `StreamSession.awaitExit()` не отменяют общий `onExit()` future при cancellation ожидающей
coroutine. Отмена Kotlin awaiter — это detach ожидающего caller, а не lifecycle event процесса.

`listenFlow` не является best-effort drop adapter: output callback блокируется на отправке в rendezvous Flow channel.
Это сохраняет bounded backpressure model `listen` для активного collector. Если collector отменяет Flow, adapter
закрывает underlying `StreamSession`.

Kotlin DSL scopes не должны становиться вторым dialect поверх Java core: они начинаются с явного сценария, не запускают
процессы напрямую, не раскрывают worker leases и используют существующие core adapters/session runtimes.

## Последствия

Плюсы:

- Kotlin API можно развивать без утяжеления Java core;
- Flow/coroutine ergonomics живут там, где естественно иметь Kotlin dependency;
- Kotlin DSL снижает boilerplate для вложенных worker/pool и protocol-adapter сценариев без нового runtime;
- Kotlin examples становятся compile-tested tests.

Минусы:

- Сборка требует Kotlin Gradle plugin и coroutines dependency;
- Kotlin module имеет собственную compatibility matrix;
- Flow cancellation intentionally closes underlying process session.
