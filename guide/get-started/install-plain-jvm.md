# Install on a plain JVM

For an application started with `java -jar` that logs through **Logback** or, from 1.1, through
**`java.util.logging`** (JUL). You add the agent to the `java` command and restart once.

!!! info "Logback: level control only"
    With Logback, LogAperture changes and resets logger levels, and applies the level settings of
    vendor defaults files and recipes. Handler levels, `drop` and `trim` rules, `top`, `doctor` and
    storm detection need `java.util.logging`. An application on `java.util.logging` gets all of them,
    as on WildFly. See the [support table](../index.md#what-10-supports).

If Logback is on the application's class path, LogAperture uses Logback, even if some code also
logs through `java.util.logging`. With `jul-to-slf4j` those records reach Logback and are
controlled there.

## What you need

- **JDK 17, 21 or 25** running the application, which logs through Logback (directly or through
  SLF4J) or through `java.util.logging`.
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
also has one line from LogAperture at startup, `LogAperture <version> active (JVM)`. On
`java.util.logging`, `env` shows `Logging backend    java.util.logging (JUL)`.
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
