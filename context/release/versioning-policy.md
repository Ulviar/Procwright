# Политика версий

## Текущий статус

Текущая публичная версия проекта — `0.1.0`. Это первый публичный релиз до `1.0.0`; breaking changes по-прежнему
допустимы только при осмысленном обновлении public docs, compile-tested examples и релевантных behavior checks.

Root project и optional modules должны иметь одинаковые `group` и `version`. Версия относится ко всему набору
артефактов текущего публичного release, а не только к core module.

## До `1.0.0`

- Версии `0.x` допускают breaking changes в public API, если изменение оформлено ADR или release note.
- Breaking change не должен попадать в код без обновления compile-tested examples и релевантных behavior checks.
- Пользовательский API остается scenario-first: новые возможности добавляются через сценарии, typed policies или
  presets, а не через разрастание набора низкоуровневых flags.
- Внутренние классы и state machines могут меняться свободно, если public behavior сохраняется тестами.

## Начиная с `1.0.0`

- Patch version исправляет bugs и documentation gaps без breaking public API changes.
- Minor version добавляет обратно совместимые API и новые optional modules.
- Major version нужен для удаления или переименования публичных типов, изменения семантики существующих сценариев или
  изменения минимального Java baseline.

## Что считается публичным API

Публичными считаются типы в пакетах:

- `io.github.ulviar.icli`;
- `io.github.ulviar.icli.command`;
- `io.github.ulviar.icli.session`;
- `io.github.ulviar.icli.diagnostics`;
- `io.github.ulviar.icli.terminal`;
- `io.github.ulviar.icli.preset`;
- `io.github.ulviar.icli.kotlin`;
- `io.github.ulviar.icli.integration`.

Типы в `io.github.ulviar.icli.internal`, hidden runtime support classes, Gradle fixtures, tests и документы в
`context/` не являются binary compatibility surface. Public package boundary tests должны сканировать весь production
artifact, а не только ожидаемый package subtree.
