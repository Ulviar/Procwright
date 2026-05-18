# PTY как capability boundary

## Назначение

PTY нужен не как отдельный пользовательский сценарий, а как transport capability для сценариев, где terminal меняет
поведение процесса. Пользователь по-прежнему выбирает workflow (`interactive`, `lineSession`, `pooled`), а не provider,
платформу или набор PTY-флагов.

## Публичная модель

- `TerminalPolicy.DISABLED` всегда использует pipes.
- `TerminalPolicy.AUTO` использует PTY только когда provider доступен; иначе fallback в pipes допустим.
- `TerminalPolicy.REQUIRED` требует PTY и fail fast при недоступном provider.
- `SessionInvocation`, `LineSessionInvocation` и `PooledLineSessionInvocation` могут задавать terminal policy.
- `run` и `listen` не раскрывают terminal policy в draft API.

`PooledLineSessionInvocation` наследует terminal capability только потому, что workers являются `LineSession`.
Отдельного pool-level PTY runtime нет.

## Provider boundary

`PtyProvider` и `PtyRequest` — узкий SPI, а не основной пользовательский workflow. Core runtime передает provider только
уже resolved direct argv, working directory, environment и `TerminalSize`.

Запрещено:

- раскрывать Pty4J, ConPTY, `script(1)` или другую provider-specific модель в scenario builders;
- добавлять terminal flags в `CommandInvocation` или `StreamInvocation` без нового ADR;
- делать silent fallback для `TerminalPolicy.REQUIRED`;
- превращать PTY provider в alternate process runtime для `run`, `listen` или integrations.

## Текущий system provider

Core artifact не имеет внешней PTY dependency. Системный provider использует Unix `script(1)` только из trusted system
paths (`/usr/bin/script`, `/bin/script`), если он доступен. PATH lookup намеренно не используется, чтобы terminal
capability не превращалась в запуск подмененного helper из окружения процесса. Windows ConPTY пока explicit unsupported
behavior.

Известные особенности текущего provider:

- terminal output может включать echo, CRLF и control sequences;
- child stdout/stderr под terminal приходят как единый terminal stream;
- `TerminalSignal.INTERRUPT` — минимальный Ctrl+C-style control byte, а не полноценная process-group abstraction.

Эти особенности должны оставаться в документации provider/PTY, но не протекать в scenario-first API.

## Проверяемые инварианты

- Public terminal method есть только у session-family builders.
- `run` и `listen` по умолчанию и в resolver остаются `TerminalPolicy.DISABLED`.
- `listen` получает unavailable PTY provider с явной причиной, чтобы transport boundary не мог случайно выбрать PTY.
- `REQUIRED` не fallback-ится в pipes.
- PTY-only tests skip-аются при отсутствии platform capability.
