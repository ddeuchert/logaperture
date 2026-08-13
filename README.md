# LogAperture

**Runtime logging control for the JVM.** See, tune, and bound what your application logs — without restarting it, editing its configuration, or knowing in advance what will go wrong.

> ### ⚠️ Status: design phase
>
> **There is no code here yet.** This repository currently holds a design document and nothing else. It is public early so the design can be argued with before it is implemented.
>
> If any of the problems below are ones you have, [open an issue](../../issues) — particularly if you have tried to solve them another way, or if you know of an existing project that already does this. Both would be genuinely useful.

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

## Design principles

- **The agent opens no network connections**, inbound or outbound, ever. Interfaces are clients of the local attach API, not servers inside your JVM.
- **Never mutate configuration the container or application owns.**
- **Fail open on rule evaluation** — a bug here must never silence your logs. The one deliberate exception is the disk guard, which fails closed, loudly, and says so in the log.
- **Suppression is never silent.** Counts are always visible, so a log never contains an undetectable hole.
- **Measure before suppressing.** The first release is read-only.

## Scope

Targeting Logback, Log4j 2, JBoss LogManager, and `java.util.logging`, across plain `java -jar`, WildFly, Spring Boot, Tomcat, and Quarkus JVM mode.

Quarkus native image is out of scope permanently — a GraalVM native executable has no JVM, no `-javaagent`, and no `Instrumentation`.

## Prior art

Parts of this exist elsewhere, and the design document says so in detail. [Arthas](https://arthas.aliyun.com/) already does cross-framework level changes and dynamic tracing from an attached CLI. Spring Boot Actuator exposes `/loggers`. Logback ships `DuplicateMessageFilter` and Log4j 2 ships `BurstFilter`. Byteman and BTrace do runtime instrumentation properly.

What none of them appear to do is combine runtime control with **persistence and expiry**, **zero-configuration storm collapse**, and an **on-premises, no-egress deployment model** — a constrained box owned by a customer, with a support engineer on the phone and no network path out.

If you know of something that does, please say so. Finding out early is worth more than a head start.

## Documentation

- [Design document](docs/design.md) — architecture, feature specification, security model, and roadmap.

## Contributing

Too early for code contributions. Design feedback, prior-art pointers, and war stories about log volume in constrained environments are all welcome in [issues](../../issues).

## License

Apache License 2.0.
