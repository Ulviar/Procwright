# ADR-0005: PTY transport через узкий provider

## Статус

Принято.

## Контекст

Часть интерактивных CLI ведет себя иначе без настоящего terminal: REPL могут менять buffering, выводить prompts только
под TTY, читать control signals через terminal driver или проверять `isatty`.

При этом PTY не должен превращать scenario API в платформенный набор flags. Пользовательская модель: пользователь
выбирает сценарий и терминальную потребность, а runtime выбирает transport.

## Решение

Ввести public `TerminalPolicy`:

- `DISABLED` — всегда pipes;
- `AUTO` — использовать PTY, если provider доступен, иначе pipes;
- `REQUIRED` — требовать PTY и падать с явной ошибкой, если provider недоступен.

Для session-сценариев добавить узкий public `PtyProvider` SPI и `PtyRequest`. В core реализовать системный Unix
provider через `script(1)`, без внешней PTY dependency.

System provider допускает только exact capability, доказанную bounded startup probe. Transport wrapper запускается с
минимальным фиксированным environment, а child environment и argv передаются как данные после отключения echo и
применяются только перед final exec. Неизвестный `script(1)` fail-closed недоступен.

Минимальные терминальные данные:

- `TerminalSize` хранит requested columns/rows;
- системный provider выставляет `LINES`/`COLUMNS` и делает best-effort `stty rows/cols` внутри PTY;
- `TerminalSignal.INTERRUPT` и `Session.sendSignal(...)` дают минимальное Ctrl+C-style mapping через terminal control
  byte.

Windows ConPTY фиксируется как explicit unsupported behavior в текущем artifact. `REQUIRED` не должен fallback в pipes,
а `AUTO` может fallback.

## Альтернативы

1. Подключить внешнюю PTY dependency сразу в core.
   - Отклонено: PTY complexity не должна попадать в ядро раньше устойчивой transport boundary.
2. Спрятать terminal preference в общем service-level configuration.
   - Отклонено: конкретный session Draft должен явно сказать "этому запуску нужен terminal".
3. Делать PTY только через shell strings.
   - Отклонено: это ломает direct argv invariant и усложняет quoting.

## Последствия

Плюсы:

- терминальная потребность выражается одной доменной policy;
- `REQUIRED` имеет проверяемый no-silent-fallback invariant;
- Unix support появляется без новой dependency и без platform details в scenario API;
- Windows strategy честно обозначена как unsupported до отдельного provider.

Минусы:

- `script(1)` имеет терминальные особенности: echo, CRLF/control output и merged child stdout/stderr;
- system provider требует абсолютные `/bin/sh`, `script`, `stty`, `env` и `dd`; executable token с `=` отклоняется из-за
  неустранимой двусмысленности portable `env`;
- полноценная process-group/signal модель не закрыта, есть только проверенный interrupt control-byte mapping;
- возможен будущий optional artifact для нативного PTY/ConPTY provider.
