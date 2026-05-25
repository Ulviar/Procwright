# Installation

Published releases are consumed from Maven Central. Use Java 17 or newer; public artifacts target Java 17.

## Core dependency

Gradle Kotlin DSL:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.ulviar:icli:0.1.0")
}
```

Gradle Groovy:

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.ulviar:icli:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>icli</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Optional modules

Use `icli-kotlin` for Kotlin receiver/coroutine helpers and `icli-integrations` for structured CLI-backed adapters.

Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation("io.github.ulviar:icli-kotlin:0.1.0")
    implementation("io.github.ulviar:icli-integrations:0.1.0")
}
```

Gradle Groovy:

```groovy
dependencies {
    implementation 'io.github.ulviar:icli-kotlin:0.1.0'
    implementation 'io.github.ulviar:icli-integrations:0.1.0'
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>icli-kotlin</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>io.github.ulviar</groupId>
    <artifactId>icli-integrations</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Source evaluation

Local source development uses the repository Gradle wrapper:

```bash
./gradlew check
./gradlew publicDocsCheck
```

To check source compatibility for a specific Java runtime, run Gradle with the matching JDK:

```bash
./gradlew check --project-prop=icli.javaRelease=17
./gradlew check --project-prop=icli.javaRelease=21
./gradlew check --project-prop=icli.javaRelease=25
```
