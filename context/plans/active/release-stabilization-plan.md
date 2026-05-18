# План стабилизации перед release candidate

## Статус

Completed for the current stabilization pass on 2026-05-18.

## Цель

Закрыть оставшиеся решения перед первым публичным release candidate без расширения runtime и без размывания
scenario-first API. Этот этап не добавляет новые пользовательские сценарии. Он фиксирует, что уже готово, что
намеренно отложено, и какие инварианты доказываются тестами, документами и release gate.

## Область

- Release stabilization pass.
- Public API freeze audit.
- Invariant proof map.
- Release docs cleanup.
- Platform/PTY decision.
- Kotlin generated documentation decision.

Не входят:

- новые convenience overloads;
- raw session pooling и stateful affinity pools;
- real MCP SDK adapter;
- Windows ConPTY implementation;
- Maven Central publishing implementation;
- Dokka publication setup.

## Выполненные шаги

1. Обновлен `context/quality/scorecard.md`: статусы приведены к release-candidate baseline, открытые решения
   разделены на принятые стабилизационные решения и отложенные задачи.
2. Добавлен public API freeze audit:
   `context/audits/public-api-freeze-audit-2026-05-18.md`.
3. Добавлена карта доказательства инвариантов:
   `context/quality/invariant-proof-map.md`.
4. Добавлены ADR:
   - `ADR-0016-first-rc-api-stabilization.md`;
   - `ADR-0017-release-publishing-strategy.md`;
   - `ADR-0018-platform-pty-strategy.md`;
   - `ADR-0019-kotlin-generated-docs-strategy.md`.
5. Обновлены release docs, release checklist, dependency review, compatibility policy и public docs release pages.
6. Подготовлен публичный draft release notes для первого release candidate.

## Проверки

Минимальная проверка этого этапа:

```bash
./gradlew quickCheck
./gradlew publicDocsCheck
./gradlew spotlessCheck
git diff --check
```

Полный release gate остается:

```bash
./gradlew releaseCandidateCheck
```

`releaseCandidateCheck` требует clean worktree, поэтому запускается после фиксации изменений.
