# ADR-0025: Бюджет гарантий runtime

## Статус

Принято, реализация выполняется.

Этот ADR задает целевое направление, а не преждевременно отменяет действующий контракт. Каждая гарантия перестает быть
обязательной только в одном атомарном изменении кода, тестов, public docs, `invariant-architecture.md` и proof map. До
такого изменения текущие scenario contracts и proofs остаются источником истины.

## Контекст

Простой scenario-first API скрывает runtime, который защищает пользователя от deadlock, unbounded memory, потерянного
process cleanup и неоднозначного protocol state. Эти гарантии определяют ценность Procwright.

При этом защита от враждебного кода внутри callbacks и continuations породила отдельные thread owners, admission
partitions, publication reservations и протоколы агрегации ошибок. Их взаимодействия увеличивают state space самого
runtime и создают больший риск дефектов, чем устраняют для обычного пользователя библиотеки.

Размер кода не является самостоятельной метрикой. Упрощение считается полезным, только если сокращает число состояний,
владельцев и переходов, необходимых для объяснения пользовательского сценария.

## Обязательные гарантии

Procwright сохраняет:

- единоличное владение stdin/stdout/stderr и параллельный drain output streams;
- bounds для capture, transcript, backlog, request, response и выполняющихся либо зависших callbacks;
- timeout, восстановление interrupt status, идемпотентный close и продолжение cleanup после caller timeout;
- best-effort остановку известного process tree;
- один канонический terminal outcome без замены поздним событием;
- сериализацию request/response и retirement worker-а после неоднозначного I/O;
- request-scoped protocol capabilities;
- defensive copies public completion futures, чтобы caller cancellation или completion не меняли lifecycle;
- typed scenario failures, exit metadata и bounded diagnostics.

PTY, Expect, streaming, readiness, protocol adapters и pool policies сохраняют свои safety-инварианты, пока остаются
поддерживаемыми публичными сценариями.

## Гарантии, которые больше не являются целью

Runtime не обязан гарантировать:

- отдельный fresh non-inheriting thread для каждого callback;
- независимую admission partition для каждого вида callback;
- доставку ошибки, брошенной callback после его abandonment, через JVM uncaught-exception handler;
- специальную identity-дедупликацию и неизменность исходного графа `Throwable` при нескольких cleanup failures;
- продвижение позднего `Error` в особый aggregate после выбора terminal outcome;
- отдельный заранее запущенный publication owner для каждого lifecycle future;
- process-global worker quota, разделяемую независимыми pools или direct sessions;
- FIFO или fairness между конкурирующими internal cleanup/reporting задачами.

Hard capacity, отсутствие выполнения пользовательского continuation под state monitor и освобождение resource permits
только после фактического завершения операции остаются обязательными.

При нескольких ошибках сохраняется канонический outcome. Дополнительные ошибки могут быть обычными suppressed
exceptions или bounded diagnostics; точная форма их графа не является API-контрактом.

## Направление реализации

1. Удалить pass-through abstractions, которые не владеют состоянием или правилом.
2. Свести timed callbacks к одному понятному bounded execution protocol.
3. Упростить lifecycle publication без выполнения continuations на process watcher или под state monitor.
4. Упростить session cleanup, сохранив output ownership и terminal barrier.
5. Pool terminal reservation удалена; далее сократить pool coordinators вокруг одного state owner.
6. После каждого шага обновлять public Javadoc/docs одновременно с изменением наблюдаемого поведения.

Line/protocol framing и scenario-first Draft API не объединяются в универсальный набор flags. Упрощение internal
runtime не должно ухудшать пользовательский язык библиотеки.

## Проверка

Каждый шаг обязан сохранить public API baseline и пройти релевантные unit, integration и bounded stress tests.
Изменение гарантии сопровождается удалением теста старого внутреннего обещания и проверкой остающегося пользовательского
контракта.

После существенного шага независимые проверки отвечают на два разных вопроса:

1. Сохранены ли обязательные пользовательские гарантии?
2. Уменьшилось ли число понятий и переходов, а не только число файлов или строк?

Performance и allocation baseline сравниваются для затронутого сценария; машинозависимое число не становится
публичной гарантией.
