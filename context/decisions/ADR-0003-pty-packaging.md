# ADR-0003: PTY packaging

## Статус

Accepted for foundation.

## Контекст

PTY нужен для части интерактивных сценариев, но не должен утяжелять первый execution kernel и не должен диктовать
публичную архитектуру. Старый проект подключал PTY dependency в core слишком рано.

Clean rewrite должен сначала стабилизировать:

- command model;
- one-shot execution;
- session lifecycle;
- scenario profiles;
- transport SPI.

## Решение

В фазе 1 PTY dependency не подключается к core artifact.

Архитектура оставляет место для `PtyProvider` / transport SPI. Конкретная PTY dependency и packaging будут выбраны
перед реализацией PTY transport отдельным ADR.

## Последствия

Плюсы:

- core foundation остается легким;
- PTY quirks не протекают в ранний API;
- можно позже выбрать packaging на основании реальных tests и platform matrix.

Минусы:

- PTY сценарии не будут доступны в ранних фазах;
- потребуется отдельный integration slice для terminal-required workflows.
