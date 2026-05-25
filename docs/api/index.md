# API Docs

iCLI publishes Java generated API docs with the public site. Kotlin APIs are documented in the Kotlin reference page and
checked through KDoc in source.

## Public API entry points

- <a href="./java/core/">Core Java API</a>
- <a href="./java/integrations/">Integrations Java API</a>
- [Kotlin API](../reference/kotlin-api.md)

If you are reading this file directly from the source tree, the generated Java HTML is not stored under `docs/`. Build
the public site locally and open `build/public-docs/api/index.html`.

## Local commands

```bash
./gradlew publicDocsCheck
./gradlew :icli-kotlin:check
```

`publicDocsCheck` builds the MkDocs site and copies generated Java API docs into `build/public-docs/api/java/core/` and
`build/public-docs/api/java/integrations/`.
