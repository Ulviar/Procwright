# Независимый аудит stress-сравнения

## Проверенная область

Отчет подготовлен по raw results из `context/comparison/stress-results.md` и реализации stress runner-а
`icli-comparison/src/main/java/com/github/ulviar/icli/comparison/StressComparisonRunner.java`.

Сравнивались пять кандидатов: iCLI rewrite, JDK ProcessBuilder, Apache Commons Exec, ZeroTurnaround zt-exec и
NuProcess. Сценарии построены поверх `:icli-test-cli`:

- `ST01`: параллельный большой stdout/stderr с bounded capture.
- `ST02`: deterministic flaky mix с ожидаемыми success/failure.
- `ST03`: параллельный timeout churn для зависающих процессов.
- `ST04`: timeout должен остановить parent и spawned child.
- `ST05`: pooled line-session со смешанными успехами и request timeouts.

## Итог по надежности

По raw data iCLI проходит все проверенные сценарии: `ST01` 12/12, `ST02` 40/40, `ST03` 16/16, `ST04` 1/1,
`ST05` 10/10. Это не означает полной доказанности runtime, но в данном наборе stress-сценариев iCLI единственный
кандидат без failure/unsupported статуса.

JDK ProcessBuilder, Apache Commons Exec, ZeroTurnaround zt-exec и NuProcess проходят базовые one-shot stress-сценарии
`ST01`-`ST03`. Это важный результат: при bounded capture, deterministic flaky exit handling и churn timeout все четыре
альтернативы показали 100% pass rate в данных прогонах.

Провал нельзя сглаживать: в `ST04` все четыре не-iCLI кандидата получили `FAIL 0/1`, потому что spawned child остался
жив (`childStopped=false`). Parent был остановлен или timeout был зафиксирован, но требуемый invariant был шире:
timeout должен убрать не только непосредственный процесс, но и порожденного ребенка. По этому критерию:

- JDK ProcessBuilder: `FAIL`, `childStopped=false`, timeout result с exit `137`.
- Apache Commons Exec: `FAIL`, `childStopped=false`, watchdog killed process, exit `143`.
- ZeroTurnaround zt-exec: `FAIL`, `childStopped=false`, timeout exception, exit absent.
- NuProcess: `FAIL`, `childStopped=false`, process still running after timeout, exit `9`.
- iCLI rewrite: `PASS`, `childStopped=true`, timeout result, exit `143`.

`ST05` не является failure для остальных библиотек в узком смысле, потому что runner помечает их как `UNSUPPORTED`, а не
как failed execution. Но для пользовательского сценария pooled line-session это все равно существенное различие scope:
iCLI дает готовую абстракцию пула line-session с request timeout и метриками, остальные кандидаты требуют самостоятельной
реализации пула, протокольной state machine, lease lifecycle, timeout recovery и retirement зависших sessions.

## Удобство написания harness-кода

iCLI в stress runner-е используется как scenario-first API: пользователь задает command scenario, capture policy,
timeout и output mode, а не собирает pump threads, watchdogs и cleanup вручную. Для pooled line-session runner использует
готовый `PooledLineSession`, request-level timeout и metrics. Это прямо соответствует целевому API проекта: инварианты
timeout, bounded output, cleanup и session lifecycle находятся в библиотеке.

JDK ProcessBuilder является самым низкоуровневым baseline. Для one-shot исполнения runner вынужден вручную создавать
процесс, писать stdin, запускать отдельные stdout/stderr pumps, ждать timeout, форсировать destroy, ждать pumps и
разбирать частичные результаты. Для интерактивных сценариев требуется ручной protocol loop. Это гибко, но объем
harness-кода и количество мест для ошибок максимальные.

Apache Commons Exec и zt-exec заметно удобнее JDK для one-shot исполнения: есть готовые watchdog/timeout и stream
handler/redirect API. Но bounded capture, normalized outcome, stderr/stdout policy и особенно process-tree cleanup все
равно остаются ответственностью harness-а или дополнительного слоя. Эти библиотеки хорошо закрывают запуск процесса,
но не дают scenario-first model уровня iCLI.

NuProcess решает другую задачу: non-blocking native process I/O. Его API полезен, когда нужна callback/event модель, но
для сценариев из этого stress runner-а он требует собственного handler-а, ByteBuffer handling, stdin lifecycle и
нормализации результата. По текущей raw table он проходит `ST01`-`ST03`, но в `ST01` остается заметно медленнее
остальных кандидатов по median ms. Это не универсальный вывод о производительности NuProcess, а только наблюдение по
данному workload и adapter-у.

`ST05` особенно показателен для удобства API: для iCLI это прямой сценарий, для остальных это не отсутствие одной
функции, а отсутствие целого набора пользовательских инвариантов: pooling, acquire timeout, per-request timeout,
retirement broken session, accounting metrics и протокольная изоляция запросов.

## Ограничения и unfair места сравнения

Сравнение не является универсальным benchmark всех библиотек. Raw results выглядят как один набор прогонов без
документированных параметров машины, ОС, JDK, load profile, warmup/retry policy и доверительных интервалов. Median/total
ms полезны как smoke-сигнал, но не должны использоваться как строгий performance ranking.

Runner сравнивает adapter-ы, написанные в этом проекте, а не все возможные production-grade способы использования
каждой библиотеки. Для JDK, Commons Exec, zt-exec и NuProcess можно написать дополнительный custom layer, который будет
убивать process tree, вести пул sessions или нормализовать diagnostics. Тогда сравнение сместится с "library API"
на "library плюс собственный runtime поверх нее".

`ST04` одновременно справедлив и unfair. Он справедлив как проверка invariant-а iCLI: timeout в user-facing API должен
не оставлять spawned child. Он unfair как оценка библиотек, которые не заявляют такую high-level гарантию по умолчанию.
Поэтому результат следует читать так: не-iCLI кандидаты не дают этот invariant из коробки в данном adapter-е, а не как
доказательство, что на них невозможно построить такой cleanup.

`ST05` еще сильнее отличается по scope. Pooled line-session - это доменная абстракция iCLI, а не базовая обязанность
ProcessBuilder, Commons Exec, zt-exec или NuProcess. Поэтому `UNSUPPORTED` не должно считаться runtime failure этих
библиотек. Но для продукта iCLI это релевантное конкурентное различие: пользовательский сценарий доступен без ручного
pool/protocol runtime.

Сами сценарии построены поверх `:icli-test-cli`, что хорошо для детерминированности, но сужает пространство проверок.
Нет данных по Windows/POSIX различиям, очень большим long-running процессам, реальным shell trees, медленному consumer-у
stdout/stderr, repeated stress loops, resource leak checks после многих минут работы и конкуренции с другими системными
нагрузками.

## Сравнительная таблица

| Кандидат | Надежность по raw data | Harness/API ergonomics | Scope caveat |
| --- | --- | --- | --- |
| iCLI rewrite | `PASS` во всех сценариях: `79/79` учитываемых attempts | Scenario-first API: bounded capture, timeout, separate output, process-tree cleanup и pooled line-session доступны как библиотечные сценарии | Проверяется собственная целевая область iCLI; результаты не доказывают все edge cases вне этих stress-сценариев |
| JDK ProcessBuilder | `PASS` в `ST01`-`ST03`, `FAIL` в `ST04`, `UNSUPPORTED` в `ST05` | Максимум ручного harness-а: pumps, stdin close, timeout, destroy, result normalization, interactive protocol | Низкоуровневый JDK baseline; дополнительные гарантии возможны только через custom layer |
| Apache Commons Exec | `PASS` в `ST01`-`ST03`, `FAIL` в `ST04`, `UNSUPPORTED` в `ST05` | Удобный one-shot runner с watchdog и stream handler, но bounded capture/result semantics/session pooling остаются вне core API | Хорошо сравним для one-shot process execution, хуже для iCLI session abstractions |
| ZeroTurnaround zt-exec | `PASS` в `ST01`-`ST03`, `FAIL` в `ST04`, `UNSUPPORTED` в `ST05` | Самый близкий к fluent one-shot API среди альтернатив, но process-tree cleanup и pooled sessions не закрыты | Сравнение fair для one-shot timeout/capture, unfair для pooled line-session |
| NuProcess | `PASS` в `ST01`-`ST03`, `FAIL` в `ST04`, `UNSUPPORTED` в `ST05`; медленнее в `ST01` по этому прогону | Требует callback handler, ByteBuffer handling и lifecycle normalization; удобен для non-blocking I/O, не для scenario-first API | Библиотека другого уровня абстракции; raw latency не является общим performance verdict |

## Короткий вывод

По представленным raw results iCLI выглядит наиболее надежным решением именно для заявленного scope проекта: безопасный
one-shot execution с bounded output/timeout cleanup и готовые session-oriented сценарии. Альтернативы убедительно
проходят базовые one-shot stress cases, но не закрывают process-tree cleanup в `ST04` и не предоставляют pooled
line-session abstraction в `ST05` без существенного пользовательского runtime поверх библиотеки.

Главный риск интерпретации: не превращать этот отчет в утверждение, что iCLI "быстрее" или "всегда надежнее" всех
кандидатов. Корректный вывод уже и сильнее, и точнее: на проверенных сценариях iCLI переносит критичные инварианты из
ручного harness-кода в библиотечный scenario-first API, а raw failures показывают, что эти инварианты не являются
бесплатным свойством обычных process libraries.
