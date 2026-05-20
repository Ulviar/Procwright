# Installation

!!! warning "No stable artifact yet"
    iCLI is not published as a stable release. Do not treat these coordinates as available until a release candidate is
    cut and publishing/signing setup is implemented.

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

Build a specific Java release variant with the matching JDK:

```bash
./gradlew check --project-prop=icli.javaRelease=17
./gradlew check --project-prop=icli.javaRelease=21
./gradlew check --project-prop=icli.javaRelease=25
```

Publishing to Maven Central, signing, and final POM metadata require a separate implementation step before the first
public release.

## Publishing Status

The pre-release stabilization decision keeps publishing infrastructure out of the current branch. A focused publishing
change must add signing, POM metadata, local publication checks, and updated verification metadata before these
coordinates become real artifacts.
