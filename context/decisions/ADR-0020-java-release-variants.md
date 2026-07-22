# ADR-0020: Java release variants

## Статус

Принято.

## Контекст

Procwright должен сохранять один scenario-first API и один набор инвариантов на поддерживаемых JVM runtimes. Нужен один
source tree и механически проверяемые варианты сборки, чтобы Java 17, Java 21 и Java 25 не расходились по API,
документации и тестам.

## Решение

Поддерживаем один source tree и три проверяемых compilation target:

- Java 17;
- Java 21;
- Java 25.

Gradle property `procwright.javaRelease` выбирает compilation target. Значение по умолчанию — `25`; published artifacts
используют только Java 17 target. Каждая ячейка CI-матрицы Linux/macOS/Windows × Temurin JDK 17/21/25 независимо
компилирует и проверяет build с Java 17 target. Отдельные Linux jobs запускают `check` и Javadoc для targets 21/25 на
соответствующих JDK.
Канонические команды release/agent checks явно передают `--project-prop=procwright.javaRelease=17`, поэтому работают на
минимальной поддерживаемой JDK; default 25 остается удобным для локальной разработки на новейшей JDK.

Прямые production-ссылки на Java 21 runtime API запрещены. Потоковая модель вынесена во внутренний boundary:

- на Java 21+ runtime Procwright может использовать virtual threads через reflection;
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
- published artifacts используют Java 17 target; Java 21/25 остаются проверяемыми, но не публикуемыми source variants;
- нельзя добавлять production API, требующий Java выше минимального поддерживаемого release, без нового ADR.
