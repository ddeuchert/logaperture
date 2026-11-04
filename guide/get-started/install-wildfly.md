# Install on WildFly

You add one line to WildFly's startup configuration and restart once. After that, `logctl` changes
logging on the running server. LogAperture never edits `standalone.xml`.

## What you need

- A **standalone** WildFly, 26.1 or later. Domain mode is not supported.
- **JDK 17, 21 or 25** running the server.
- A **JDK**, not just a JRE, wherever you run `logctl`. It uses the JVM attach mechanism, which
  only a JDK has.
- `logctl` runs **on the same machine and as the same operating-system user** as the server.

## Install

1. **Download** the release zip, `logaperture-<version>.zip`, from the [Download](download.md)
   page, and unzip it on the server, for example to `/opt/logaperture`. It contains:

    | Path | |
    |---|---|
    | `bin/logctl`, `bin/logctl.cmd` | the command-line tool |
    | `lib/logaperture-agent.jar` | the agent |
    | `docs/index.html` | this guide, for reading without a network |

    To fetch the agent with Maven or Gradle instead, for example into a container image, see
    [Download](download.md).

2. **Put `bin/` on your `PATH`**, so you can type plain `logctl`:

    ```sh
    export PATH="$PATH:/opt/logaperture/bin"
    ```

    Add the line to `~/.bashrc` or your shell's profile to keep it. On Windows, add
    `C:\opt\logaperture\bin` to `PATH`.

3. **Add the agent** to the server's JVM options, as one appended line.

    === "Linux and macOS"

        In `$JBOSS_HOME/bin/standalone.conf`:

        ```sh
        JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/logaperture/lib/logaperture-agent.jar"
        ```

    === "Windows"

        In `%JBOSS_HOME%\bin\standalone.conf.bat`:

        ```bat
        set "JAVA_OPTS=%JAVA_OPTS% -javaagent:C:\opt\logaperture\lib\logaperture-agent.jar"
        ```

    If the server already has other `-javaagent` entries, put LogAperture's first. An agent listed
    before it can log during startup before LogAperture is ready. See
    [Agent order](../concepts/agent-order.md).

    In a container, the server's user often has no writable home directory, and LogAperture can't
    save its state there. Point it somewhere writable as well:
    `-Dlogaperture.home=$JBOSS_HOME/standalone/data/logaperture`.

4. **Restart WildFly.**

!!! tip "Try it first"
    To see LogAperture on a running server before you edit `standalone.conf`, use
    [`logctl attach`](../how-to/try-without-restart.md). It lasts until the server stops, and changes
    nothing in `standalone.xml`.

## Check it works

The server log has one line from LogAperture at startup and no
`The LogManager was not properly installed` error. Then run:

```sh
logctl env
```

```
LogAperture agent    1.0.0-beta.4  (logctl 1.0.0-beta.4)
Java                 17.0.5  Eclipse Adoptium
OS                   Linux 7.2.10-200.fc44.x86_64  amd64
Logging backend      JBoss LogManager 2.1.18.Final
Framework/container  WildFly 26.1.3.Final
Diagnostics level    —
State file           /opt/jboss/wildfly/standalone/tmp/logaperture/instances/af001a751ad6462d-jboss.state.yaml
Vendor defaults      not configured
```

Your versions and paths will differ. This output is from a WildFly in a container, started with
`-Dlogaperture.home` pointing into the server's `tmp` directory.

`Framework/container` must name WildFly. If it doesn't, LogAperture didn't recognize the server:
check the server log before going further. If `logctl` says it can't find a JVM, see
[Work with several JVMs](../how-to/several-jvms.md).

LogAperture knows WildFly's handler names (`CONSOLE`, `FILE`, …) a few seconds after the JVM
starts, so you can set a handler level or put one in the
[vendor defaults](../vendors/vendor-defaults.md) while a long-starting application is still
deploying. `ALL_HANDLERS` always works.

Next: the [Quick start](quick-start.md).

## A deployment with its own Log4j 2

From 1.1. Most applications on WildFly need nothing here: an application that logs through Log4j 2 using
WildFly's own Log4j API module is already logging through JBoss LogManager, and everything above applies
to it.

It is different when a deployment **excludes** the server's Log4j API (an exclusion of
`org.apache.logging.log4j.api` in `jboss-deployment-structure.xml`) and brings its own `log4j-api` and
`log4j-core`, 2.20.0 or later. That deployment's Log4j is separate from the server's, so LogAperture finds
it inside the deployment and controls it as its own context, next to the server's:

```sh
logctl list loggers --show-all
```

The server's logging is the context `system`; a deployment's Log4j is `log4j2:` followed by its name
(its web context path, if it has Log4j's web support, as on [Tomcat](install-tomcat.md#context-names)). The
agent line is the same as above; nothing is added to the deployment. When the deployment is redeployed,
its overrides come back on the new copy.

It was tried on WildFly 26.1.3. This setup is checked by hand and not yet by automated tests; if it
doesn't work for you, [tell us](https://github.com/ddeuchert/logaperture/issues). A Log4j older than 2.20.0
is found but not controlled, and `logctl doctor` says so (`log4j2.version`).

## Agent options

Agent options go after the jar, separated by `=`:

```sh
-javaagent:/opt/logaperture/lib/logaperture-agent.jar=--vendor-defaults=/etc/myproduct/logging.yaml
```

- `--vendor-defaults=<file>` starts with a vendor defaults file. See
  [Vendor defaults](../vendors/vendor-defaults.md).
- `--storm-detection=on` starts with storm detection on. See [Log storms](../how-to/storms.md).

All options and `-Dlogaperture.*` system properties are listed in
[Agent options and properties](../reference/agent-options.md).

## Uninstall

Remove the `-javaagent` line and restart WildFly. Nothing else was changed.

LogAperture's state file, which holds `sticky` changes, is under `~/.logaperture/` of the user
running the server, unless `-Dlogaperture.home` moved it. `logctl env` shows the exact path.
Delete it if you don't plan to reinstall.

## If the server won't start

Add `-Dlogaperture.disabled=true` to `JAVA_OPTS` and restart. The agent then does nothing at all.
If the server starts that way, LogAperture is involved: please
[open an issue](https://github.com/ddeuchert/logaperture/issues) with the server log and the
output of `java -version`.
