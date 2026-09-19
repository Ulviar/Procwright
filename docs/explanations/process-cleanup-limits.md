# Process Cleanup Limits

Procwright owns timeout and close behavior for processes it starts, but it is not an operating-system sandbox.

The runtime uses the JDK process model, including `ProcessHandle` descendant tracking where available. This covers the
ordinary failure modes that make process libraries useful: a command times out, a session is closed, a worker becomes
unusable, or a listener fails. Procwright applies the configured shutdown policy in those cases. Natural helper
completion waits for its required logical output drain. Close and timeout may instead abandon noncooperative work
logically; physical pump, callback, and stream cleanup can continue independently.

One topology gets explicit handling in `run`: a descendant that inherited the command's stdout or stderr pipe and
outlives it. Output drain uses the same deadline as process waiting, so an inherited pipe that remains open produces a
timed-out `CommandResult` even when the root process exited normally. Cleanup also targets descendants observed while
the root was alive.

Helper scenarios also wait for their owned stdout/stderr pumps on natural completion. For `listen`, the configured
absolute timeout continues to apply after the root exits. Set it when a descendant may inherit a pipe; the default
disabled timeout preserves natural output drain for as long as the pipe remains open.

Line, protocol, and Expect sessions have no absolute drain timeout. Request and match timeouts bound their respective
operations; an idle timeout stops watching once the root process outcome is known. They do not bound a later wait for
`onExit()` while an inherited output pipe remains open. Keep the handle in a resource scope, wait with
`onExit().get(timeout, TimeUnit.SECONDS)`, and close the handle if that wait times out. The future wait timeout does not
close the process by itself. Explicit close abandons the outstanding drain logically and applies bounded cleanup;
physical stream close and a detached descendant may still require caller-side containment.

During graceful and forceful shutdown, Procwright refreshes the descendant set and retains observed reparented
descendants while they remain alive. Interactive-session close and pooled worker retirement use the same
observed-descendant cleanup. If a security policy or platform restriction blocks process-handle access, Procwright
still attempts to stop the root process, but it may be unable to stop an inaccessible descendant.

Waiting for process-provider operations is bounded. If an operation outlives that wait, it keeps its execution slot until
it returns; repeated scans cannot create unlimited blocked operations. Its late result cannot replace the selected
timeout or interruption. Delivery of late provider failures through uncaught-exception handlers is not guaranteed.

JDK process-tree observations are not atomic. A child that is created and fully detaches between observations may never
be seen and can survive cleanup. Detached descendants and processes that deliberately leave the parent tree can
therefore require caller-side containment.

User callbacks used for readiness, line or protocol response decoding, protocol request writing, and pool health/reset
run on a task thread. A deadline can release the calling workflow, but Java cannot forcibly terminate
callback code that ignores interruption. Such work may keep running on a daemon thread until it returns; the affected
handle or worker becomes unusable instead of starting more callbacks. Independent handles do not share a callback
admission quota.

Line-request encoding is synchronous and checks interruption and the request deadline between encoding steps. A custom
`Charset` implementation that does not return from a JDK method can still block its caller; Procwright does not add a
second execution subsystem solely to contain contract-violating charset implementations.

An explicit `Session.closeStdin()` is a requested operation, not terminal cleanup. Procwright first prevents later writes,
then requires the bounded dispatcher to admit and start the physical close. Successful handoff returns without waiting
for physical close. An admission or start failure performs bounded terminal cleanup before it is thrown. A later
physical-close failure also becomes terminal while the public outcome is not yet selected; it cannot replace an outcome
selected earlier.

Physical closing of stream wrappers during terminal cleanup is best-effort. Live sessions do not reserve dispatcher
slots. Each still-open wrapper gets one bounded close attempt; if the dispatcher is already full or cannot start the
task, Procwright reports a cleanup failure and does not create an unbounded fallback thread. Process termination and the
public result still complete. On natural exit, raw stdout and stderr remain caller-owned so unread output stays available.
The operating system normally releases process pipe endpoints when the process exits, but a rejected Java wrapper may
remain open until later JVM cleanup.

Treat Procwright cleanup as the runtime-owned best effort inside the JDK process tree model. It is not a replacement for
an OS sandbox, container, job object, service manager, or CI runner isolation.
