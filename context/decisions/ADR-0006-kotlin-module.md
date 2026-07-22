# ADR-0006: Optional Kotlin ergonomics module

## Статус

Принято.

## Контекст

Kotlin API должен использовать `kotlin.time.Duration`, coroutines и Flow, не добавляя Kotlin runtime в Java core и не
создавая второй mutable DSL поверх Java API.

## Решение

`:procwright-kotlin` расширяет те же Java scenario Draft и session handles:

- duration overloads возвращают новый persistent Draft;
- `RunScenario.Draft.executeAwait()` выполняет one-shot call вне caller coroutine;
- `requestAwait(...)` поддерживает direct и pooled line/protocol sessions;
- `Session.awaitExit()` и `StreamSession.awaitExit()` отменяют только waiter, не общий exit future;
- `StreamScenario.Draft.openFlow()` возвращает cold Flow: каждая collection открывает и закрывает собственную session;
- `protocolAdapterFactory { ... }` создает новый adapter wrapper на каждый `Supplier.get()`.

Отмена direct request закрывает session с недостоверным framing state. Отмена active pooled request retire-ит worker;
отмена acquire wait не затрагивает worker. Flow использует rendezvous delivery и сохраняет backpressure.

Публичный `openAwait()` не добавляется без доказанной передачи ownership при гонке startup/cancellation. Mutable
scenario scopes, terminal configuration lambdas и отдельный pool DSL отсутствуют.

## Последствия

Java core не зависит от Kotlin. Kotlin module имеет собственные KDoc, ABI, cancellation и external consumer gates.
Удобство Kotlin растет через extensions над одним Java dialect, а не через параллельную модель конфигурации.
