# Политика версий

## Текущий статус

Текущая публичная версия — `0.1.0`, доступная из Maven Central. Опубликованные артефакты неизменяемы: исправления
выпускаются под новой версией. До первого изменения public API после `0.1.0` обязателен bootstrap из
[политики совместимости](compatibility-policy.md#стабильность-публичного-api).

Root project и optional modules должны иметь одинаковые `group` и `version`. Версия относится ко всему набору
артефактов текущего публичного release, а не только к core module.

Release build получает выбранную версию явно через `-Pprocwright.version`; development default остаётся
`0.0.0-SNAPSHOT`. Для установки текущего checkout пользовательская документация использует `0.1.0-SNAPSHOT`, чтобы
локальная сборка не подменяла опубликованный release с теми же coordinates. Изменения артефактов после проверки кандидата
требуют новой сборки и повторной проверки перед публикацией.

## До `1.0.0`

- Версии `0.x` допускают breaking changes в public API только с явным описанием в release notes
  и осознанным обновлением binary compatibility policy.
- Breaking change не должен попадать в код без обновления compile-tested examples и релевантных behavior checks.
- Пользовательский API остается scenario-first: новые возможности добавляются через сценарии или typed policies, а не
  через разрастание набора низкоуровневых flags.
- Внутренние классы и state machines могут меняться свободно, если public behavior сохраняется тестами.

## Начиная с `1.0.0`

- Patch version исправляет bugs и documentation gaps без breaking public API changes.
- Minor version добавляет обратно совместимые API и новые optional modules.
- Major version нужен для удаления или переименования публичных типов, изменения семантики существующих сценариев или
  изменения минимального Java baseline.

## Что считается публичным API

Публичными считаются типы в пакетах:

- `io.github.ulviar.procwright`;
- `io.github.ulviar.procwright.command`;
- `io.github.ulviar.procwright.session`;
- `io.github.ulviar.procwright.diagnostics`;
- `io.github.ulviar.procwright.terminal`;
- `io.github.ulviar.procwright.kotlin`;
- `io.github.ulviar.procwright.integration`.

Типы в `io.github.ulviar.procwright.internal`, hidden runtime support classes, Gradle fixtures, tests и документы в
`context/` не являются binary compatibility surface. Public package boundary tests должны сканировать весь production
artifact, а не только ожидаемый package subtree. Единственное структурное исключение — binary names внутренних
реализаций, записанные JVM в `PermittedSubclasses` публичных sealed handles: сами реализации не являются доступным API,
но их release-locked allowlist в `SessionContractShapeTest` проверяет изменение публичной sealed
hierarchy отдельно от общего bytecode compatibility tool.
