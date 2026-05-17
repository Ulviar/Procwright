# Источники по библиотекам

## Apache Commons Exec

- Apache Commons Exec release history: https://commons.apache.org/exec/changes.html
- API docs for `DefaultExecutor`: https://commons.apache.org/proper/commons-exec/apidocs/org/apache/commons/exec/DefaultExecutor.html
- API docs for `ExecuteWatchdog`: https://commons.apache.org/exec/apidocs/org/apache/commons/exec/ExecuteWatchdog.html

Наблюдение: библиотека активно обновляется; версия `1.6.0` опубликована как feature/maintenance release и сохраняет
Java 8+ baseline.

## ZeroTurnaround zt-exec

- Maven Central listing: https://repo1.maven.org/maven2/org/zeroturnaround/zt-exec/
- GitHub repository: https://github.com/zeroturnaround/zt-exec
- Javadocs: https://javadoc.io/doc/org.zeroturnaround/zt-exec/latest/index.html

Наблюдение: fluent API удобен для one-shot; последняя версия `1.12` старая по сравнению с Commons Exec.

## NuProcess

- Maven Central metadata: https://central.sonatype.com/artifact/com.zaxxer/nuprocess
- GitHub repository: https://github.com/brettwooldridge/NuProcess
- API docs for `NuProcess`: https://brettwooldridge.github.io/NuProcess/apidocs/com/zaxxer/nuprocess/NuProcess.html

Наблюдение: библиотека фокусируется на non-blocking I/O и низком overhead для большого числа процессов.

## Pty4J

- GitHub README: https://github.com/JetBrains/pty4j
- Maven Central metadata: https://central.sonatype.com/artifact/org.jetbrains.pty4j/pty4j
- Maven Repository version listing: https://mvnrepository.com/artifact/org.jetbrains.pty4j/pty4j

Наблюдение: это специализированная PTY-библиотека, а не общий process runner; поддерживает Linux, macOS, Windows и
FreeBSD по README. Harness использует `0.13.12`, опубликованную 30 января 2026.

## ExpectIt

- Maven Central metadata: https://central.sonatype.com/artifact/net.sf.expectit/expectit-core
- Javadocs: https://javadoc.io/doc/net.sf.expectit/expectit-core/latest/index.html

Наблюдение: это специализированный Expect helper поверх streams, а не launcher. Последний релиз `0.9.0` старый, но
библиотека прямо соответствует prompt automation scenario.
