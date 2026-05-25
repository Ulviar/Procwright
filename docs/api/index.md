# API Docs

iCLI publishes generated Java API docs with the public site. Kotlin APIs are documented in the Kotlin reference page.

## Public API entry points

The Java links below work in the built or published documentation site. If you are reading this Markdown file on GitHub,
build the site locally with `./gradlew publicDocsCheck` and open `build/public-docs/api/index.html`.

- <a href="./java/core/">Core Java API</a>
- <a href="./java/integrations/">Integrations Java API</a>
- [Kotlin API](../reference/kotlin-api.md)

## Local commands

```bash
./gradlew publicDocsCheck
```

`publicDocsCheck` builds the MkDocs site and copies generated Java API docs into `build/public-docs/api/java/core/` and
`build/public-docs/api/java/integrations/`.
