# Install on a plain JVM

For an application started with `java -jar` that logs through **Logback**. You add the agent to the
`java` command and restart once.

!!! info "Level control only in 1.0"
    On a plain JVM with Logback, 1.0 changes and resets logger levels, and applies the level
    settings of vendor defaults files and recipes. Handler levels, `drop` and `trim` rules, `top`,
    `doctor` and storm detection need `java.util.logging` or JBoss LogManager, so today they work
    on WildFly only. A plain JVM that logs through `java.util.logging` alone isn't picked up yet.
    See the [support table](../index.md#what-10-supports).

## What you need

- **JDK 17, 21 or 25** running the application, which logs through Logback (directly or through
  SLF4J).
- A **JDK**, not just a JRE, wherever you run `logctl`.
- `logctl` runs **on the same machine and as the same operating-system user** as the application.

## Install

1. **Download** `logaperture-<version>.zip` from the
   [releases page](https://github.com/ddeuchert/logaperture/releases) and unzip it, for example
   to `/opt/logaperture`.

    !!! tip "Or get it from Maven Central"
        From 1.0.0-beta.1 the same files are on Maven Central, group `org.logaperture`: the zip as
        `logaperture-dist` (type `zip`), the agent as `logaperture-agent` and `logctl` as
        `logaperture-cli`. To fetch the agent in a build script or a container image:

        ```sh
        mvn dependency:copy -Dartifact=org.logaperture:logaperture-agent:<version> \
            -DoutputDirectory=/opt/logaperture/lib -Dmdep.stripVersion=true
        ```

        `-Dmdep.stripVersion=true` names it `logaperture-agent.jar`, as in the zip.

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

## Check it works

```sh
logctl env
logctl list loggers --show-all
```

`env` shows the agent's version and where it keeps its state file. The application's own log
also has one line from LogAperture at startup, `LogAperture <version> active (JVM)`.
`list loggers --show-all` lists the application's loggers and their levels.

Then change one for a few minutes and watch the log:

```sh
logctl set logger com.example DEBUG for 5m
logctl status
```

The [Quick start](quick-start.md) is written for WildFly, but its level, tier and `reset` steps
work the same here.

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
