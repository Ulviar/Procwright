# Качественная оценка библиотек по целям iCLI

## Назначение

Этот документ дополняет quantitative report из [results.md](results.md). Он фиксирует выводы по API, документации,
maintenance/task-fit и пригодности библиотек для scenario-first модели iCLI. Это не выбор backend-а и не обещание
интегрировать внешние зависимости в core.

## Метод оценки

Оценка использует критерии из [evaluation-criteria.md](evaluation-criteria.md):

- надежность работы;
- производительность как workflow signal;
- удобство API;
- качество документации;
- пригодность для сценариев iCLI.

`UNSUPPORTED` в quantitative report не считается ошибкой библиотеки, если сценарий явно вне ее scope. Но для iCLI это
считается capability gap: пользователю все равно нужен цельный сценарный API.

## Выводы по кандидатам

### iCLI rewrite

Сильная сторона — единый scenario-first язык поверх разных workflow: `run`, `lineSession`, `interactive`, `expect`,
`listen`, `pooled` и optional integration layer. Текущий harness показывает полное покрытие сценариев, но это не
доказательство окончательной зрелости реализации. Главная ценность — правильная архитектурная граница: пользователь
выбирает workflow, а runtime удерживает инварианты.

Риск: собственный runtime несет больше ответственности и требует сильных stress/integration/release gates.

### JDK ProcessBuilder

Сильная сторона — предсказуемость, portability и отсутствие runtime dependency. Он подходит как baseline и internal
floor. Для one-shot и bounded I/O его можно использовать надежно.

Ограничение: streaming, expect и line-session workflows быстро превращаются в ручной pump/state-machine code. В iCLI
это должно оставаться внутри библиотеки, поэтому `ProcessBuilder` не является пользовательской API-моделью.

### Apache Commons Exec

Сильная сторона — зрелые идеи вокруг watchdog, process execution и stream handlers. Библиотека полезна как reference
для timeout/diagnostic maturity.

Ограничение: интерактивные session/expect/PTY/pooled сценарии не являются ее основной моделью. Переносить ее API в
iCLI нельзя; полезны только отдельные runtime-идеи.

### ZeroTurnaround zt-exec

Сильная сторона — удобный fluent one-shot experience. Это хороший ориентир по краткости пользовательского пути для
простых запусков.

Ограничение: scope остается one-shot/process execution. Сценарии session, expect, PTY и pooled worker требуют отдельной
архитектуры, поэтому zt-exec не подходит как core API model.

### NuProcess

Сильная сторона — non-blocking process I/O и потенциально полезный backend pattern для высокой нагрузки.

Ограничение: API находится ниже целевого уровня iCLI. Он помогает реализовывать transport/runtime, но не задает язык
пользовательских сценариев. Если NuProcess когда-либо появится в проекте, он должен быть optional backend за narrow
SPI, без протекания типов в core API.

### ExpectIt

Сильная сторона — специализированный expect/prompt pattern. Он подтверждает важность first-class prompt automation.

Ограничение: это не общая process execution library. Для iCLI полезен именно pattern matching model, а не превращение
`ExpectIt` в публичную зависимость.

### Pty4J

Сильная сторона — специализированный PTY transport. Он подтверждает, что terminal-required workflows требуют отдельной
capability boundary.

Ограничение: PTY не должен стать самостоятельной общей API-веткой core. В iCLI терминал остается `TerminalPolicy` и
transport capability внутри `interactive`/`lineSession`, а unavailable provider должен давать structured failure/skip,
а не leaking dependency exception.

## Архитектурный итог

Внешние библиотеки полезны как источники зрелых решений, но каждая из них покрывает только часть целевого пространства.
Их нельзя сделать публичной моделью iCLI без потери исходной идеи проекта. Core API должен оставаться собственным,
маленьким и scenario-first; backend choices должны быть заменяемыми implementation details.
