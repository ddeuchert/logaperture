# Install on Quarkus

!!! note "New in 1.1"
    Quarkus support arrives in LogAperture 1.1. This page describes it.

You add one `-javaagent` option to the command that starts your Quarkus application and restart
it once. After that, `logctl` changes logging on the running application. LogAperture never edits
`application.properties`.

## What you need

- A Quarkus application running in **JVM mode**: `java -jar quarkus-run.jar` (the default
  `fast-jar` package), or an uber-jar. **Native images are never supported**: they have no JVM to
  attach an agent to.
- **Quarkus 3.39** or the **3.33 LTS** stream. These are the versions LogAperture is tested on.
  Another Quarkus version isn't blocked, but it runs in a reduced mode: see
  [Other Quarkus versions](#other-quarkus-versions).
- **JDK 17, 21 or 25** running the application.
- A **JDK**, not just a JRE, wherever you run `logctl`.
- `logctl` runs **on the same machine and as the same operating-system user** as the application.

## Install

1. **Download** the release zip and unzip it, as in [Install on WildFly](install-wildfly.md#install)
   steps 1 and 2. You need `bin/logctl` and `lib/logaperture-agent.jar`.

2. **Add the agent** to the command that starts the application:

    ```sh
    java -javaagent:/opt/logaperture/lib/logaperture-agent.jar -jar quarkus-run.jar
    ```

    In a Quarkus container image, set the variable the image's start script reads:

    ```sh
    JAVA_OPTS_APPEND="-javaagent:/opt/logaperture/lib/logaperture-agent.jar"
    ```

    If the application already has other `-javaagent` entries, put LogAperture's first. See
    [Agent order](../concepts/agent-order.md).

    If the user running the application has no writable home directory, as in many containers, point
    LogAperture's state at a writable place: `-Dlogaperture.home=/tmp/logaperture`.

3. **Restart the application.**

## Check it works

The application's output has one line from LogAperture, before Quarkus's own banner:

```
[logaperture] LogAperture 1.0.0-beta.2-SNAPSHOT active (Quarkus)
```

Then run `logctl env`:

```
LogAperture agent    1.0.0-beta.2-SNAPSHOT  (logctl 1.0.0-beta.2-SNAPSHOT)
Java                 21.0.12  Eclipse Adoptium
OS                   Linux 7.2.10-200.fc44.x86_64  amd64
Logging backend      JBoss LogManager 3.0.6.Final
Framework/container  Quarkus 3.33.4
Diagnostics level    —
State file           /tmp/logaperture/instances/f7b982ff04e06fc3-cap.state.yaml
Vendor defaults      not configured
```

Your versions and paths will differ; this is a small sample application. `Framework/container` must name Quarkus. If it doesn't,
LogAperture didn't recognize the application: check that it was started with `quarkus-run.jar` (or an
uber-jar) and not another launcher. If `logctl` says it can't find a JVM, see
[Work with several JVMs](../how-to/several-jvms.md).

Next: the [Quick start](quick-start.md). Its commands work on Quarkus too, with the logger and
handler names of your application.

## Handler names

Quarkus keeps its console and file handlers inside one internal handler. LogAperture looks inside
it, and names the handlers by what they are (paths here are from a test run):

```
HANDLER           LEVEL  PERSISTS  TARGET                  OVERRIDE
ALL_HANDLERS      —      —         —                       —
console           ALL    no        —                       —
file              ALL    file      /tmp/cap/app.log        —
DEFAULT_HANDLERS  —      —         (auto: console)         —
```

The names are `console`, `file`, `syslog` and `socket`. A second handler of one type is numbered:
`file-2`. The names you gave handlers in `application.properties`
(`quarkus.log.handler.file."AUDIT"`) aren't used. Use `logctl set handler file DEBUG`, for example,
to let a raised logger's lines through a handler that is set higher.

## The build's minimum level

`quarkus.log.min-level` is fixed when you build the application, and Quarkus then lets nothing more
verbose through, whatever a logger's level says. The default is `DEBUG`. So an application built with
the default can show `DEBUG`, but never `TRACE`. LogAperture tells you instead of doing nothing:

```
$ logctl set logger org.logaperture.sample.quarkus.Worker TRACE for 5m
org.logaperture.sample.quarkus.Worker → TRACE   (FOR, reverts 13:15:16 local — in 4m)
WARN: org.logaperture.sample.quarkus.Worker is set to TRACE but the logging backend only emits DEBUG and above for it; nothing more verbose will appear.
      the application was built with quarkus.log.min-level=DEBUG; rebuild with a lower quarkus.log.min-level (for example TRACE) to log below it.
```

`logctl list loggers` shows the level that is actually emitted (`DEBUG` here), and `logctl doctor`
reports it ([`logger.below-floor`](../reference/doctor-checks.md#loggerbelow-floor)). To see `TRACE`,
rebuild with `quarkus.log.min-level=TRACE`.

## Limits

- A `quarkus.log.category."X".min-level` setting only applies when Quarkus starts. A level you set
  with `logctl` isn't held to it.
- In dev mode (`quarkus:dev`) the minimum level is `ALL`, so `TRACE` works there even when it doesn't
  in your production build. Dev mode isn't a supported target, though it works.
- `logctl top` measures bytes written to files, and Quarkus logs to the console by default. Turn on
  the file handler (`quarkus.log.file.enabled=true`) to give `top` something to measure.
- Asynchronous handlers (`quarkus.log.*.async.enable`) aren't supported.
- `syslog` and `socket` handlers are named and listed, but not otherwise tested.
- A plain `java -jar` application that logs through `java.util.logging` alone, without Quarkus, is
  not covered by this page.

### Other Quarkus versions

LogAperture re-applies what it changed when Quarkus finishes its start-up logging setup. It does
that by asking Quarkus's own logging handler, and as a safety net it also re-checks every quarter of
a second for the first 15 seconds. On a Quarkus version where the first doesn't work, `logctl doctor`
says so (`agent.early-sweep`) and the second still covers it. Settings made through `logctl` keep
working either way.

## Uninstall

Remove the `-javaagent` option and restart. Nothing else was changed. LogAperture's state file is
under `~/.logaperture/` of the user running the application, unless `-Dlogaperture.home` moved it.

## If the application won't start

Add `-Dlogaperture.disabled=true` to the command line and restart. The agent then does nothing at
all. If the application starts that way, LogAperture is involved: please
[open an issue](https://github.com/ddeuchert/logaperture/issues) with the output and `java -version`.
