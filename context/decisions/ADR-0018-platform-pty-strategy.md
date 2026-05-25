# ADR-0018: Platform и PTY strategy перед первым релизом

## Статус

Accepted for the first release baseline.

## Контекст

iCLI поддерживает terminal capability через `TerminalPolicy` и `PtyProvider`. Core не должен зависеть от Pty4J,
ConPTY wrappers или других native bindings. Текущий system provider использует platform capability и explicit
unsupported behavior, если terminal недоступен.

Перед первым релизом нужно решить, пытаться ли включить Windows ConPTY provider в текущий scope.

## Решение

Первый релиз не включает Windows ConPTY implementation.

Текущая стратегия:

- ordinary process scenarios используют JDK process pipes и остаются кроссплатформенными;
- terminal capability живет только внутри session-family сценариев;
- `TerminalPolicy.REQUIRED` не делает silent fallback в pipes;
- текущий system PTY provider остается platform-dependent capability;
- POSIX/PTY tests skip-аются через assumptions, если capability недоступна;
- Windows ConPTY будет проектироваться как отдельный optional artifact или runtime-specific provider после первого релиза.

## Почему не добавляем ConPTY сейчас

- ConPTY требует отдельной native/platform integration strategy.
- Нельзя ухудшать core dependency story ради одного transport backend.
- API уже имеет правильную capability boundary; добавление provider не должно менять scenario model.
- Первый релиз должен стабилизировать contract, а не расширять platform runtime.

## Последствия

Плюсы:

- Core остается JDK-only.
- Terminal behavior остается явным capability, а не best-effort магией.
- Будущий ConPTY provider можно добавить без изменения public scenario API.

Минусы:

- Windows terminal-required workflows не считаются shipped capability в первом релизе.
- Пользователь Windows получает explicit unsupported behavior для `TerminalPolicy.REQUIRED`, если provider недоступен.
