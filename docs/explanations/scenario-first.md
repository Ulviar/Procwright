# Choose the interaction before starting the process

A finite command needs output capture and an overall timeout. A long-lived worker needs request boundaries and a timeout
for each exchange. An interactive prompt needs matching rules. Those settings mean different things, so Procwright
keeps them on separate scenario builders.

Choose a scenario before `execute()` or `open()`:

- `run()` captures output and returns a result after one command finishes.
- `listen()` delivers text while a process runs.
- `lineSession()` and `protocolSession(...)` make repeated requests through one process.
- `interactive().expect()` matches prompts and sends replies.
- `interactive()` gives you raw streams when you need to manage the conversation yourself.

The choice also decides [who reads output](../reference/output-ownership.md). Two readers on the same stream would
compete for bytes, so an open raw session cannot later become an Expect or protocol session.

A direct worker session already reuses one process. Add a pool only when independent requests need concurrent workers
and any worker can handle any request. Use the [scenario chooser](../how-to/choose-process-scenario.md) to find the
walkthrough for your task.
