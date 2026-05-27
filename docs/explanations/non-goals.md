# Non-goals

Procwright is a scenario-first process automation library. It is not intended to become a general automation platform around
every possible way a program can start another program.

## Remote build-agent launcher

Procwright core does not model remote agents, remoting channels, distributed workspaces, remote stream forwarding, or build
step secret masking. Those belong to CI/build platforms.

The useful lesson from such systems is narrower: output ownership, environment boundaries, process-tree cleanup, and
diagnostics must be explicit.

## SSH, Telnet, and remote shell framework

`Expect` automates prompt-oriented interaction over a Procwright `Session`. The current core does not provide SSH or Telnet
transports.

Remote stream adapters may be possible outside core, but they should not make local process scenarios depend on a remote
connection model.

## High-concurrency native process backend as public API

Native or non-blocking process libraries can be useful implementation research for workloads with many concurrent child
processes. They should not leak into the Procwright public surface.

If Procwright ever adds an alternate process backend, it should sit behind a narrow internal or optional SPI and preserve the
same scenario contracts.

## Server orchestration framework

Procwright can start a process, drain output, apply timeout/shutdown policy, and expose diagnostics. It does not define what
"ready" means for an arbitrary server.

HTTP polling, socket checks, PID files, health endpoints, and domain-specific log matching belong to the calling
application unless they become a repeated, tested Procwright scenario.

## Shell abstraction layer

Procwright does not try to normalize shell quoting, shell built-ins, or platform command languages. Direct argv is the default.
Shell execution is available only as an explicit boundary.

## Tool installation and executable discovery

Procwright does not install tools, manage versions, probe package managers, or decide whether `git`, `docker`, `mvn`, or a
domain CLI should exist on PATH.

Applications may build those decisions above Procwright and then pass the resolved executable or command specification into a
scenario.
