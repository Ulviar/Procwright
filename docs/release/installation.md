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

## Publishing Status

Configured publishing target:

- Maven Central via the Central Portal upload API;
- public namespace and coordinates: `io.github.ulviar`;
- Central Portal credentials are read from `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`;
- signing uses in-memory `SIGNING_KEY` and `SIGNING_PASSWORD` and is mandatory for Central publication;
- credentials and signing material are never stored in the repository;
- remote publication rejects `*-SNAPSHOT` or non-SemVer versions;
- public artifacts are built with `--project-prop=icli.javaRelease=17`;
- `mavenCentralBundle` builds a signed Maven repository bundle with checksums;
- the release-only CI job uploads a `USER_MANAGED` Central Portal deployment after the verification matrix passes.

Local publication check:

```bash
./gradlew publishToMavenLocal --project-prop=icli.javaRelease=17
```

Central publication requires a verified `io.github.ulviar` namespace in Sonatype Central Portal before the first release
can be uploaded successfully.
