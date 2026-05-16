# Контекст iCLI rewrite

## Назначение

Эта папка заменяет старую большую базу задач компактным набором источников правды для переписывания iCLI.
Контекст должен помогать реализации, а не становиться ежедневной бюрократией.

## Документы

- [architecture.md](architecture.md) — целевая архитектура первого MVP.
- [invariant-architecture.md](invariant-architecture.md) — изоляция инвариантов в API и runtime.
- [scenario-api.md](scenario-api.md) — сценарный API: пользователь выбирает workflow, а не flags.
- [api-ideas.md](api-ideas.md) — пользовательский API, который нужно сохранить.
- [legacy-lessons.md](legacy-lessons.md) — что берем и что не берем из старой версии.
- [development-model.md](development-model.md) — процесс разработки и работы агентов.
- [plans/maximal-version-roadmap.md](plans/maximal-version-roadmap.md) — верхнеуровневый план максимальной версии.
- [evals/process-behavior.md](evals/process-behavior.md) — обязательные behavior checks.
- [quality/engineering-charter.md](quality/engineering-charter.md) — обязательные требования к качеству проекта.
- [quality/scorecard.md](quality/scorecard.md) — текущая готовность и известные разрывы.
- [decisions/ADR-0001-clean-rewrite.md](decisions/ADR-0001-clean-rewrite.md) — решение о чистой ветке.
- [decisions/ADR-0002-java-baseline.md](decisions/ADR-0002-java-baseline.md) — замененное решение о Java 21 baseline.
- [decisions/ADR-0004-java-25-baseline.md](decisions/ADR-0004-java-25-baseline.md) — Java 25 baseline для clean rewrite.
- [decisions/ADR-0003-pty-packaging.md](decisions/ADR-0003-pty-packaging.md) — PTY не входит в core foundation.
- [decisions/ADR-0005-pty-transport.md](decisions/ADR-0005-pty-transport.md) — PTY transport через узкий provider.
- [decisions/ADR-0006-kotlin-module.md](decisions/ADR-0006-kotlin-module.md) — optional Kotlin ergonomics module.
- [decisions/ADR-0007-pooled-line-session.md](decisions/ADR-0007-pooled-line-session.md) — pooled line-session scenario.

## Правило навигации

Контекст — это карта, а не энциклопедия. Если правило повторяется, его лучше превратить в тест, скрипт или ADR.
Если документ становится слишком большим, раздели его по назначению:

- устойчивое решение — в `decisions/`;
- текущий план — в `plans/active/`;
- проверяемое ожидание — в `evals/`;
- статус качества — в `quality/`.

## Старые материалы

Старый проект остается в истории `main`. Его документы и код можно использовать как reference, но они не имеют
авторитета в этой ветке, если новое решение явно не переносит идею.
