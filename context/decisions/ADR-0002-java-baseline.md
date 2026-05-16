# ADR-0002: Java baseline

## Статус

Accepted.

## Контекст

Старый проект использовал Java 25. Для clean rewrite важно выбрать baseline, который поддерживает современные
конструкции Java, но не сужает потенциальную аудиторию без необходимости.

Для архитектуры iCLI нужны:

- records;
- sealed interfaces;
- pattern matching where useful;
- virtual threads for future runtime work;
- современный Gradle/JUnit ecosystem.

Java 21 уже дает эти возможности и является зрелым LTS baseline. Текущая локальная среда может использовать более
новый JDK для компиляции, но артефакты библиотеки должны таргетировать Java 21 bytecode.

## Решение

Целевой baseline для core library: Java 21.

Build должен компилировать с `--release 21`, даже если локальный JDK новее.

## Последствия

Плюсы:

- шире adoption, чем у Java 25;
- достаточно современный язык для records, sealed policies и virtual threads;
- меньше риска для пользователей, которые еще не перешли на более новые JDK.

Минусы:

- нельзя использовать API и language features после Java 21;
- при желании использовать более новые возможности потребуется отдельный ADR.
