# Критерии оценки

Каждый кандидат оценивается по пяти осям. Оценка должна опираться на runnable scenarios, исходный код adapter layer,
документацию библиотеки и наблюдения о failure modes.

## Надежность

- Нет deadlock при большом stdout/stderr.
- Timeout не оставляет зависший child process.
- Non-zero exit не теряет diagnostics.
- Stdin закрывается предсказуемо.
- Interactive/streaming сценарии не перемешивают stdout/stderr и не теряют lifecycle.
- Platform-specific возможности явно unsupported или skipped, а не маскируются как success.

## Производительность

- Median latency для коротких one-shot runs.
- Throughput/total elapsed для parallel timeout churn.
- Время обработки large stdout/stderr.
- Runtime overhead adapter code: нужны ли дополнительные pumper threads, buffers, polling loops.

## Удобство API

- Насколько явно выражается сценарий пользователя.
- Можно ли задать timeout, env, cwd, stdin, stdout/stderr policy без ручной state machine.
- Насколько легко отличить timeout, non-zero exit, launch failure и protocol failure.
- Поддерживает ли библиотека long-lived session, expect, PTY, pooling или требует отдельной сборки.

## Качество документации

- Есть актуальный README/API docs.
- Есть примеры timeout, stdout/stderr, stdin, async/streaming.
- Документация описывает platform limitations.
- Видна активность поддержки и свежесть релизов.

## Пригодность для Procwright-задач

- Закрывает ли библиотека scenario-first API без утечки низкоуровневых flags.
- Позволяет ли изолировать инварианты в typed objects и state machines.
- Не тянет ли dependency, которая не нужна core.
- Может ли быть implementation detail под Procwright, не диктуя public API.

## Шкала

- `5` — закрывает сценарий напрямую, с понятной документацией и малыми рисками.
- `4` — закрывает сценарий, но требует небольшого adapter layer.
- `3` — работает для базового случая, но инварианты приходится достраивать вручную.
- `2` — частично применимо, есть существенные ограничения или слабая документация.
- `1` — не подходит для сценария или требует практически полного собственного runtime.
- `0` — сценарий не поддерживается.

В runnable report статус `MANUAL` означает, что сценарий можно собрать поверх библиотеки, но только через ручной
adapter/state-machine code, а не через готовую библиотечную абстракцию. Такой результат не равен `PASS` при оценке
удобства API.
