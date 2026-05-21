# Installation

!!! warning "No stable artifact yet"
    iCLI is not published as a stable release. The build now has publishing metadata and GitHub Packages configuration,
    but these coordinates are unavailable until a release candidate is cut through a GitHub release.

Planned Maven coordinates:

```text
dependencies {
    implementation("com.github.ulviar:icli:<version>")
}
```

Optional modules:

```text
dependencies {
    implementation("com.github.ulviar:icli-kotlin:<version>")
    implementation("com.github.ulviar:icli-integrations:<version>")
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

Current local development uses the repository Gradle wrapper:

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
- remote publication rejects `*-SNAPSHOT` or non-SemVer versions; valid RC tag shapes include `v0.1.0-rc.1` and
  `0.1.0-rc.1`, and the release job passes `icli.version` from the GitHub release tag;
- the release-only CI job publishes to GitHub Packages with scoped `packages: write` permission after the verification
  matrix passes.

Local publication check:

```bash
./gradlew publishToMavenLocal --project-prop=icli.javaRelease=17
```

Maven Central publication remains a future release-infrastructure step; GitHub Packages is the configured
external-dependency path after a release candidate is published.
