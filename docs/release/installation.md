# Use Procwright in your application

Procwright requires JDK 25. There is no public artifact yet; to use this checkout from another application, install
its artifacts into your local Maven repository:

```shell
./gradlew publishToMavenLocal \
  --project-prop=procwright.version=0.1.0 \
  --no-daemon
```

## Core dependency

Gradle Kotlin DSL:

<!-- procwright-docs: build-configuration -->
```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:procwright:0.1.0")
}
```

Gradle Groovy:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.ulviar:procwright:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>procwright</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Optional modules

Use `procwright-kotlin` for Kotlin duration, coroutine, Flow, and adapter-factory extensions. Use
`procwright-integrations` for JSON and byte-framing protocol adapters. Both depend on core, so you do not need to declare
the core dependency separately when using either module. Add only the modules your application needs.

`procwright-integrations` exposes Jackson Databind 3.2.2 transitively because Jackson 3 types
(`tools.jackson.databind.JsonNode`) appear in its public adapter API. Check dependency constraints if your application manages a different Jackson version.

Gradle Kotlin DSL:

<!-- procwright-docs: build-configuration -->
```kotlin
dependencies {
    implementation("io.github.ulviar:procwright-kotlin:0.1.0")
    implementation("io.github.ulviar:procwright-integrations:0.1.0")
}
```

Gradle Groovy:

```groovy
dependencies {
    implementation 'io.github.ulviar:procwright-kotlin:0.1.0'
    implementation 'io.github.ulviar:procwright-integrations:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>procwright-kotlin</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>procwright-integrations</artifactId>
    <version>0.1.0</version>
</dependency>
```

See [Kotlin usage](../reference/kotlin-api.md) for coroutine imports and JPMS setup, or
[protocol integrations](../scenarios/integrations.md) to choose a framing adapter.
