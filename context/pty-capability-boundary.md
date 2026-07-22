# PTY как capability boundary

## Назначение

PTY нужен не как отдельный пользовательский сценарий, а как transport capability для сценариев, где terminal меняет
поведение процесса. Пользователь выбирает workflow (`interactive`, `lineSession`, `protocolSession`,
`lineSession().pooled()`, `protocolSession(factory).pooled()`), а не provider, платформу или набор PTY-флагов.

## Публичная модель

- `TerminalPolicy.DISABLED` всегда использует pipes.
- `TerminalPolicy.AUTO` использует PTY только когда provider доступен; иначе fallback в pipes допустим.
- `TerminalPolicy.REQUIRED` требует PTY и fail fast при недоступном provider.
- `InteractiveScenario.Draft`, `LineSessionScenario.Draft` и `ProtocolSessionScenario.Draft` задают terminal policy
  через `withTerminal(...)`.
- `lineSession` и `protocolSession` начинают с `DISABLED`; явный terminal request использует provider и
  `TerminalSize`, выбранные в том же Draft.
- `run` и `listen` не раскрывают terminal policy в draft API.

Pooled-сценарии задают terminal только до ветвления в pool:
`lineSession().withTerminal(...).pooled()` и `protocolSession(factory).withTerminal(...).pooled()` передают явную
scenario-конфигурацию workers. Отдельного pool-level PTY runtime нет.

## Provider boundary

`PtyProvider` и `PtyRequest` — узкий SPI, а не основной пользовательский workflow. Core runtime передает provider только
уже resolved direct argv, working directory, environment и `TerminalSize`.

Запрещено:

- раскрывать Pty4J, ConPTY, `script(1)` или другую provider-specific модель в scenario Draft;
- добавлять terminal methods в `RunScenario.Draft` или `StreamScenario.Draft` без нового ADR;
- делать silent fallback для `TerminalPolicy.REQUIRED`;
- превращать PTY provider в alternate process runtime для `run`, `listen` или integrations.

## Текущий system provider

Core artifact не имеет внешней PTY dependency. Системный provider использует `script`, `stty`, `env` и `dd`
только из фиксированных system paths в `/usr/bin` или `/bin`, а shell — только `/bin/sh`. PATH lookup для
transport helpers запрещен.

Наличие исполняемого файла недостаточно. Bounded probe каждого кандидата запускает точную BSD или util-linux command
shape, проверяет реальный TTY у stdin/stdout, round-trip environment/argv и передачу специального child exit code.
Поэтому BusyBox без `-e`, неизвестная реализация, зависший helper и реализация с неверной передачей exit code считаются
unavailable. Выбранный результат хранится immutable в singleton provider; `available()` не перезапускает probe.

Wrapper всегда получает только allowlisted environment (`SHELL`, `LC_ALL`, `LANG`, `TERM`). После отключения
terminal echo он сообщает `READY`, читает из уже открытого PTY stdin один ограниченный по counts,
полям и total bytes frame, проверяет terminal size и сообщает `STARTED`. Bootstrap/control files нет, а тот же
stdin после `STARTED` принадлежит child.

Resolved child environment и direct argv остаются quoted positional data и применяются только финальным
абсолютным `env -i --`; target shell и macOS `arch` trampoline нет. Child values не попадают в environment или
diagnostics transport helpers. Executable token с `=` fail-closed отклоняется, потому что portable `env` не
умеет отделить такой token от environment assignment.

Windows ConPTY пока explicit unsupported behavior.

Известные особенности текущего provider:

- terminal output может включать echo, CRLF и control sequences;
- child stdout/stderr под terminal приходят как единый terminal stream;
- `TerminalSignal.INTERRUPT` — минимальный Ctrl+C-style control byte, а не полноценная process-group abstraction.

Эти особенности должны оставаться в документации provider/PTY, но не протекать в scenario-first API.

## Проверяемые инварианты

- Public terminal method есть только у session-family Draft.
- `run` и `listen` не имеют terminal capability.
- `lineSession`, `protocolSession` и их pools не включают PTY без явного scenario-level `withTerminal(...)`.
- `listen` получает unavailable PTY provider с явной причиной, чтобы transport boundary не мог случайно выбрать PTY.
- `REQUIRED` не fallback-ится в pipes.
- Wrapper environment не зависит от `PtyRequest.environment`; child environment и argv не интерполируются в shell source.
- `available()` означает успешный bounded probe именно того absolute `script`, который затем запускается.
- PTY-only tests skip-аются при отсутствии platform capability, кроме контролируемых Linux и macOS/JDK 17 CI
  jobs: они требуют system PTY и тем самым доказывают, что весь набор не был незаметно пропущен.
