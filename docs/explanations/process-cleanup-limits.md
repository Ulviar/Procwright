# Process Cleanup Limits

Procwright owns timeout and close behavior for processes it starts, but it is not an operating-system sandbox.

The runtime uses the JDK process model, including `ProcessHandle` descendant tracking where available. This covers the
ordinary failure modes that make process libraries useful: a command times out, a session is closed, a worker becomes
unusable, or a listener fails. In those cases Procwright applies the configured shutdown policy and drains owned streams.

One topology gets explicit handling in `run`: a descendant that inherited the command's stdout or stderr pipe and
outlives it. When the process has exited but an inherited output pipe is still open, the run fails with a
`CommandExecutionException` explaining that a descendant process may be holding the pipe, and forceful cleanup also
stops descendants recorded in a snapshot taken periodically while the process was alive. The snapshot is best-effort:
a descendant spawned in the last instant before exit may not appear in it and can survive cleanup.

Some process topologies can still escape that model. Detached descendants, inaccessible process handles, platform
limitations, or children that deliberately leave the parent tree can require caller-side containment.

Treat Procwright cleanup as the runtime-owned best effort inside the JDK process tree model. It is not a replacement for an OS
sandbox, container, job object, service manager, or CI runner isolation.
