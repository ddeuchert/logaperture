# LogAperture

**Runtime logging control for the JVM.** See, tune, and bound what your application logs, without
restarting it, editing its configuration, or knowing in advance what will go wrong.

Open source under the [Apache License 2.0](https://github.com/ddeuchert/logaperture/blob/main/LICENSE).

LogAperture is a Java agent plus a command-line tool, `logctl`. The agent goes on the JVM's
`-javaagent` line; `logctl` talks to it on the same machine.

```sh
logctl set logger org.hibernate.SQL DEBUG for 30m   # more detail for half an hour, then back
logctl top                                           # which loggers write the most
logctl add rule drop com.acme.batch.Worker --message-contains "This happens a lot"
```

!!! warning "Pre-release"
    LogAperture 1.0 is in beta. Try it on a server you can afford to restart, and
    [tell us](https://github.com/ddeuchert/logaperture/issues) what breaks, or
    [how it went](https://github.com/ddeuchert/logaperture/discussions/245). It is not for
    production use before 1.0.0.

## See it work

A logger raised to `TRACE` on a running WildFly. The console's own handler is at `INFO`, so it
follows the change to let the `TRACE` lines through, and both revert on their own. The top pane is
the WildFly console; `logctl` runs below.

<div class="la-cast" data-cast="why-no-trace"></div>

More films in [Now Showing](now-showing.md).

## What it does

- **Change log levels on a running JVM**, and have the change undo itself on a timer, last until
  restart, or survive restarts: your choice, per change. See [Raise a log level](how-to/raise-a-level.md).
- **Undo everything it did** with one command, back to how the application was configured. See
  [Undo changes](how-to/undo-changes.md).
- **Show where log volume comes from** (`top`) and **flag logging configuration that will hurt you**
  (`doctor`). See [Find where the disk goes](how-to/find-disk-usage.md).
- **Silence a known-noisy message**, or keep it and shorten its stack trace, without changing the
  logger's level. See [Silence noise](how-to/silence-noise.md).
- **Spot log storms**: bursts of near-identical messages from one logger. See [Log storms](how-to/storms.md).
- **Ship sane logging defaults with a product**, so customers don't start with noisy logs. See
  [Vendor defaults](vendors/vendor-defaults.md).

## What it will never do

- **Edit configuration the application or server owns.** `standalone.xml`, `logback.xml` and
  `logging.properties` are read, never written. LogAperture keeps its changes in its own state file.
- **Open a network connection.** The agent listens on no port and connects nowhere. `logctl` reaches
  it through the JVM's local attach mechanism, which the operating system restricts to the user
  running the JVM.
- **Hide a log line without saying so.** Every dropped event is counted and reported in the log.

## What 1.0 supports

JDK 17, 21 and 25.

| | WildFly, standalone, 26.1 and later | Plain `java -jar` with Logback | Plain `java -jar` with `java.util.logging` (from 1.1) | Log4j 2, 2.20.0 and later (from 1.1) |
|---|---|---|---|---|
| Change and reset logger levels | ✓ | ✓ | ✓ | ✓ |
| Change and reset handler levels | ✓ | — | ✓ | ✓ (by appender name) |
| Vendor defaults file: levels | ✓ | ✓ | ✓ | ✓ |
| Vendor defaults file: rules | ✓ | — | ✓ | ✓ |
| Recipes | ✓ | ✓ | ✓ | ✓ |
| `drop` and `trim` rules | ✓ | — | ✓ | ✓ (`trim` for text layouts, and not on 2.25.0 to 2.25.3) |
| `top` | ✓ | — | ✓ | — |
| `doctor` | ✓ | — | ✓ (no file-size or disk checks on the JDK's `FileHandler`) | ✓ (file checks for plain file appenders only) |
| Storm detection | ✓ | — | ✓ | ✓ |
| `status`, `env` | ✓ | ✓ | ✓ | ✓ |

From 1.1, Log4j 2 is controlled wherever it lives: on the JVM's class path, or bundled inside the
application: a **Spring Boot fat jar** on `spring-boot-starter-log4j2`, a **Tomcat webapp** that
brings its own `log4j-core`, or a **WildFly deployment** that excludes the server's Log4j API. See
[plain JVM](get-started/install-plain-jvm.md#on-log4j-2),
[Tomcat](get-started/install-tomcat.md) and
[WildFly](get-started/install-wildfly.md#a-deployment-with-its-own-log4j-2).

Not supported: WildFly domain mode, Logback in a Spring Boot fat jar or a Tomcat webapp (planned),
Spring Boot's own logging integration, Jetty, and Log4j 1 or reload4j. Quarkus is supported from
1.1. Quarkus native images can never be supported: they have no JVM to attach an agent to.

## Where to start

1. Get it: the release zip, or Maven Central for a build: [Download](get-started/download.md).
2. Install the agent: [on WildFly](get-started/install-wildfly.md),
   [on Quarkus](get-started/install-quarkus.md) (from 1.1),
   [on Tomcat](get-started/install-tomcat.md) (from 1.1) or
   [on a plain JVM](get-started/install-plain-jvm.md).
3. Try it for ten minutes: [Quick start](get-started/quick-start.md).
4. Learn what `reset` goes back to: [Configuration layers](concepts/configuration-layers.md).
5. See what it costs per log call: [Overhead](overhead.md).

Every `logctl` command is in the [command reference](reference/logctl.md), and on the command line
with `logctl help <command>`.
