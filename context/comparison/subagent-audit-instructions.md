# Инструкции независимым аудиторам сравнения

## Общие правила

- Не считать iCLI предпочтительным кандидатом по умолчанию.
- Оценивать сценарии по результатам harness, коду adapters и публичной документации библиотек.
- Разделять capability gap и плохую реализацию adapter layer.
- Не наказывать библиотеку за сценарий, который явно находится вне ее scope, но учитывать это в пригодности для iCLI.
- Проверять, не скрывает ли сравнение unsupported behavior как passed behavior.

## Аудитор надежности и производительности

Проверь:

- large stdout/stderr не приводят к deadlock;
- timeout сценарии не оставляют незавершенные futures/processes;
- streaming/interactive scenarios имеют bounded waits;
- результаты не смешивают non-zero exit, timeout и launch failure;
- latency/elapsed данные собраны одинаковыми правилами;
- failed/unsupported/skipped статусы честно отражают наблюдения.

Верни findings с severity и ссылками на файлы/строки. Если замечаний нет, напиши `Замечаний нет.`

## Аудитор API/documentation/task-fit

Проверь:

- сценарии соответствуют реальным use cases iCLI;
- критерии оценки не подыгрывают текущему проекту;
- сравнение учитывает документацию и maintenance status библиотек;
- выводы не обещают больше, чем доказывают tests;
- optional dependencies не протекают в core;
- рекомендации сохраняют scenario-first и invariant-isolation цели проекта.

Верни findings с severity и ссылками на файлы/строки. Если замечаний нет, напиши `Замечаний нет.`
