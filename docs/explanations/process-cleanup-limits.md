# Process Cleanup Limits

Procwright owns timeout and close behavior for processes it starts, but it is not an operating-system sandbox.

The runtime uses the JDK process model, including `ProcessHandle` descendant tracking where available. This covers the
ordinary failure modes that make process libraries useful: a command times out, a session is closed, a worker becomes
unusable, or a listener fails. In those cases Procwright applies the configured shutdown policy and drains owned streams.

One topology gets explicit handling in `run`: a descendant that inherited the command's stdout or stderr pipe and
outlives it. When the process has exited but an inherited output pipe is still open, the run fails with a
`CommandExecutionException` explaining that a descendant process may be holding the pipe, and forceful cleanup also
targets descendants observed while the process was alive.

During graceful and forceful shutdown, Procwright refreshes the descendant set and retains observed reparented
descendants while they remain alive. Interactive-session close and pooled worker retirement use the same
observed-descendant cleanup. If a security policy or platform restriction blocks process-handle access, Procwright
still attempts to stop the root process, but it may be unable to stop an inaccessible descendant.

JDK process-tree observations are not atomic. A child that is created and fully detaches between observations may never
be seen and can survive cleanup. Detached descendants and processes that deliberately leave the parent tree can
therefore require caller-side containment.

User callbacks used for readiness, line or protocol encoding/decoding, pool health/reset, and diagnostics run behind
bounded library-managed execution capacity. Closing a raw session's stdin also uses bounded capacity so a blocked stream
close cannot trap the caller. A deadline can release the calling workflow, but Java cannot forcibly terminate arbitrary
callback or stream code that ignores interruption. Such work may keep running until it returns; Procwright bounds the
number of abandoned tasks instead of creating an unbounded number of threads.

Treat Procwright cleanup as the runtime-owned best effort inside the JDK process tree model. It is not a replacement for
an OS sandbox, container, job object, service manager, or CI runner isolation.
