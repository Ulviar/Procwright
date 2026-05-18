# IS06: Terminal-required command

## Scenario

Сценарий проверяет команду, которая меняет поведение без настоящего terminal: процесс должен быть запущен через PTY,
`isatty` для stdin/stdout должен дать terminal mode, а размер terminal должен быть передан в процесс. Core probe ниже
ориентирован на POSIX shell:

```sh
if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; stty size
```

Ожидаемый результат при размере `100x40`: `mode:tty` и строка `40 100`. Helper-функции `readUntil`, `readAll` и
`envWithTerm` считаются внешним harness; LOC ниже считают core library usage и непосредственные проверки в snippet.

## Implementations

### iCLI rewrite

```java
SessionOptions terminal = SessionOptions.defaults()
        .withPtyProvider(PtyProvider.system())
        .withTerminalSize(new TerminalSize(100, 40));
CommandService shell = new CommandService(CommandSpec.of("sh"), RunOptions.defaults(), terminal);
try (Session session = shell.interactive(call -> call
                .terminal(TerminalPolicy.REQUIRED)
                .args("-c", "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; stty size"));
        BufferedReader out =
                new BufferedReader(new InputStreamReader(session.stdout(), StandardCharsets.UTF_8))) {
    assertEquals("mode:tty", readUntil(out, "mode:"));
    assertEquals("40 100", readUntil(out, "40 "));
    assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
}
```

LOC: 13
Status: implemented
API: 88
Docs: 82
Library: 78
Scenario: 83
Notes: Сценарий выражается напрямую через `interactive` + `TerminalPolicy.REQUIRED`; `REQUIRED` fail-fast при
недоступном provider и не fallback-ится в pipes. `TerminalSize` есть в session options, но не задается прямо в per-call
builder, поэтому discovery чуть хуже. Текущий system provider зависит от Unix `script(1)`, Windows ConPTY явно не
поддержан в core artifact, а terminal stream несет обычные PTY особенности: echo, CRLF/control sequences и merged
terminal output.

### JDK ProcessBuilder

```java
// Unsupported as a library-native implementation:
// ProcessBuilder has pipes, redirects, and inheritIO(), but no API to allocate a PTY
// or set/query terminal window size for a child process.
```

LOC: 0
Status: unsupported
API: 12
Docs: 70
Library: 28
Scenario: 30
Notes: `inheritIO()` может дать child процессу тот же terminal, что и у Java процесса, если сам Java процесс уже
запущен из terminal. Это не "request PTY": нельзя создать isolated pseudo-terminal, задать размер и одновременно
получить capture-friendly stream contract. Реальный workaround требует внешнего OS helper (`script(1)`, `setsid`/`stty`
glue) или другой PTY library, то есть выходит за пределы JDK API.

### Apache Commons Exec

```java
// Unsupported as a library-native implementation:
// DefaultExecutor/PumpStreamHandler/ExecuteWatchdog supervise pipe-backed processes,
// but do not expose PTY allocation or terminal size propagation.
```

LOC: 0
Status: unsupported
API: 10
Docs: 60
Library: 32
Scenario: 29
Notes: Commons Exec хорошо документирует `Executor`, stream handlers, exit values и watchdog, но terminal capability в
модели нет. Можно запускать внешний `script(1)` wrapper через `CommandLine`, однако тогда пользователь сам проектирует
platform-specific quoting, `stty rows/cols`, merged output и unavailable behavior. Для IS06 это тяжелый workaround, а не
библиотечный контракт.

### ZeroTurnaround zt-exec

```java
// Unsupported as a library-native implementation:
// ProcessExecutor improves pipe process ergonomics, timeout, output, and exit handling,
// but does not allocate a pseudo-terminal or own terminal dimensions.
```

LOC: 0
Status: unsupported
API: 12
Docs: 54
Library: 28
Scenario: 27
Notes: Fluent API делает внешний `script(1)` workaround короче, чем на raw `ProcessBuilder`, но сама библиотека остается
pipe process executor. Документация покрывает common process cases and timeouts, но не terminal-required commands.
Maintenance story выглядит менее современной, а PTY semantics полностью остаются на пользователе.

### NuProcess

```java
// Unsupported as a library-native implementation:
// NuProcess provides non-blocking stdin/stdout/stderr callbacks over OS pipes,
// not a controlling terminal or window-size API.
```

LOC: 0
Status: unsupported
API: 10
Docs: 60
Library: 35
Scenario: 30
Notes: NuProcess силен для большого числа pipe-backed процессов и callback I/O, но IS06 требует не throughput, а
controlling terminal. Внешний `script(1)` wrapper технически можно запустить через `NuProcessBuilder`, однако callback
harness становится длинным, а PTY lifecycle, quoting, size propagation и unsupported-state detection остаются
custom-кодом.

### Pty4J

```java
HashMap<String, String> env = new HashMap<>(System.getenv());
env.putIfAbsent("TERM", "xterm-256color");
PtyProcess process = new PtyProcessBuilder()
        .setCommand(new String[] {"sh", "-c",
                "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; stty size"})
        .setEnvironment(env)
        .setInitialColumns(100)
        .setInitialRows(40)
        .start();
try (BufferedReader out =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
    assertEquals(100, process.getWinSize().getColumns());
    assertEquals(40, process.getWinSize().getRows());
    assertEquals("mode:tty", readUntil(out, "mode:"));
    assertEquals("40 100", readUntil(out, "40 "));
}
assertTrue(process.waitFor(2, TimeUnit.SECONDS));
```

LOC: 17
Status: implemented
API: 94
Docs: 76
Library: 84
Scenario: 86
Notes: Лучший raw fit: библиотека прямо моделирует PTY process, initial rows/columns и `getWinSize`/`setWinSize`.
Код все еще требует ручного timeout/capture/cleanup harness и знания terminal stream quirks. Документация быстро
показывает basic usage and supported OS list, но failure semantics, native-access risks и edge cases приходится
добивать API/Javadoc и практическими проверками.

### ExpectIt

```java
PtyProcess pty = new PtyProcessBuilder()
        .setCommand(new String[] {"sh", "-c",
                "if [ -t 0 ] && [ -t 1 ]; then echo mode:tty; else echo mode:pipe; fi; stty size"})
        .setEnvironment(envWithTerm())
        .setInitialColumns(100)
        .setInitialRows(40)
        .start();
try (Expect expect = new ExpectBuilder()
        .withInputs(pty.getInputStream())
        .withOutput(pty.getOutputStream())
        .withTimeout(2, TimeUnit.SECONDS)
        .withExceptionOnFailure()
        .build()) {
    expect.expect(contains("mode:tty"));
    expect.expect(contains("40 100"));
}
```

LOC: 16
Status: workaround
API: 34
Docs: 66
Library: 48
Scenario: 46
Notes: ExpectIt полезен как matcher/automation layer после того, как кто-то другой уже предоставил terminal stream.
Самостоятельно библиотека не запускает процесс и не запрашивает PTY; snippet выше фактически зависит от Pty4J как
terminal owner. Документация неплохо объясняет stream-based expect, filters, timeouts and SSH-shell examples, но IS06
нельзя реализовать на ExpectIt alone.

## Summary

Лучший fit для raw PTY: Pty4J. Он единственный внешний кандидат, который прямо создает pseudo-terminal и умеет задавать
initial terminal size.

Самый короткий implemented код: iCLI rewrite. Сценарий выражается через session workflow и `TerminalPolicy.REQUIRED`,
а failure при недоступном PTY provider является частью public contract.

Самый надежный high-level contract: iCLI rewrite, потому что `REQUIRED` запрещает silent pipe fallback и отделяет
terminal capability от обычных `run`/`listen` сценариев. Самый полный low-level terminal control остается у Pty4J.

JDK `ProcessBuilder`, Apache Commons Exec, zt-exec и NuProcess для IS06 unsupported как native library APIs: они
запускают pipe-backed процессы. Их можно заставить пройти probe только через внешний OS-specific PTY helper, но тогда
ключевые инварианты сценария принадлежат custom harness, а не библиотеке. ExpectIt занимает промежуточную позицию:
хороший expect layer поверх PTY, но не PTY provider.
