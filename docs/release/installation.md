# Installation

Published releases are consumed from Maven Central.

Core dependency:

```text
dependencies {
    implementation("io.github.ulviar:icli:0.1.0")
}
```

Optional modules:

```text
dependencies {
    implementation("io.github.ulviar:icli-kotlin:0.1.0")
    implementation("io.github.ulviar:icli-integrations:0.1.0")
}
```

Consumers only need Maven Central in their repository list:

```kotlin
repositories {
    mavenCentral()
}
```

Local source development uses the repository Gradle wrapper:

```bash
./gradlew check
./gradlew publicDocsCheck
```

Build a specific Java release variant with the matching JDK:

```bash
./gradlew check --project-prop=icli.javaRelease=17
./gradlew check --project-prop=icli.javaRelease=21
./gradlew check --project-prop=icli.javaRelease=25
```

Public artifacts must be built with `--project-prop=icli.javaRelease=17`. Java 21 and Java 25 remain checked source
variants, not separate public coordinates.
