# ADR-0015: JPMS-инкапсуляция ядра

## Статус

Accepted.

## Контекст

JPMS может стать настоящей границей между public API и implementation details: модуль сможет экспортировать только
пользовательские пакеты и оставить `com.github.ulviar.icli.internal` закрытым.

Но `module-info.java` нельзя добавлять как формальность. JPMS экспортирует пакеты целиком, а не отдельные классы.
Если в экспортируемом пакете остается `public` runtime-only support, такой класс становится модульным API независимо
от того, исключен ли он из Javadoc.

## Решение

Не добавлять `module-info.java` в текущем шаге.

Сначала зафиксировать два условия:

- production package graph должен проверяться тестом, чтобы пакетная архитектура не расходилась с кодом;
- `module-info.java` можно добавить только после того, как runtime-only public support исчезнет из экспортируемых
  пакетов или будет сознательно принят как публичный API.

В этом шаге diagnostics dispatcher и diagnostic schema validator перенесены в `com.github.ulviar.icli.internal`.
Это убирает один JPMS-блокер из `diagnostics`.

Оставшийся блокер находится в `com.github.ulviar.icli.session`:

- `SessionRuntime`;
- `StreamRuntime`;
- `SessionScenarioSupport`.

Эти классы пока публичны не как пользовательский API, а как мост между root facade `CommandService` и package-private
session state. JPMS не сможет скрыть их при экспорте `com.github.ulviar.icli.session`.

## Последствия

Плюсы:

- текущая ветка не получает ложной модульной инкапсуляции;
- diagnostics package становится ближе к чистой public API модели;
- будущая работа по JPMS имеет конкретный список блокеров;
- тесты не позволят добавить `module-info.java`, пока session runtime support остается видимым.

Минусы:

- настоящий Java module descriptor откладывается;
- для полного JPMS потребуется более глубокое разделение session facade и session implementation.

## Проверка

- `PackageBoundaryTest` проверяет production-зависимости между core packages по скомпилированным class files.
- `PublicApiSurfaceTest` требует, чтобы `module-info.java` не появлялся до устранения runtime-only public session
  support из экспортируемых пакетов.
- `PublicApiSurfaceTest` по-прежнему запрещает утечки `internal` в public signatures.
