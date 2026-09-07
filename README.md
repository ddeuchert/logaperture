# LogAperture

**Runtime logging control for the JVM.** See, tune, and bound what your application logs — without restarting it, editing its configuration, or knowing in advance what will go wrong.

> ### Status: 0.1.0-alpha.1 — early, evaluation only
>
> **Not for production.** This is the first tagged build. It does real work on a running JVM — see [Try the alpha](#try-the-alpha) — but the feature set is partial, the override store's on-disk format may still change between builds with no migration, and there is no support commitment.
>
> Bug reports from an alpha run, prior-art pointers, and war stories about log volume in constrained environments are all useful — [open an issue](../../issues).

---

## The problem

**You cannot change a log level without a restart, or without editing a config file.** Every framework and every container solves this differently, most solutions are undiscoverable, and several persist the change into the very configuration file you did not want to touch.

**Developers work around it.** When raising a log level is harder than typing `System.out.println("*** HERE ***")`, people type the println. Occasionally it ships.

**Log volume is unbounded, and the cause is always a surprise.** An unexpected error path, an unusual usage pattern, or a bug that drops into a tight loop throwing exceptions. Nobody wrote a suppression rule for it in advance, because nobody knew it was coming. On a customer's machine with 40 GB of disk, that is not an inconvenience — it is an outage.

## The intent

- **Change log levels at runtime**, across frameworks and containers, from one command — and have the change **revert itself** on a timer, survive a restart if you ask it to, and never touch `standalone.xml`, `logback-spring.xml`, or anything else your application owns.
- **Catch log storms automatically**, with no configuration and no prior knowledge. Keep the first occurrence in full, collapse the repeats into a running count, say so in the log, and stop when the storm stops.
- **Attribute log volume** before suppressing any of it. Which logger is producing the gigabytes, what it would cost to trim, and what rule would do it.
- **Suppress precisely when you do know the shape of the noise** — by category, keyword, exception type, or cause chain, including trimming a stack trace without losing the line it came from.
- **Stay governable.** Suppressing logs means hiding activity, and raising verbosity means writing secrets to disk. Both need a capability model, an audit trail, and categories that can never be silenced or made more verbose.

## Try the alpha

`0.1.0-alpha.1` is a **measure-and-control** slice — it reads what your app logs, and it changes log levels. It does not suppress anything yet.

**Tested on:**

- **Standalone WildFly** (26.x, JBoss LogManager) — the full command set, end to end in a real server.
- **Plain `java -jar`** with **Logback** or **`java.util.logging`** — runtime level changes, enforced expiry, and persistence (a `sticky` override survives a real restart), verified cross-process.

Working today:

- **`logctl levels [glob]`** — every logger and its effective level; the glob finds a logger from the abbreviated name a log line actually printed.
- **`logctl debug <logger> for 30m`** / `trace` / `set <logger> <level> sticky` — change a level at runtime. It reverts on its own timer, survives a restart if you ask (`sticky`), and never touches `standalone.xml`, `logback-spring.xml`, or anything your application owns.
- **`logctl handler <name> <level>`** / **`logctl handlers`** — set a handler's own level (the fix when a raised logger still shows nothing because a handler is pinned stricter) and list the handler catalogue. On WildFly the handlers resolve to their real configured names (`CONSOLE`, `FILE`, …), read in-VM from the server's own model.
- **`logctl doctor`** — flag common logging-config problems: unbounded file-handler growth, verbosity left on, the same content written twice, autoflush on a busy handler, disk headroom vs. write rate.
- **`logctl top`** — bytes written per logger, worst-first, with a projected daily total and the stack-trace-byte fraction.
- **`logctl status`** / **`logctl reset --all`** — what LogAperture has changed, and undo all of it.

`doctor`, `top`, and `logctl handlers` currently inspect `java.util.logging` / JBoss LogManager only — on a Logback application they report nothing yet (a Logback pass is on the roadmap). Every change is capability-checked and written to a tamper-evident audit trail. The agent opens no sockets; `logctl` reaches it over the local attach API, UID-gated by the OS.

**Not in this build:** automatic storm collapse or any suppression, per-rule squelching, the Log4j 2 adapter, and any Spring Boot / Tomcat / Quarkus-JVM integration (a Spring Boot fat-jar attaches as a plain JVM, so level control *may* work against its Logback, but it is untested).

**Getting it:** download `logaperture-<version>.zip` from the [latest release](../../releases) and follow the bundled `INSTALL-wildfly.md`. [DEVELOPMENT.md](DEVELOPMENT.md) covers a plain-JVM setup and running `logctl`.

## Design principles

- **The agent opens no network connections**, inbound or outbound, ever. Interfaces are clients of the local attach API, not servers inside your JVM.
- **Never mutate configuration the container or application owns.**
- **Fail open on rule evaluation** — a bug here must never silence your logs. The one deliberate exception is the disk guard, which fails closed, loudly, and says so in the log.
- **Suppression is never silent.** Counts are always visible, so a log never contains an undetectable hole.
- **Measure before suppressing.** No suppression ships until the measurement tools (`doctor`, `top`, storm *detection*) are proven; the first suppression release runs dry-run first.

## Scope

Targeting Logback, Log4j 2, JBoss LogManager, and `java.util.logging`, across plain `java -jar`, WildFly, Spring Boot, Tomcat, and Quarkus JVM mode.

Quarkus native image is out of scope permanently — a GraalVM native executable has no JVM, no `-javaagent`, and no `Instrumentation`.

## Prior art

Parts of this exist elsewhere, and the design document says so in detail. [Arthas](https://arthas.aliyun.com/) already does cross-framework level changes and dynamic tracing from an attached CLI. Spring Boot Actuator exposes `/loggers`. Logback ships `DuplicateMessageFilter` and Log4j 2 ships `BurstFilter`. Byteman and BTrace do runtime instrumentation properly.

What none of them appear to do is combine runtime control with **persistence and expiry**, **zero-configuration storm collapse**, and an **on-premises, no-egress deployment model** — a constrained box owned by a customer, with a support engineer on the phone and no network path out.

If you know of something that does, please say so. Finding out early is worth more than a head start.

## Documentation

- [Design document](doc/logaperture-spec.md) — architecture, feature specification, security model, and roadmap.
- [DEVELOPMENT.md](DEVELOPMENT.md) — building, testing, running `logctl`, and the WildFly dev environment.

## Contributing

Code contributions are welcome once the alpha stabilises — the internals are still moving. For now the most useful things are bug reports from an actual alpha run, design feedback on [the spec](doc/logaperture-spec.md), prior-art pointers, and war stories about log volume in constrained environments, all in [issues](../../issues).

## License

Apache License 2.0.
