# Release notes template

## Назначение

Этот template используется maintainers при подготовке публичных release notes. Итоговый файл release notes пишется на
английском, потому что он является пользовательским артефактом, но template хранится во внутреннем release context.

```markdown
# iCLI <version>

## Status

- Release type:
- Java baseline:
- Compatibility notes:

## Shipped behavior

-

## New public API

-

## Behavior changes

-

## Fixes

-

## Documentation

-

## Known limitations

-

## Stabilization decisions

-

## Verification

- `./gradlew releaseCandidateCheck`
- `./gradlew publicDocsCheck`
- generated Javadocs
- Kotlin KDoc coverage check
```
