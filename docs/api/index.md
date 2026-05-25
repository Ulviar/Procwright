# API Docs

iCLI publishes generated Java API docs with the public documentation site. Kotlin APIs are documented in the Kotlin
reference page.

## Public API entry points

- [Core Java API](https://ulviar.github.io/iCLI/api/java/core/)
- [Integrations Java API](https://ulviar.github.io/iCLI/api/java/integrations/)
- [Kotlin API](../reference/kotlin-api.md)

## Local commands

Use this only when working from a source checkout:

```bash
./gradlew publicDocsCheck
```

`publicDocsCheck` builds the MkDocs site and copies generated Java API docs into `build/public-docs/api/java/core/` and
`build/public-docs/api/java/integrations/`.
