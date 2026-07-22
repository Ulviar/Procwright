# Уровни проверок и проверочный gate

## Назначение

Test/eval tiers фиксируют, какие инварианты защищает каждый уровень проверки. Это не заменяет сценарный API и не
добавляет новый runtime abstraction: уровни существуют только для разработки, аудита и релиза.

## Принципы

- Быстрый feedback проверяет контракты и public API без запуска тяжелых сценариев.
- Сценарные проверки используют реальные процессы и подтверждают пользовательские workflows.
- Stress остается bounded: он ловит регрессии, но не превращается в нестабильный benchmark suite.
- Comparison/JMH живут как research/manual tasks и не входят в release pass/fail gate.
- Release gate собирает уже определенные уровни, документацию и чистоту worktree; он не скрывает отдельные команды.
- Machine-specific capabilities проверяются через assumptions/skip там, где среда их не гарантирует. Контролируемый
  Linux/JDK 17 job, напротив, требует system PTY и падает, если capability недоступна: так допустимый skip не может
  скрыть полное отсутствие реального PTY coverage.
- Каждый JUnit test имеет default deadline 60 секунд в отдельном timeout thread; более строгий локальный `@Timeout`
  сохраняет приоритет. Блокирующие future waits внутри тестов дополнительно используют явные deadlines там, где
  проверяется lifecycle.

## Уровни

### Tier 0: проверка контрактов

Команда:

```bash
./gradlew quickCheck --project-prop=procwright.javaRelease=17
```

Назначение:

- value objects и scenario Draft fail fast;
- public API surface не получает случайные entry points;
- `apiCompatibilityCheck` сравнивает public JVM signatures с baseline `0.1.0`;
- `:procwright-kotlin:checkKotlinAbi` сравнивает Kotlin JVM ABI и metadata-visible declarations с tracked baseline;
- unit tests проверяют production-owned `DiagnosticAttributeSchema` через построение diagnostics events и schema-safe
  failure attributes;
- helper ownership и boundary tests проверяют изоляцию инвариантов без долгих процессов.

Этот уровень должен оставаться самым дешевым default для TDD.

### Tier 1: сценарная проверка

Команда:

```bash
./gradlew scenarioCheck --project-prop=procwright.javaRelease=17
```

Назначение:

- core scenarios запускаются на реальных процессах;
- `run`, `interactive`, `lineSession`, `expect`, `listen` и module adapters проверяются как workflows;
- `:procwright-consumer-examples:test` выполняет внешние consumer workflows для `run`, `lineSession`, `protocolSession` и
  pools;
- отдельные integrations и Kotlin consumer fixtures компилируются с единственной зависимостью на соответствующий
  optional artifact и проверяют его transitive metadata;
- Kotlin и integrations modules подтверждают, что optional ergonomics не ломают core философию.

Этот уровень отвечает за вопрос: "пользовательский сценарий действительно работает?"

### Tier 2: регрессионная проверка

Команда:

```bash
./gradlew regressionCheck --project-prop=procwright.javaRelease=17
```

Назначение:

- bounded `stressTest` проверяет backpressure, timeout churn, large output и session lifecycle;
- external-library boundary не допускает утечки comparison dependencies в public artifacts;

Этот уровень не является benchmark. Производительность оценивается через надежность поведения под нагрузкой и
ограниченность ресурсов.

### Tier 3: проверка документации и артефактов

Команды:

```bash
./gradlew publicJavaJavadocCheck --project-prop=procwright.javaRelease=17
./gradlew :procwright-kotlin:kotlinApiDocsCheck --project-prop=procwright.javaRelease=17
./gradlew :procwright-kotlin:checkKotlinAbi --project-prop=procwright.javaRelease=17
./gradlew publicDocsCheck --project-prop=procwright.javaRelease=17
```

Назначение:

- Java public modules собирают Javadoc;
- `publicJavaJavadocCheck` запускает Javadoc с `-Werror`, поэтому warning является failure, а не ручной заметкой;
- `kotlinApiDocsCheck` запускает Dokka parser-backed проверку KDoc с `reportUndocumented=true` и
  `failOnWarning=true`;
- Kotlin public binary API совпадает с tracked ABI baseline;
- public MkDocs site собирается в strict mode;
- documentation maturity проверяется отдельно от runtime behavior.

### Tier 4: проверка release gate

Команда:

```bash
./gradlew releaseCandidateCheck --project-prop=procwright.javaRelease=17
./gradlew releaseCandidateCheck --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.version=0.1.0
```

Назначение:

- в default SNAPSHOT режиме запускает readiness, documentation и release script/contract self-tests без finalized-doc
  check и реальной публикации artifacts;
- в explicit non-SNAPSHOT режиме дополнительно запускает `releaseDocsContentCheck` и
  `realReleaseArtifactSemanticTest` без обхода publication/signing guards;
- требует clean Git worktree, включая untracked files;
- выполняет clean-tree check после всех проверок, выбранных текущим режимом.

## Правило развития

Новая возможность должна попасть минимум в один быстрый contract test и один scenario или integration test. Если
изменение касается shutdown, streaming, PTY, pooling, diagnostics или external-library boundary, оно дополнительно
требует regression/stress или explicit release checklist entry.
