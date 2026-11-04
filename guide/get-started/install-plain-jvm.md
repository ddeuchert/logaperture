# Install on a plain JVM

For an application started with `java -jar` that logs through **Logback** or, from 1.1, through
**`java.util.logging`** (JUL) or **Log4j 2**. You add the agent to the `java` command and restart once.

!!! info "Logback: level control only"
    With Logback, LogAperture changes and resets logger levels, and applies the level settings of
    vendor defaults files and recipes. Handler levels, `drop` and `trim` rules, `top`, `doctor` and
    storm detection need `java.util.logging` or Log4j 2. An application on `java.util.logging` gets all of
    them, as on WildFly; Log4j 2 gets all but `top` (see [On Log4j 2](#on-log4j-2)). See the
    [support table](../index.md#what-10-supports).

If Logback is on the application's class path, LogAperture uses Logback, even if some code also
logs through `java.util.logging`. With `jul-to-slf4j` those records reach Logback and are
controlled there.

## What you need

- **JDK 17, 21 or 25** running the application, which logs through Logback (directly or through
  SLF4J), through `java.util.logging`, or through **Log4j 2.20.0 or later**.
- A **JDK**, not just a JRE, wherever you run `logctl`.
- `logctl` runs **on the same machine and as the same operating-system user** as the application.

## Install

1. **Download** the release zip, `logaperture-<version>.zip`, from the [Download](download.md)
   page, and unzip it, for example to `/opt/logaperture`.

    To fetch the agent with Maven or Gradle instead, for example into a container image, see
    [Download](download.md).

2. **Put `bin/` on your `PATH`**, so you can type plain `logctl`:

    ```sh
    export PATH="$PATH:/opt/logaperture/bin"
    ```

3. **Add the agent** to the `java` command, before `-jar`:

    ```sh
    java -javaagent:/opt/logaperture/lib/logaperture-agent.jar -jar myapp.jar
    ```

    If the application is started by a script you can't edit, the `JAVA_TOOL_OPTIONS`
    environment variable works too:

    ```sh
    export JAVA_TOOL_OPTIONS="-javaagent:/opt/logaperture/lib/logaperture-agent.jar"
    ```

!!! tip "Try it first"
    To see LogAperture on a running application before you change its launch line, use
    [`logctl attach`](../how-to/try-without-restart.md). It lasts until the application stops.

## Check it works

```sh
logctl env
logctl list loggers --show-all
```

`env` shows the agent's version and where it keeps its state file. The application's own log
also has one line from LogAperture at startup, `LogAperture <version> active (JVM)`. `env` also names
the logging backend: `Logging backend    Logback 1.5.13`, on `java.util.logging`,
`Logging backend    java.util.logging (JUL)`, or on Log4j 2, `Logging backend    Log4j 2 2.25.5`.
`list loggers --show-all` lists the application's loggers and their levels.

Then change one for a few minutes and watch the log:

```sh
logctl set logger com.example DEBUG for 5m
logctl status
```

The [Quick start](quick-start.md) is written for WildFly, but its level, tier and `reset` steps
work the same here.

## On `java.util.logging`

LogAperture starts the moment the application makes its first `java.util.logging` call, never
before, so the application's own `java.util.logging.manager` and `logging.properties` always take
effect. The startup line appears just after the application's first log line. A `sticky` setting
from the last run applies from then on; the very first lines go out at the application's own
levels.

The JDK's handlers are named by type: `console`, `file`, and `file-2` for a second one. Change
one with `logctl set handler console WARN`. The output below is from a small application whose
`logging.properties` writes to the console and to a file:

```sh
logctl list handlers --show-all
```

```
HANDLER           LEVEL  PERSISTS  TARGET           OVERRIDE
console           INFO   no        —                —
file              ALL    file      —                —
ALL_HANDLERS      —      —         —                —
DEFAULT_HANDLERS  —      —         (auto: console)  —
```

`set logger` takes the console along, as on WildFly:

```sh
logctl set logger com.example DEBUG for 5m
```

```
com.example → DEBUG   (FOR, reverts 21:47:32 local — in 4m)
handler console → AUTO, currently DEBUG   (follows com.example)
```

`TARGET` is empty for the `file` handler: the JDK keeps a `FileHandler`'s file name private.
`top` still counts what it writes, but `doctor` can't check that file's size limits or the disk it
is on.

If the application re-reads its configuration (`LogManager.readConfiguration()`), your changes are
put back straight away, on the new handlers the JDK creates.

## On Log4j 2

From 1.1, LogAperture controls a Log4j 2 of **2.20.0 or later**. It finds Log4j wherever the
application keeps it: on the JVM's class path, or inside a Spring Boot fat jar (next section). It never
touches Log4j before the application's own configuration has started, so `log4j2.xml`, the
`log4j2.configurationFile` property and a configuration the application sets in `main()` all apply as
they always did.

Almost everything works as on WildFly. `top` does not count Log4j 2 output yet (it says
`No byte-volume measurements available yet.`), and `doctor` and `trim` have the limits under
[Limits to know](#limits-to-know). The output below is from a small application on Log4j 2.25.5 with a
`Console` appender, a plain `File` appender named `App`, and a `com.example.Audit` logger that writes
only to `App`:

```sh
logctl list handlers --show-all
```

```
CONTEXT        HANDLER           LEVEL  PERSISTS  TARGET                 OVERRIDE
log4j2:log4j2  Console           ALL    no        —                      —
log4j2:log4j2  App               ALL    file      /var/log/demo/app.log  —
log4j2:log4j2  ALL_HANDLERS      —      —         —                      —
log4j2:log4j2  DEFAULT_HANDLERS  —      —         (auto: Console)        —
system         console           INFO   no        —                      —
system         ALL_HANDLERS      —      —         —                      —
system         DEFAULT_HANDLERS  —      —         (auto: console)        —
```

- **A handler is an appender, named as in `log4j2.xml`** (`Console`, `App`). Its level is the level of the
  appender's reference in each logger, and `logctl set handler Console WARN` sets it on every reference.
  An appender with no level reports `ALL`: nothing is filtered. An appender that sits only behind an
  `<Async>` appender is not listed: the `<Async>` appender is the handler, so give *its* name.
- **`system` is the JDK's own logging** (`java.util.logging`), which the connection `logctl` opens starts
  using. It has no application loggers; ignore it. A `set logger` goes to every context, so its rows appear
  under both.
- **Logger levels, `set logger`, rules and `doctor` work as in the rest of this guide.** An override
  survives Log4j re-reading its configuration (a `monitorInterval` reload, Spring Boot applying its
  settings): it is put back straight away, and `logctl status` still shows it.

```sh
logctl set logger com.example.Worker DEBUG for 30m --reason INC-123
```

```
com.example.Worker → DEBUG   (FOR, reverts 23:04:17 local — in 29m)
handler console → AUTO, currently DEBUG   (follows com.example.Worker)
```

Here only the JDK's `console` needed to follow: the Log4j `Console` appender has no level, so it never
held the lines back.

### When something else changes the level

Spring Boot Actuator's `POST /actuator/loggers/<name>`, or the application calling
`Configurator.setLevel`, changes a level without LogAperture. If that logger has an override, LogAperture
puts the override back, records it in the [audit log](../reference/audit-log.md), and says so once a minute
in the application's log:

```
[logaperture] WARN logger 'com.example.Worker' was changed to ERROR outside LogAperture, and the override on it was put back (DEBUG, FOR, set 8 s ago via jmx, reason: INC-123). 'logctl reset logger com.example.Worker' ends the override.
```

Actuator still answers `204`. A change made within five seconds of a Log4j reconfiguration is Spring
Boot's own start-up settings, and is put back without the warning. When the override ends, the logger goes
to the level that change set (`ERROR` here), not the one in the file. See
[Tiers and expiry](../concepts/tiers-and-expiry.md#when-the-application-changes-a-level-itself).

### Limits to know

- **`trim` works on text layouts** (a `PatternLayout`), not on JSON or other structured layouts, which
  are left alone. It does not work on **Log4j 2.25.0 to 2.25.3**: those versions render the exception
  from the class itself, with no hook for LogAperture. Rules still count; `doctor` says so
  (`log4j2.trim`). 2.24.x, 2.25.4 and later work.
- **`list loggers` shows a logger only while the application holds it** on Log4j 2.24.3 and later
  (2.24.0 and earlier keep them). A logger the code looked up once and dropped is gone from the list after
  a garbage collection; one kept in a `static final` field stays. `set logger` works on any name, and a logger declared in the configuration
  is always listed.
- **A `drop` rule that matches on the message text** formats the message once more to read it, so a
  parameter's `toString()` runs an extra time. Rules on the logger, the level or the unformatted pattern
  never do.
- **`doctor`** checks the file of a plain `File`, `RandomAccessFile` or `MemoryMappedFile` appender for a
  size cap and for disk space, and reports `handler.autoflush` for one that flushes every record (Log4j's
  default). A `RollingFile` appender is not inspected.
- **Changing a handler level swaps the appender inside its logger**: a log call at that instant is not
  written to that appender, and it ends up last in the logger's list. Rare, and the same for the `trim`
  wrapper LogAperture installs at start.
- **Older than 2.20.0** is not controlled: the application logs as it always did and `logctl doctor`
  says `log4j2.version`. See [Troubleshooting](../troubleshooting.md#log4j-2).

### A Spring Boot fat jar

A Spring Boot application on `spring-boot-starter-log4j2` keeps Log4j inside its jar, where the JVM's
class path cannot see it. LogAperture finds it there and needs nothing extra on the command line:

```sh
java -javaagent:/opt/logaperture/lib/logaperture-agent.jar -jar myapp.jar
```

It was tried on Spring Boot 3.5 (Log4j 2.24.3) and 4.1 (2.25.5). `logging.level.*` in
`application.properties` and Actuator's `/loggers` both work as above. Spring Boot on Logback in a fat jar
is not supported yet. These setups are checked by hand and not yet by automated tests; if one doesn't
work, [tell us](https://github.com/ddeuchert/logaperture/issues).

## Several applications on one machine

`logctl` picks the JVM by itself when exactly one is running with the agent. With several, on a
terminal it lists them to pick from; in a script, pass `--pid <n>`. See
[Work with several JVMs](../how-to/several-jvms.md).

Each JVM keeps its `sticky` changes in its own state file under `~/.logaperture/instances/`,
named after the directory the JVM was started from. Two applications started from the **same**
directory can't share one: the second one to start can't keep `sticky` changes, and says so in its
log. Give each its own state file with `-Dlogaperture.instanceId=<name>`.

## Uninstall

Remove the `-javaagent` option and restart the application. Optionally delete its state file,
whose path `logctl env` shows.
