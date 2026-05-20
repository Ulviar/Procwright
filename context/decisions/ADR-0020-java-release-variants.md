# ADR-0020: Java release variants

## Статус

Принято.

## Контекст

Проект изначально был переведен на Java 25 как ветка для максимально современного кода. Это хорошо подходит для
разработки и исследования качества, но перед релизной стабилизацией стало важно проверить, можно ли сохранить тот же
scenario-first API и тот же набор инвариантов для более широких runtime-платформ.

Нам не нужна история отдельных веток для Java 17 и Java 21: у библиотеки пока нет внешних пользователей, а дублирование
веток быстро создало бы расхождение API, документации и тестов. Нужен один исходный код и механически проверяемые
варианты сборки.

## Решение

Поддерживаем один source tree и три release target:

- Java 17;
- Java 21;
- Java 25.

Gradle property `icli.javaRelease` выбирает target release. Значение по умолчанию остается `25`, потому что текущая
ветка продолжает использовать Java 25 как development baseline. CI запускает `check` и `javadoc` на Temurin JDK
17/21/25 для Linux, macOS и Windows.

Прямые production-ссылки на Java 21 runtime API запрещены. Потоковая модель вынесена во внутренний boundary:

- на Java 21+ runtime iCLI может использовать virtual threads через reflection;
- на Java 17 runtime используется daemon platform-thread fallback;
- публичный API не обещает конкретную реализацию threading model.

Методы коллекций и тестовый код также должны оставаться source-compatible с Java 17.

## Последствия

Плюсы:

- один scenario-first API для всех release variants;
- нет долгоживущих compatibility branches;
- Java 21/25 сохраняют дешевую concurrency path, когда runtime ее предоставляет;
- Java 17 variant становится проверяемым артефактом, а не отдельной ручной адаптацией.

Ограничения:

- Java 17 variant может иметь другой performance profile под высокой concurrency, потому что использует platform
  threads;
- publishing strategy для нескольких artifacts остается отдельным release decision;
- нельзя добавлять production API, требующий Java выше минимального поддерживаемого release, без нового ADR.

ADR-0004 остается историческим решением о переходе development baseline на Java 25, но релизная совместимость теперь
определяется этим ADR.
