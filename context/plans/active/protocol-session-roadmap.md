# План развития protocol sessions

## Цель

Добавить в iCLI недостающий слой для CLI-протоколов, где запрос не обязан быть одной строкой, а ответ завершается
правилом конкретного протокола. Изменение должно сохранить scenario-first API, единый process runtime и изоляцию
инвариантов.

## Порядок работ

1. Зафиксировать стабильную публикационную и compatibility основу: coordinates, Java 17 target, semantic versioning,
   API freeze-кандидаты.
2. Добавить strict charset decoding policy без удаления backward-compatible `REPLACE` поведения.
3. Добавить typed failure taxonomy для session/protocol/pool ошибок.
4. Добавить readiness probe после launch и до возврата session handle; для pool worker успешная readiness обязательна
   перед попаданием в idle.
5. Добавить один новый сценарий `protocolSession`, а framing/request/response варианты оформить как adapters.
6. Добавить typed pooled protocol session без раскрытия lease наружу.
7. Расширить pool metrics: latency, startup, состояния worker-ов и причины retirement.
8. Добавить request/response size limits как отдельные инварианты, а не как transcript retention.
9. Добавить optional integration adapters поверх существующего runtime: JSON Lines, delimiter, Content-Length и typed
   adapters.
10. Обновить public API baseline, документацию, release notes и проверки.

## Статус реализации

- Пункты 1-9 реализованы в core/integrations как scenario-first API поверх существующего runtime.
- Пункт 10 реализован: public docs, context, release docs и public API baseline обновлены; локальный
  `releaseCandidateCheck` на Java 17-targeted build прошел после переноса clean rewrite в `main`.
- Generic/core async request API осознанно оставлен за рамками текущего изменения.

## Явно не входит

- Generic/core async request API. Он может быть отдельным module/facade позже.
- Второй process engine для protocol adapters.
- Raw worker lease в public API.
