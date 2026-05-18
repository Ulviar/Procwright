# Installation

!!! warning "No stable artifact yet"
    iCLI is not published as a stable release. Do not treat these coordinates as available until a release candidate is
    cut and the publishing ADR is accepted.

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

Current local development uses the repository Gradle wrapper:

```bash
./gradlew check
./gradlew publicDocsCheck
```

Publishing to Maven Central, signing, and final POM metadata require a separate ADR before the first public release.
