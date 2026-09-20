# Use Procwright in your application

Procwright requires JDK 25 to build and run.

Version `0.1.0` is available from Maven Central. To try unreleased changes instead, [use a checkout](#use-a-checkout).

## Core dependency

Gradle Kotlin DSL:

<!-- procwright-docs: build-configuration -->
```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:procwright:0.1.0")
}
```

Gradle Groovy:

```groovy
repositories {
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

Next, [run the Java example](../getting-started.md#substitute-your-command) and
[inspect its result](../getting-started.md#use-the-result). The core has no runtime dependencies outside the JDK.

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

## Use a checkout

For unreleased changes, install all three modules from the checkout root into your local Maven repository:

```shell
./gradlew publishToMavenLocal \
  --project-prop=procwright.version=0.1.0-SNAPSHOT \
  --no-daemon
```

On Windows, run:

```powershell
.\gradlew.bat publishToMavenLocal --project-prop=procwright.version=0.1.0-SNAPSHOT --no-daemon
```

Use `0.1.0-SNAPSHOT` in your application's dependency declarations to keep this local build separate from release
`0.1.0`. For Gradle, add `mavenLocal()` before `mavenCentral()` in the application's `repositories` block. Maven uses
its local repository automatically. When switching to a published release, restore the release version and remove
`mavenLocal()` from Gradle's repository list.
