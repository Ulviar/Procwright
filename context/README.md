# Контекст iCLI rewrite

## Назначение

Эта папка заменяет старую большую базу задач компактным набором источников правды для переписывания iCLI.
Контекст должен помогать реализации, а не становиться ежедневной бюрократией.

## Документы

- [architecture.md](architecture.md) — целевая архитектура первого MVP.
- [invariant-architecture.md](invariant-architecture.md) — изоляция инвариантов в API и runtime.
- [scenario-api.md](scenario-api.md) — сценарный API: пользователь выбирает workflow, а не flags.
- [scenario-contracts.md](scenario-contracts.md) — публичные контракты канонических сценариев.
- [scenario-cookbook.md](scenario-cookbook.md) — практические рецепты выбора сценария, привязанные к compile-tested examples.
- [pty-capability-boundary.md](pty-capability-boundary.md) — PTY как capability внутри session-family сценариев.
- [external-library-boundary.md](external-library-boundary.md) — внешние process-библиотеки только как research boundary.
- [diagnostics.md](diagnostics.md) — наблюдательный diagnostics contract и event schema.
- [api-ideas.md](api-ideas.md) — пользовательский API, который нужно сохранить.
- [legacy-lessons.md](legacy-lessons.md) — что берем и что не берем из старой версии.
- [development-model.md](development-model.md) — процесс разработки и работы агентов.
- [plans/maximal-version-roadmap.md](plans/maximal-version-roadmap.md) — верхнеуровневый план максимальной версии.
- [plans/active/documentation-writing-plan.md](plans/active/documentation-writing-plan.md) — план написания публичной
  документации.
- [plans/active/release-stabilization-plan.md](plans/active/release-stabilization-plan.md) — план стабилизации перед
  первым release candidate.
- [plans/active/protocol-session-roadmap.md](plans/active/protocol-session-roadmap.md) — roadmap stable artifact,
  protocol sessions, readiness probes, typed pools и adapter helpers.
- [evals/process-behavior.md](evals/process-behavior.md) — обязательные behavior checks.
- [evals/test-cli-simulator.md](evals/test-cli-simulator.md) — отдельный test CLI для моделирования проблем реальных
  дочерних процессов.
- [evals/test-tiers.md](evals/test-tiers.md) — уровни локальных проверок и eval gate.
- [quality/engineering-charter.md](quality/engineering-charter.md) — обязательные требования к качеству проекта.
- [quality/scorecard.md](quality/scorecard.md) — текущая готовность и известные разрывы.
- [quality/invariant-proof-map.md](quality/invariant-proof-map.md) — карта `invariant -> owner -> proof`.
- [release/versioning-policy.md](release/versioning-policy.md) — политика версий.
- [release/compatibility-policy.md](release/compatibility-policy.md) — совместимость и platform support.
- [release/public-api-baseline.md](release/public-api-baseline.md) — intended public surface и API guard перед первым RC.
- [release/dependency-review.md](release/dependency-review.md) — обзор runtime/build dependencies.
- [release/release-checklist.md](release/release-checklist.md) — checklist первого OSS release candidate.
- [release/migration-notes.md](release/migration-notes.md) — migration notes из старой iCLI.
- [release/release-notes-template.md](release/release-notes-template.md) — template публичных release notes.
- [comparison/README.md](comparison/README.md) — исследовательское сравнение process-библиотек по сценариям iCLI.
- [audits/standing-auditor-instructions.md](audits/standing-auditor-instructions.md) — постоянные роли независимого
  аудита.
- [audits/step-audit-protocol.md](audits/step-audit-protocol.md) — протокол пошагового аудита изменений.
- [audits/global-architecture-audit-2026-05-18.md](audits/global-architecture-audit-2026-05-18.md) — глобальный
  архитектурный аудит текущего среза от верхних границ до классов.
- [audits/deep-component-class-audit-2026-05-18.md](audits/deep-component-class-audit-2026-05-18.md) — глубокий аудит
  компонентов и классов, тестового покрытия, инвариантов, SOLID и GRASP.
- [audits/audit-finding-remediation-2026-05-18.md](audits/audit-finding-remediation-2026-05-18.md) — отчет о закрытии
  findings двух архитектурных аудитов.
- [audits/public-api-freeze-audit-2026-05-18.md](audits/public-api-freeze-audit-2026-05-18.md) — аудит public API перед
  release-candidate freeze.
- [audits/usability-audit-open-source-process-usage-2026-05-20.md](audits/usability-audit-open-source-process-usage-2026-05-20.md)
  — масштабный аудит удобства iCLI по реальным open-source сценариям использования `ProcessBuilder` и библиотек-аналогов.
- [decisions/ADR-0001-clean-rewrite.md](decisions/ADR-0001-clean-rewrite.md) — решение о чистой ветке.
- [decisions/ADR-0002-java-baseline.md](decisions/ADR-0002-java-baseline.md) — замененное решение о Java 21 baseline.
- [decisions/ADR-0004-java-25-baseline.md](decisions/ADR-0004-java-25-baseline.md) — историческое решение о Java 25
  development baseline.
- [decisions/ADR-0003-pty-packaging.md](decisions/ADR-0003-pty-packaging.md) — PTY не входит в core foundation.
- [decisions/ADR-0005-pty-transport.md](decisions/ADR-0005-pty-transport.md) — PTY transport через узкий provider.
- [decisions/ADR-0006-kotlin-module.md](decisions/ADR-0006-kotlin-module.md) — optional Kotlin ergonomics module.
- [decisions/ADR-0007-pooled-line-session.md](decisions/ADR-0007-pooled-line-session.md) — pooled line-session scenario.
- [decisions/ADR-0008-scenario-presets.md](decisions/ADR-0008-scenario-presets.md) — scenario presets как typed builder customizers.
- [decisions/ADR-0009-cli-backed-integrations.md](decisions/ADR-0009-cli-backed-integrations.md) — optional CLI-backed integrations module.
- [decisions/ADR-0010-stress-suite.md](decisions/ADR-0010-stress-suite.md) — bounded stress suite как release gate.
- [decisions/ADR-0011-release-hardening.md](decisions/ADR-0011-release-hardening.md) — release hardening перед первым OSS-кандидатом.
- [decisions/ADR-0012-scenario-first-after-library-comparison.md](decisions/ADR-0012-scenario-first-after-library-comparison.md)
  — сохранение scenario-first API после сравнения process-библиотек.
- [decisions/ADR-0013-documentation-toolchain.md](decisions/ADR-0013-documentation-toolchain.md) — публичная
  документация, MkDocs Material и граница между `docs/` и `context/`.
- [decisions/ADR-0014-package-architecture.md](decisions/ADR-0014-package-architecture.md) — пакетная архитектура
  ядра и границы public/internal API.
- [decisions/ADR-0015-jpms-encapsulation.md](decisions/ADR-0015-jpms-encapsulation.md) — JPMS-инкапсуляция core и
  разделение public session contracts / internal implementations.
- [decisions/ADR-0016-first-rc-api-stabilization.md](decisions/ADR-0016-first-rc-api-stabilization.md) — стабилизация
  public API перед первым release candidate.
- [decisions/ADR-0017-release-publishing-strategy.md](decisions/ADR-0017-release-publishing-strategy.md) — стратегия
  публикации release artifacts.
- [decisions/ADR-0018-platform-pty-strategy.md](decisions/ADR-0018-platform-pty-strategy.md) — platform и PTY strategy
  перед первым release candidate.
- [decisions/ADR-0019-kotlin-generated-docs-strategy.md](decisions/ADR-0019-kotlin-generated-docs-strategy.md) — решение
  по generated Kotlin docs.
- [decisions/ADR-0020-java-release-variants.md](decisions/ADR-0020-java-release-variants.md) — один source tree и
  проверяемые Java 17/21/25 release variants.

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
