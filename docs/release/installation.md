# Installation

Published releases are consumed from Maven Central. Use Java 17 or newer; public artifacts target Java 17.

## Core dependency

Gradle Kotlin DSL:

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

## Optional modules

Use `procwright-kotlin` for Kotlin receiver/coroutine helpers and `procwright-integrations` for structured CLI-backed adapters.

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
