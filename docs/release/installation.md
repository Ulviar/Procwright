# Installation

No public release exists yet. Use Java 17 or newer; release artifacts target Java 17.

!!! note "No public repository yet"
    Install the planned `0.1.0` artifacts from the Procwright checkout with the exact command below. After the artifacts
    are published to a public Maven repository, replace `mavenLocal()` with the repository configuration required by
    that registry; the coordinates remain the same.

```shell
./gradlew publishToMavenLocal \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.version=0.1.0 \
  --no-daemon
```

## Core dependency

Gradle Kotlin DSL:

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

The six exported core packages are `@NullMarked` with JSpecify 1.0.0. Build tools receive JSpecify as published API
metadata so Kotlin and other nullness-aware consumers can enforce the contract; the core runtime itself still has no
dependency outside the JDK. On the module path, core declares `requires static transitive org.jspecify`.

## Optional modules

Use `procwright-kotlin` for Kotlin duration, coroutine, Flow, and adapter-factory extensions. Use
`procwright-integrations` for JSON and byte-framing protocol adapters.
`procwright-integrations` exposes `jackson-databind:2.22.0` transitively because Jackson types are part of its public
adapter API. Check your dependency constraints before adding it to an application that manages a different Jackson version.

The Kotlin artifact is the explicit JPMS module `io.github.ulviar.procwright.kotlin`. A named consumer needs only
`requires io.github.ulviar.procwright.kotlin`; core, Kotlin stdlib, and coroutines are readable through transitive module
requirements.

Gradle Kotlin DSL:

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
