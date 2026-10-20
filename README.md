# LogAperture

[![CI](https://github.com/ddeuchert/logaperture/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/ddeuchert/logaperture/actions/workflows/ci.yml?query=branch%3Adevelop)
[![Release](https://img.shields.io/github/v/release/ddeuchert/logaperture?include_prereleases&sort=semver)](https://github.com/ddeuchert/logaperture/releases)
[![Maven Central](https://img.shields.io/maven-central/v/org.logaperture/logaperture-agent)](https://central.sonatype.com/artifact/org.logaperture/logaperture-agent)
[![License](https://img.shields.io/github/license/ddeuchert/logaperture)](LICENSE)
[![User guide](https://img.shields.io/badge/guide-logaperture.org-blue)](https://logaperture.org/)

**Runtime logging control for the JVM.** See, tune, and bound what your application logs — without restarting it, editing its configuration, or knowing in advance what will go wrong.

**Install it, learn it, and look up every command at [logaperture.org](https://logaperture.org/).** Open source under the [Apache License 2.0](LICENSE).

> ### Status: 1.0.0-beta.2 — feature-complete for 1.0, testers wanted
>
> **Not for production yet.** Everything 1.0 will do is in this build — see [Try the beta](#try-the-beta). Until 1.0.0 only fixes, documentation and packaging change, plus any renames from the 1.0 review of commands and output, so a script written against this beta may need a small change at 1.0.0.
>
> Bug reports from a beta run, prior-art pointers, and war stories about log volume in constrained environments are all useful — [open an issue](../../issues).

**Have a WildFly server handy?** [Install it](https://logaperture.org/latest/get-started/install-wildfly/) and run the [Quick start](https://logaperture.org/latest/get-started/quick-start/) in about 10 minutes — raise a level and watch it revert, make the console show it, compare `for`/`session`/`sticky`, look at `doctor` and `top`. Feedback from that run is exactly what this beta needs.

[![A logger raised to TRACE shows nothing on the console until logctl puts the console handler in AUTO](https://logaperture.org/latest/assets/demos/why-no-trace.gif)](https://logaperture.org/latest/#see-it-work)

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

## Try the beta

`1.0.0-beta.2` runs on **standalone WildFly** (26.1 and later) and on **plain `java -jar` with Logback**, on JDK 17, 21 and 25. Not every feature works on both yet: the guide's [support table](https://logaperture.org/latest/#what-10-supports) says which.

- **Change a log level on the running JVM**, and have it revert on a timer, last until restart, or survive restarts. One command undoes everything.
- **`top` and `doctor`**: which loggers fill the disk, and logging configuration that will hurt you.
- **`drop` and `trim` rules** for noise you can name, and **storm detection** for bursts you didn't see coming.
- **Vendor defaults and recipes**, for shipping sane logging settings with a product.

**Get it** from the [Download page](https://logaperture.org/latest/get-started/download/) — the zip from GitHub Releases, or `org.logaperture` on Maven Central — and install it [on WildFly](https://logaperture.org/latest/get-started/install-wildfly/) or [on a plain JVM](https://logaperture.org/latest/get-started/install-plain-jvm/). Every command is in the [`logctl` reference](https://logaperture.org/latest/reference/logctl/). The guide is also in the zip, under `docs/`.

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

- [User guide at logaperture.org](https://logaperture.org/) — installing, a quick start, how-to pages and the `logctl` command reference.
- [Design document](doc/logaperture-spec.md) — architecture, feature specification, security model, and roadmap.
- [DEVELOPMENT.md](DEVELOPMENT.md) — building, testing, running `logctl`, and the WildFly dev environment.

## Contributing

Code contributions are welcome after 1.0 — until then the code is frozen to fixes. For now the most useful things are bug reports from an actual beta run, design feedback on [the spec](doc/logaperture-spec.md), prior-art pointers, and war stories about log volume in constrained environments, all in [issues](../../issues).

## License

Apache License 2.0.
