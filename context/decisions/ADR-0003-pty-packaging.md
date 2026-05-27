# ADR-0003: PTY packaging

## Статус

Принято. Текущий transport contract зафиксирован в [ADR-0005](ADR-0005-pty-transport.md).

## Контекст

PTY нужен для части интерактивных сценариев, но не должен утяжелять execution kernel и не должен диктовать публичную
архитектуру.

Procwright должен сначала стабилизировать:

- command model;
- one-shot execution;
- session lifecycle;
- scenario profiles;
- transport SPI.

## Решение

PTY dependency не подключается к core artifact.

Архитектура оставляет место для `PtyProvider` / transport SPI. Текущий transport выбран отдельным ADR.

## Последствия

Плюсы:

- core foundation остается легким;
- PTY quirks не протекают в core API;
- можно позже выбрать packaging на основании реальных tests и platform matrix.

Минусы:

- terminal-required workflows требуют отдельного transport slice.
