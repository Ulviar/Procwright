# Wrap a CLI tool

Use the optional integrations module when a CLI speaks JSON Lines, delimiter-framed bytes, or Content-Length JSON and
should expose domain request and response types.

Add the integrations artifact alongside core:

```kotlin
dependencies {
    implementation("io.github.ulviar:procwright-integrations:0.1.0")
}
```

See [installation](../release/installation.md#optional-modules) for Maven and Gradle Groovy syntax.

```java
--8<-- "examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java"
```

[Open `TypedContentLengthJsonSessionExample.java`](../examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java)
and the [optional-module example sources](../examples.md#optional-modules).

Replace `workerCommand()` with the real executable and arguments. Keep `typedJsonSession(...)` for domain mapping and
choose the transport factory that matches the tool's wire protocol. The example sets the adapter's response-body limit
and the scenario's full-wire limits separately; see the [integration contract](../scenarios/integrations.md).

The wrapper does not install or discover the executable. Resolve and validate it in your application, and handle
`ProtocolSessionException` without parsing messages.
