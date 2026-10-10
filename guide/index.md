# LogAperture

**Runtime logging control for the JVM.** See, tune, and bound what your application logs, without
restarting it, editing its configuration, or knowing in advance what will go wrong.

LogAperture is a Java agent plus a command-line tool, `logctl`. The agent goes on the JVM's
`-javaagent` line; `logctl` talks to it on the same machine.

```sh
logctl set logger org.hibernate.SQL DEBUG for 30m   # more detail for half an hour, then back
logctl top                                           # which loggers write the most
logctl add rule drop com.acme.batch.Worker --message-contains "This happens a lot"
```

!!! warning "Pre-release"
    LogAperture 1.0 is in beta. Try it on a server you can afford to restart, and
    [tell us](https://github.com/ddeuchert/logaperture/issues) what breaks. It is not for
    production use before 1.0.0.

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

| | WildFly, standalone, 26.1 and later | Plain `java -jar` with Logback |
|---|---|---|
| Change and reset logger levels | ✓ | ✓ |
| Change and reset handler levels | ✓ | — |
| Vendor defaults file: levels | ✓ | ✓ |
| Vendor defaults file: rules | ✓ | — |
| Recipes | ✓ | ✓ |
| `drop` and `trim` rules | ✓ | — |
| `top` | ✓ | — |
| `doctor` | ✓ | — |
| Storm detection | ✓ | — |
| `status`, `env` | ✓ | ✓ |

Not supported in 1.0: WildFly domain mode, a plain JVM that logs through `java.util.logging`
alone, Log4j 2, Spring Boot, Tomcat, Jetty, Quarkus. Quarkus native images can never be supported:
they have no JVM to attach an agent to.

## Where to start

1. Install the agent: [on WildFly](get-started/install-wildfly.md) or
   [on a plain JVM](get-started/install-plain-jvm.md).
2. Try it for ten minutes: [Quick start](get-started/quick-start.md).
3. Learn what `reset` goes back to: [Configuration layers](concepts/configuration-layers.md).

Every `logctl` command is in the [command reference](reference/logctl.md), and on the command line
with `logctl help <command>`.
