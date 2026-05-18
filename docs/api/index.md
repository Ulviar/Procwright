# API

iCLI publishes generated API documentation as part of the release process.

Current local commands:

```bash
./gradlew publicDocsCheck
./gradlew :icli-kotlin:check
```

`publicDocsCheck` builds the MkDocs site and copies generated Java API docs into the public site output:

<ul>
  <li><a href="java/core/index.html">Core Java API</a></li>
  <li><a href="java/integrations/index.html">Integrations Java API</a></li>
</ul>

The optional Kotlin module currently enforces KDoc coverage in source. Generated Kotlin API docs are deliberately
deferred until the Kotlin API is ready for public stabilization and a Dokka publication decision is made.
