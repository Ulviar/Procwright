# Module procwright-kotlin

Kotlin extensions for running external programs through Procwright's Java scenario API.
The module requires Java 25 and adds Kotlin stdlib and kotlinx.coroutines as transitive dependencies.
Import extensions from `io.github.ulviar.procwright.kotlin` alongside the core types.

Choose [executeAwait][io.github.ulviar.procwright.kotlin.executeAwait] for a finite command,
[requestAwait][io.github.ulviar.procwright.kotlin.requestAwait] for a worker exchange, or
[openFlow][io.github.ulviar.procwright.kotlin.openFlow] to collect live output.
The Java draft, session, result, and exception contracts still apply; this module adds no separate process runtime.

# Package io.github.ulviar.procwright.kotlin

Kotlin durations, suspending operations, output Flow, and a protocol adapter factory DSL.

* **Finite commands:** [executeAwait] runs the blocking execution on an interruptible I/O dispatcher.
  Inspect the returned result: a nonzero exit or process timeout is not automatically an exception.
* **Worker requests:** [requestAwait] preserves the direct-session or pool failure contract.
  Cancelling an active exchange can close its session or retire its worker; cancelling a wait for a worker only abandons that wait.
* **Exit observation:** [awaitExit] cancels only its own wait, not the process. The caller still owns and must close the handle.
* **Output:** [openFlow] starts a fresh process per collection and closes it when collection is cancelled.
  It emits arbitrary text chunks and does not report the process exit code or timeout status.
* **Custom protocols:** [protocolAdapterFactory] creates a fresh adapter for every opened session or worker.
  Its handlers must follow [ProtocolAdapterFactoryDsl]'s framing, concurrency, and borrowed-I/O rules.

Duration extensions convert with [kotlin.time.toJavaDuration] and preserve the Java operation's validation.
Negative values are rejected. Zero disables only the limits whose function documentation explicitly says so;
request, match, readiness, acquisition, hook, and close deadlines must be positive.
Positive infinity is accepted through Kotlin's saturating conversion to a very large Java duration;
it is not a separate Procwright setting. Configuration extensions return new immutable drafts, so retain the returned value.

Suspending operations do not take ownership of an already opened session or pool. Use Kotlin `use` for those handles.
Core `open()` remains synchronous; this module does not provide an `openAwait()` function.
