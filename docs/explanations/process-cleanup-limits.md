# Process Cleanup Limits

iCLI owns timeout and close behavior for processes it starts, but it is not an operating-system sandbox.

The runtime uses the JDK process model, including `ProcessHandle` descendant tracking where available. This covers the
ordinary failure modes that make process libraries useful: a command times out, a session is closed, a worker becomes
unusable, or a listener fails. In those cases iCLI applies the configured shutdown policy and drains owned streams.

Some process topologies can still escape that model. Detached descendants, inaccessible process handles, platform
limitations, or children that deliberately leave the parent tree can require caller-side containment.

Treat iCLI cleanup as the runtime-owned best effort inside the JDK process tree model. It is not a replacement for an OS
sandbox, container, job object, service manager, or CI runner isolation.
