# Installation

Published artifacts are available from GitHub Packages.

Core dependency:

```text
dependencies {
    implementation("com.github.ulviar:icli:0.1.0")
}
```

Optional modules:

```text
dependencies {
    implementation("com.github.ulviar:icli-kotlin:0.1.0")
    implementation("com.github.ulviar:icli-integrations:0.1.0")
}
```

GitHub Packages consumers must add the package repository and authenticate with a token that can read packages:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Ulviar/iCLI")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
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

- GitHub Packages repository: `https://maven.pkg.github.com/Ulviar/iCLI`;
- credentials are read from `GITHUB_ACTOR` and `GITHUB_TOKEN`;
- signing uses in-memory `SIGNING_KEY` and `SIGNING_PASSWORD` when present;
- credentials and signing material are never stored in the repository;
- remote publication rejects `*-SNAPSHOT` or non-SemVer versions; the release job passes `icli.version` from the GitHub
  release tag;
- the release-only CI job publishes to GitHub Packages with scoped `packages: write` permission after the verification
  matrix passes.

Local publication check:

```bash
./gradlew publishToMavenLocal --project-prop=icli.javaRelease=17
```

Maven Central publication remains a future release-infrastructure step; GitHub Packages is the configured public
external-dependency path.
