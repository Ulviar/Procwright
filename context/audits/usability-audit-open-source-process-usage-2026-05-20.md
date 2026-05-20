# Масштабный аудит удобства использования iCLI по open-source process usage

## Назначение

Этот аудит проверяет, насколько scenario-first API iCLI соответствует реальным способам запуска внешних процессов в
открытых Java-проектах и библиотеках-аналогах. Цель не в том, чтобы скопировать API `ProcessBuilder`, Apache Commons
Exec, zt-exec, NuProcess или ExpectIt. Цель — увидеть, где текущий язык iCLI уже закрывает пользовательскую задачу лучше
низкоуровневых средств, где есть документируемые пробелы, а где расширение нарушило бы исходную философию проекта.

Дата среза: 2026-05-20.

## Методика

Были просмотрены официальные API/README источники и выборка реальных проектов, где subprocess usage не является учебным
примером:

- [Java 25 `ProcessBuilder`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html);
- [Apache Commons Exec tutorial](https://commons.apache.org/proper/commons-exec/tutorial.html);
- [zt-exec README](https://github.com/zeroturnaround/zt-exec);
- [NuProcess README](https://github.com/brettwooldridge/NuProcess);
- [ExpectIt README](https://github.com/Alexey1Gavrilov/ExpectIt);
- [Testcontainers `CommandLine`](https://github.com/testcontainers/testcontainers-java/blob/main/core/src/main/java/org/testcontainers/utility/CommandLine.java);
- [Gradle `ExecHandleRunner`](https://github.com/gradle/gradle/blob/master/platforms/core-runtime/process-services-base/src/main/java/org/gradle/process/internal/ExecHandleRunner.java);
- [Gradle `ProcessBuilderFactory`](https://github.com/gradle/gradle/blob/master/platforms/core-runtime/process-services-base/src/main/java/org/gradle/process/internal/ProcessBuilderFactory.java);
- [Maven `ForkedMavenExecutor`](https://github.com/apache/maven/blob/master/impl/maven-executor/src/main/java/org/apache/maven/cling/executor/forked/ForkedMavenExecutor.java);
- [Elasticsearch `Spawner`](https://github.com/elastic/elasticsearch/blob/main/server/src/main/java/org/elasticsearch/bootstrap/Spawner.java);
- [Solr `RunExampleTool`](https://github.com/apache/solr/blob/main/solr/core/src/java/org/apache/solr/cli/RunExampleTool.java);
- [Spring Cloud release tools `ReleaserProcessExecutor`](https://github.com/spring-cloud/spring-cloud-release-tools/blob/main/releaser-core/src/main/java/releaser/internal/tech/ReleaserProcessExecutor.java);
- [documents4j `ProcessFutureWrapper`](https://github.com/documents4j/documents4j/blob/master/documents4j-util-transformer-process/src/main/java/com/documents4j/conversion/ProcessFutureWrapper.java);
- [Watchman Java `WatchmanTransportBuilder`](https://github.com/facebook/watchman/blob/main/watchman/java/src/com/facebook/watchman/WatchmanTransportBuilder.java);
- [Jenkins `Launcher`](https://github.com/jenkinsci/jenkins/blob/master/core/src/main/java/hudson/Launcher.java);
- [Jenkins `Proc`](https://github.com/jenkinsci/jenkins/blob/master/core/src/main/java/hudson/Proc.java).

Выборка намеренно покрывает разные классы задач: build tools, test infrastructure, release automation, server startup,
native helper processes, remote/build agents, prompt automation, structured worker protocols и high-concurrency process
I/O.

## Наблюдаемые семьи сценариев

| Семья сценариев | Где встречается | Что реально нужно пользователю | Текущий сценарий iCLI |
| --- | --- | --- | --- |
| Завершимая команда с результатом | Testcontainers, Maven, zt-exec examples, Commons Exec tutorial | Запустить argv, получить exit/stdout/stderr, timeout, понятную ошибку | `run` |
| Checked command wrapper | Testcontainers, Spring release tools, Maven version query | Бросить domain exception при non-zero/timeout, но сохранить output | `run` + `CommandResult.toException()` |
| Команда с tee в лог и bounded capture | Spring release tools, zt-exec examples | Писать output в logger во время выполнения и сохранить итоговый фрагмент | Частично `run`, частично `listen` |
| Долгоживущий log/watcher | Elasticsearch native controllers, Jenkins stream pumps, zt-exec line callbacks | Читать stdout/stderr без unbounded memory и не заблокировать child process | `listen` |
| Managed daemon/server startup | Solr examples, Elasticsearch controllers | Запустить процесс, дождаться HTTP/socket/readiness, корректно закрыть | `listen` + внешний readiness check |
| Prompt automation | Solr prompts, ExpectIt examples | Ждать prompt/regex, отправлять строки, отличать timeout/EOF | `interactive` + `Expect` |
| Line-oriented worker | CLI REPL, long-lived tool worker, command-backed adapters | Сериализовать request/response, retire worker при protocol uncertainty | `lineSession`, `pooled` |
| Structured command-backed tool | Watchman discovery, iCLI integrations comparison | Запускать CLI как discovery/adapter, валидировать structured output | `run`, `lineSession`, `:icli-integrations` |
| Remote/build-agent launcher | Jenkins launcher/Proc | Запускать локально или удаленно, фильтровать env, прокидывать streams через channel | Не core scope |
| High-concurrency process transport | NuProcess, Watchman usage | Минимизировать stream threads and memory for many processes | Не public API; возможный backend research |
| Platform-specific command branch | Maven `.cmd`, Solr Windows/Unix split, Gradle signals | Выбрать executable, shell boundary, signal capability per OS | User/domain layer + iCLI shutdown/terminal policies |

## Как iCLI выглядел бы в этих проектах

### Завершимая automation-команда

В Testcontainers-style helper, Spring release automation или Maven version query iCLI заменяет не только
`ProcessBuilder`, а маленький самописный runner:

```java
CommandService command = CommandService.forCommand("docker");

CommandResult result = command.run(call -> call
        .args("info", "--format", "{{json .ServerVersion}}")
        .timeout(Duration.ofSeconds(20)));

if (!result.succeeded()) {
    throw result.toException();
}
String version = result.stdout().trim();
```

Пользовательский выигрыш: не нужно вручную создавать pump threads, ждать процесс, решать, сколько output хранить, и
отдельно чинить timeout path. Минус текущего UX: частый checked-flow требует двух строк `succeeded()` + `toException()`,
тогда как zt-exec дает `exitValueNormal()` / `exitValues(...)` прямо в fluent chain.

### Команда с output tee

В Spring Cloud release tools типичный user story: команда идет долго, output нужен в лог прямо сейчас, но итоговый output
тоже нужен для диагностики. В текущем iCLI это раскладывается на выбор:

- `run`, если нужен итоговый bounded result, но real-time logging не критичен;
- `listen`, если real-time stream важнее итогового captured result;
- diagnostics, если нужно наблюдать lifecycle, но не раскрывать raw output.

Это концептуально чисто, но UX gap есть: распространенный "tee to logger + retain bounded tail" сейчас не является
одним очевидным рецептом.

### Долгоживущий daemon или log follower

Elasticsearch `Spawner` и Jenkins `Proc` показывают классическую боль: если stdout/stderr не читать, child process может
заблокироваться; если читать вручную, появляется lifecycle code вокруг pump threads, закрытия и ожидания. В iCLI это
естественно выглядит как `listen`:

```java
try (StreamSession controller = moduleController.listen(call -> call
        .args("--controller")
        .onOutput(chunk -> logger.warn("{}: {}", chunk.source(), chunk.text())))) {
    waitUntilControllerIsReady();
}
```

Важно: readiness остается domain concern. iCLI должен владеть stream draining, timeout, shutdown и diagnostics, но не
должен превращаться в generic server orchestration framework.

### Prompt automation

Solr example tooling и ExpectIt показывают, что prompt automation — отдельная задача, а не "просто ProcessBuilder +
while read". Текущий `interactive` + `Expect` хорошо сохраняет нужную границу:

```java
try (Session session = tool.interactive(call -> call.args("configure"));
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(5)))) {
    expect.expectText("Name:");
    expect.sendLine("demo");
    expect.expectRegex(Pattern.compile("ready|ok"));
}
```

Здесь iCLI не должен копировать ExpectIt как dependency. Полезна сама модель: один владелец output streams, bounded
transcript, различимые timeout/EOF/closed/failure, фильтрация echo/ANSI там, где это явно нужно.

### Line worker и pooled worker

Многие проекты запускают CLI не как разовую команду, а как worker или discovery transport. Watchman запускает subprocess
для discovery, затем переходит на socket/named pipe; похожий паттерн возникает у language servers, SMT solvers,
formatters, linters и JSONL tools.

Если процесс дает line-oriented request/response protocol, iCLI должен выглядеть не как raw session, а как `lineSession`
или `pooled`:

```java
try (LineSession worker = tool.lineSession(call -> call.args("repl"))) {
    LineResponse response = worker.request("status");
    parseStatus(response.text());
}
```

Это одна из самых сильных сторон текущей модели: protocol uncertainty после timeout/failure закрывает worker, а не
оставляет пользователя гадать, можно ли продолжать.

### Build-agent и remote launcher

Jenkins — полезный отрицательный пример для scope. Его `Launcher` решает не только process execution, но и remoting,
masking secrets in logs, environment filtering, build-agent channels, process-tree cleanup по cookie и legacy API
compatibility. iCLI не должна становиться такой платформой. Из Jenkins стоит переносить выводы об output ownership,
process-tree cleanup и diagnostics, но не remote launcher surface.

## Сильные стороны текущего iCLI UX

1. Сценарный язык совпадает с реальными пользовательскими задачами лучше, чем универсальный `ProcessExecutor`-style API.
   В open-source проектах почти всегда пишут не "runner", а маленький scenario wrapper: command check, release command,
   forked Maven executor, native controller spawner, log follower, prompt automator.

2. Разделение `run`, `listen`, `interactive`, `Expect`, `lineSession` и `pooled` защищает от смешения несовместимых
   инвариантов. Это особенно заметно на stream ownership: Jenkins и Gradle вынуждены явно различать "caller reads raw
   streams" и "framework pumps streams"; iCLI делает это частью сценария.

3. Bounded capture/transcript — правильный default. Реальные проекты часто либо теряют output, либо рискуют unbounded
   memory, либо размазывают truncation policy по utility-классам.

4. Shutdown policy и process-tree cleanup должны оставаться core value. Gradle, Jenkins, Commons Exec и zt-exec
   подтверждают, что timeout без понятного destroy path быстро становится пользовательской ловушкой.

5. `lineSession`/`pooled` дают iCLI самостоятельную нишу, которой нет у Commons Exec и zt-exec: warm worker как сценарий,
   а не как самодельная state machine поверх raw streams.

## UX gaps и улучшения

### P0: улучшить документацию до первого release candidate

1. Добавить публичный `ProcessBuilder migration guide`.
   Документ должен показывать не "как заменить API method на API method", а как выбрать сценарий: `run`, `listen`,
   `interactive`, `Expect`, `lineSession`, `pooled`, integration adapter.

2. Добавить `Real-world process recipes`.
   Нужны рецепты для типовых open-source задач: `git/docker/maven --version`, release command with timeout, long-running
   log follower, daemon startup with external readiness check, prompt-driven installer, line REPL worker, JSONL tool.

3. Явно описать "tee output to logger while keeping bounded diagnostics".
   Если до RC не меняем API, документация должна честно объяснять выбор между `run`, `listen` и diagnostics, чтобы
   пользователь не пытался получить все свойства через один generic runner.

4. Добавить decision table по output ownership.
   Таблица должна отвечать на вопрос: "кто читает stdout/stderr?" Для реальных пользователей это важнее, чем список
   классов.

5. Добавить guide по portable command construction.
   Реальные проекты постоянно ветвятся на `.cmd`/shell/direct argv/Windows path quirks. iCLI должен документировать:
   direct argv by default, shell only explicitly, OS-specific executable selection belongs to caller/domain layer.

6. Документировать non-goals.
   До RC нужно явно сказать, что iCLI core не является remote build-agent launcher, SSH/telnet automation framework,
   high-concurrency native process backend или server orchestrator.

### P1: рассмотреть небольшие API additions только после отдельного API-аудита

1. Checked result convenience.
   Частый пользовательский путь — "верни stdout или брось exception with result". Сейчас это возможно через
   `succeeded()` + `toException()`, но ergonomics хуже zt-exec. Возможные формы: `CommandResult.requireSuccess()` или
   отдельный small helper. Добавлять только если это не размоет separation outcome/failure.

2. Line-oriented stream adapter.
   zt-exec и реальные log consumers часто мыслят строками, а не chunks. Возможная форма — helper/factory для
   `StreamListener`, который собирает строки с bounded line length и не меняет `listen` contract.

3. Command availability probe.
   Testcontainers и Watchman имеют отдельную проверку executable discovery. Это может быть полезным, но рискованно для
   core: PATH lookup, Windows extensions and permissions are platform-specific. До API-добавления лучше начать с recipe.

4. Async one-shot execution.
   documents4j и zt-exec показывают спрос на `Future`-like one-shot. В iCLI это не должно стать generic async process
   handle; если появится, это должен быть async-view того же `run` scenario с теми же capture/shutdown инвариантами.

5. Tee capture policy.
   Если real-world pressure повторится после документации, можно рассмотреть typed policy для `run`: stream output to
   sink while retaining bounded result. Это должно оставаться `run` policy, а не отдельным `ProcessExecutor` режимом.

### P2: future scope, не для первого RC

1. Managed daemon scenario.
   Может быть полезен для "start process, wait readiness, keep handle, close process tree". Но риск оверинжиниринга
   высокий: readiness является domain-specific. Начать с recipes; сценарий добавлять только при повторяющихся evals.

2. Optional high-concurrency backend SPI.
   NuProcess полезен как research boundary для hundreds-of-process workloads, но его callback model ниже уровня iCLI.
   Если backend появится, он должен быть implementation detail за narrow SPI без публичных NuProcess types.

3. Remote stream/SSH adapters.
   ExpectIt хорошо работает не только с local process, но и с SSH/telnet streams. Для iCLI это может быть отдельный
   adapter layer над `Expect`-like contracts, но не core process API.

## Что не стоит переносить

- Один универсальный fluent runner с десятками orthogonal options. Он выглядит удобно в простых snippets, но в сложных
  случаях скрывает, кто владеет stdout/stderr, timeout, stdin и lifecycle.
- Shell-by-default shortcuts. Commons Exec tutorial и Solr examples показывают, что quoting быстро становится
  источником ошибок. Shell должен быть explicit boundary.
- Remote launcher model в стиле Jenkins. Это другая платформа с remoting, masking, agent lifecycle и build semantics.
- High-concurrency native process backend как публичная модель. Это runtime optimization, а не пользовательский сценарий.
- Автоматическое domain readiness в core. HTTP/socket/file readiness должен оставаться приложенческим контрактом, пока
  не появится повторяемый независимый сценарий.

## Итоговая оценка

Текущая направленность iCLI подтверждается open-source выборкой. Большинство реальных проектов не хотят "еще один
низкоуровневый `ProcessBuilder`". Они снова и снова пишут вокруг него одни и те же сценарные оболочки: checked command,
timeout, stream draining, process-tree cleanup, prompt wait, long-lived worker, log follower, command-backed adapter.

Главное улучшение UX на ближайший этап — не расширять core API, а сделать сценарный выбор очевидным на реальных
миграционных примерах. Самый сильный API-кандидат после документации — checked result convenience и line-oriented
stream listener adapter. Самые опасные направления — managed daemon framework, remote launcher и native backend в public
surface: они могут дать возможности, но легко сломают философию "пользователь выбирает сценарий, а не мешок flags".
