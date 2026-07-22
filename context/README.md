# Контекст Procwright

## Назначение

`context/` содержит только текущие источники правды для Procwright. Это не архив, не журнал работы и не место
для raw research output. Исторические данные восстанавливаются из Git history, если они когда-нибудь понадобятся.

Документ остается в `context/`, только если он помогает принять текущее решение, удерживает архитектурную границу,
описывает проверяемый инвариант или задает release policy.

## Активная карта

- [architecture.md](architecture.md) — текущие слои, модули и границы runtime.
- [invariant-architecture.md](invariant-architecture.md) — как изолируются инварианты API и runtime.
- [scenario-api.md](scenario-api.md) — scenario-first пользовательский язык.
- [scenario-contracts.md](scenario-contracts.md) — контракты реализованных сценариев.
- [scenario-cookbook.md](scenario-cookbook.md) — краткая карта выбора сценария для maintainers.
- [api-ideas.md](api-ideas.md) — критерии осмысленного расширения API.
- [development-model.md](development-model.md) — легкий процесс работы и контекстная гигиена.
- [diagnostics.md](diagnostics.md) — diagnostics contract.
- [pty-capability-boundary.md](pty-capability-boundary.md) — PTY как capability boundary.
- [external-library-boundary.md](external-library-boundary.md) — граница внешних process-библиотек.

## Проверки и качество

- [evals/process-behavior.md](evals/process-behavior.md) — обязательные process behavior checks.
- [evals/test-cli-simulator.md](evals/test-cli-simulator.md) — назначение `:procwright-test-cli`.
- [evals/test-tiers.md](evals/test-tiers.md) — уровни локальных проверок.
- [quality/engineering-charter.md](quality/engineering-charter.md) — обязательный стандарт качества.
- [quality/scorecard.md](quality/scorecard.md) — текущая готовность и known gaps.
- [quality/invariant-proof-map.md](quality/invariant-proof-map.md) — `invariant -> owner -> proof`.

## Release context

- [release/versioning-policy.md](release/versioning-policy.md) — политика версий.
- [release/compatibility-policy.md](release/compatibility-policy.md) — совместимость и platform support.
- [release/public-api-baseline.md](release/public-api-baseline.md) — intended public surface и API guard.
- [release/dependency-review.md](release/dependency-review.md) — runtime/build dependency boundary.
- [release/release-checklist.md](release/release-checklist.md) — checklist первого OSS release.

## Решения и research boundary

- [decisions/README.md](decisions/README.md) — ADR для устойчивых архитектурных решений.
- [comparison/README.md](comparison/README.md) — только воспроизводимая методика сравнения process-библиотек, без
  исторических raw reports.
- `audits/` — только постоянные инструкции и протокол аудита, без закрытых отчетов.

## Правила очистки

- Не хранить исторические данные в `context/`: закрытые планы, raw benchmark results, subagent reports, audit reports,
  transcripts и одноразовые инструкции удаляются после того, как полезные выводы перенесены в текущий источник истины.
- Не создавать `archive/` внутри `context/`. Архивом является Git history.
- Если отчет породил решение, решение оформляется в ADR. Если отчет породил правило, правило переносится в тест,
  validator, release checklist или engineering charter.
- Активный план допускается только на время длинной работы. После завершения он удаляется или сжимается в ADR/scorecard.
- `context/README.md` перечисляет только активные источники истины, а не все файлы дерева.

## Анти-мусорное правило

Документ не должен объяснять, почему текущий проект отличается от прошлой итерации. Если правило важно сейчас, оно
формулируется как текущий инвариант, test, ADR или release policy. Если текст нужен только как историческая справка, он
не должен оставаться в `context/`.
