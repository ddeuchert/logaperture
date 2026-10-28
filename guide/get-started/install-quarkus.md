# Install on Quarkus

!!! note "New in 1.1"
    Quarkus support arrives in LogAperture 1.1. This page describes it.

There are two ways to add LogAperture to a Quarkus application. Either way, `logctl` then changes
logging on the running application, and LogAperture never edits `application.properties`.

- **The LogAperture extension** (recommended): add one dependency and rebuild. Nothing changes in how
  the application is started, so it suits platforms where you don't control the command line, such
  as Kubernetes or Azure App Service.
- **The agent**: add a `-javaagent` option to the command that starts the application, with no
  rebuild.

Use one or the other. If both are present, the agent runs and the extension does nothing; see
[Agent and extension together](#agent-and-extension-together).

## What you need

- A Quarkus application running in **JVM mode**: `java -jar quarkus-run.jar` (the default
  `fast-jar` package), or an uber-jar. **Native images are not supported.**
- **Quarkus 3.39** or the **3.33 LTS** stream. These are the versions LogAperture is tested on.
  With the agent, another Quarkus version isn't blocked but runs in a reduced mode: see
  [Other Quarkus versions](#other-quarkus-versions). The extension needs 3.33 or later.
- **JDK 17, 21 or 25** running the application.
- A **JDK**, not just a JRE, wherever you run `logctl`.
- `logctl` runs **on the same machine and as the same operating-system user** as the application.
  In Kubernetes, see [Run logctl in Kubernetes](#run-logctl-in-kubernetes).

## Install the extension

1. **Add the dependency** to the application's `pom.xml`:

    ```xml
    <dependency>
      <groupId>org.logaperture</groupId>
      <artifactId>logaperture-quarkus</artifactId>
      <version>1.1.0</version>
    </dependency>
    ```

    Or with the Quarkus CLI: `quarkus ext add org.logaperture:logaperture-quarkus:1.1.0`. With Gradle:
    `implementation("org.logaperture:logaperture-quarkus:1.1.0")`.

2. **Rebuild and deploy** the application as usual. The start command doesn't change.

3. **Settings**, if you need any, are `quarkus.logaperture.*` properties, which can come from
   `application.properties`, environment variables or a ConfigMap. Most applications need none. In a
   container whose user has no writable home directory, give LogAperture's state a writable place:

    ```sh
    QUARKUS_LOGAPERTURE_HOME=/tmp/logaperture
    ```

    See [Quarkus extension configuration](../reference/quarkus-extension-config.md) for every property.
    To ship logging settings with the application, bundle a
    [vendor defaults file](../vendors/vendor-defaults.md#in-a-quarkus-application) in it.

You also need `logctl`, from the release zip: download and unzip it as in
[Install on WildFly](install-wildfly.md#install) steps 1 and 2. You need `bin/logctl`.

## Or install the agent

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

The application's output has one line from LogAperture, before Quarkus's own banner. With the
extension it says `Quarkus extension`; with the agent, `Quarkus`:

```
[logaperture] LogAperture 1.1.0-SNAPSHOT active (Quarkus extension)
```

Then run `logctl env`:

```
LogAperture extension  1.1.0-SNAPSHOT  (logctl 1.1.0-SNAPSHOT)
Java                   21.0.12  Eclipse Adoptium
OS                     Linux 7.2.10-200.fc44.x86_64  amd64
Logging backend        JBoss LogManager 3.2.2.Final
Framework/container    Quarkus 3.39.5
Diagnostics level      —
State file             /tmp/logaperture/instances/e7e1b29c1dd098b2-cap.state.yaml
Vendor defaults        not configured
```

With the agent, the first line reads `LogAperture agent`. Your versions and paths will differ; this
is a small sample application. `Framework/container` must name Quarkus. If it doesn't, LogAperture
didn't recognize the application: check that it was started with `quarkus-run.jar` (or an uber-jar)
and not another launcher. If `logctl` says it can't find a JVM, see
[Work with several JVMs](../how-to/several-jvms.md).

Next: the [Quick start](quick-start.md). Its commands work on Quarkus too, with the logger and
handler names of your application.

## Run logctl in Kubernetes

Quarkus's container images usually carry a JRE, and `logctl` needs a JDK. Run `logctl` from a
debug container in the application's pod instead, with an image that has a JDK and `logctl`:

```sh
kubectl debug -it <pod> --image=<image with a JDK and logctl> --target=<application container> \
  -- /opt/logaperture/bin/logctl env
```

`--target` puts the debug container in the application container's process namespace, and every
container in a pod shares its network, which is what `logctl` needs. The debug container must run as
the same user as the application (in Quarkus's own images, user `185`); set it with a
`--profile` or a custom profile that sets `runAsUser`.

!!! note
    This route was checked with containers that share the process and network namespaces the way a
    pod's do, with the application in a JRE-only image. It has not yet been run on a Kubernetes
    cluster.

## Handler names

Quarkus keeps its console and file handlers inside one internal handler. LogAperture looks inside
it. A handler you named in `application.properties` shows under that name, and Quarkus's own
console and file handlers show by what they are (paths here are from a test run):

```properties
quarkus.log.handler.file."AUDIT".enable=true
quarkus.log.handler.file."AUDIT".path=/tmp/cap/audit.log
quarkus.log.category."org.example".handlers=AUDIT
```

```
HANDLER           LEVEL  PERSISTS  TARGET                     OVERRIDE
ALL_HANDLERS      —      —         —                          —
console           ALL    no        —                          —
file              ALL    file      /tmp/cap/quarkus-file.log  —
AUDIT             DEBUG  file      /tmp/cap/audit.log         DEBUG (FOR, reverts in 3h 59m)
DEFAULT_HANDLERS  —      —         (auto: console)            —
```

`logctl set handler AUDIT DEBUG` changes that handler, as `FILE` is changed on WildFly. A handler
listed under `quarkus.log.handlers` (the root) is named the same way. A named file handler needs
`enable=true`: without it Quarkus attaches nothing and logs "linked to a category but not
configured".

The handlers Quarkus creates itself are `console`, `file`, `syslog` and `socket`. A handler whose
name could not be matched is numbered by type, for example `file-2`, and so is a handler whose
`handlers` list does not line up with the handlers Quarkus attached. If you name a handler
`console`, that name is yours and Quarkus's own console handler becomes `console-2`. Names are
case-sensitive, as written in the configuration. `logctl doctor` reports
[`quarkus.handler-names`](../reference/doctor-checks.md#quarkushandler-names) if the names could not
be read at all. Use `logctl set handler file DEBUG`, for example, to let a raised logger's lines
through a handler that is set higher.

## The build's minimum level

`quarkus.log.min-level` is fixed when you build the application, and Quarkus then lets nothing more
verbose through, whatever a logger's level says.

**With the extension**, the minimum level is `TRACE`, so every level works without a rebuild:

```
$ logctl set logger org.logaperture.sample.quarkus.Worker TRACE for 5m
org.logaperture.sample.quarkus.Worker → TRACE   (FOR, reverts 12:46:48 local — in 4m)
```

Nothing more is logged until you set a logger that low. If you set `quarkus.log.min-level` yourself
in `application.properties`, your setting wins.

**With the agent**, Quarkus's default of `DEBUG` stays, so an application built with the default can
show `DEBUG` but never `TRACE`. LogAperture tells you instead of doing nothing:

```
$ logctl set logger org.logaperture.sample.quarkus.Worker TRACE for 5m
org.logaperture.sample.quarkus.Worker → TRACE   (FOR, reverts 12:46:54 local — in 4m)
WARN: org.logaperture.sample.quarkus.Worker is set to TRACE but the logging backend only emits DEBUG and above for it; nothing more verbose will appear.
      the application was built with quarkus.log.min-level=DEBUG; rebuild with a lower quarkus.log.min-level (for example TRACE) to log below it.
```

`logctl list loggers` shows the level that is actually emitted (`DEBUG` here), and `logctl doctor`
reports it ([`logger.below-floor`](../reference/doctor-checks.md#loggerbelow-floor)). To see `TRACE`,
rebuild with `quarkus.log.min-level=TRACE`, or use the extension.

## Dev mode

With the extension, `quarkus dev` is supported. `logctl` works on the dev-mode application, and
after each live reload LogAperture starts again with the settings you made `sticky` or `for` a time.
Settings made for the session only lapse at a reload, and a changed `quarkus.logaperture.*` property
applies from the next reload. `logctl doctor` reports that the Dev UI's log
stream is left out of `trim` rules and `top`
([`handler.formatter-kept`](../reference/doctor-checks.md#handlerformatter-kept)).

With the agent, dev mode works but isn't a supported target.

## Limits

- A `quarkus.log.category."X".min-level` setting only applies when Quarkus starts. A level you set
  with `logctl` isn't held to it.
- With the agent, dev mode runs with a minimum level of `ALL`, so `TRACE` works there even when it
  doesn't in your production build.
- `logctl top` measures bytes written to files, and Quarkus logs to the console by default. Turn on
  the file handler (`quarkus.log.file.enabled=true`) to give `top` something to measure.
- Asynchronous handlers (`quarkus.log.*.async.enable`) aren't supported.
- `syslog` and `socket` handlers, named or not, are listed but not otherwise tested.
- A plain `java -jar` application that logs through `java.util.logging` alone, without Quarkus, is
  not covered by this page.

### Other Quarkus versions

With the agent, LogAperture re-applies what it changed when Quarkus finishes its start-up logging
setup. It does that by asking Quarkus's own logging handler, and as a safety net it also re-checks
every quarter of a second for the first 15 seconds. On a Quarkus version where the first doesn't
work, `logctl doctor` says so (`agent.early-sweep`) and the second still covers it. Settings made
through `logctl` keep working either way. The extension starts after Quarkus's logging setup, so it
needs neither.

## Agent and extension together

If the application has the extension and is also started with the agent, the agent runs and the
extension starts nothing. This holds for an agent from 1.0 too. The application's output says so:

```
[logaperture] LogAperture 1.1.0-SNAPSHOT active (Quarkus)
[logaperture] The LogAperture agent is running in this JVM; the Quarkus extension starts nothing.
```

Remove one of them.

## Uninstall

- **Extension:** remove the dependency and rebuild.
- **Agent:** remove the `-javaagent` option and restart.

Nothing else was changed. LogAperture's state file is under `~/.logaperture/` of the user running
the application, unless `quarkus.logaperture.home` or `-Dlogaperture.home` moved it.

## If the application won't start

Turn LogAperture off and start again: with the extension, set `QUARKUS_LOGAPERTURE_ENABLED=false`
(or `quarkus.logaperture.enabled=false`); with the agent, add `-Dlogaperture.disabled=true` to the
command line. LogAperture then does nothing at all. If the application starts that way, LogAperture
is involved: please [open an issue](https://github.com/ddeuchert/logaperture/issues) with the output
and `java -version`.
