# Install on Tomcat

For Tomcat running web applications that bring their own **Log4j 2** (2.20.0 or later). You add one
line to Tomcat's startup configuration and restart once. LogAperture finds the Log4j inside each web
application and controls it, whichever version the application bundles. This is available from 1.1.

!!! warning "Checked by hand, not yet by automated tests"
    This setup was tried on Tomcat 11.0.27 on JDK 21, with two web applications bundling different
    versions of Log4j 2 (2.20 and 2.25). Tomcat itself is not part of the automated test suite yet, so
    if something here doesn't work for you, please
    [tell us](https://github.com/ddeuchert/logaperture/issues).

## What you need

- **Tomcat** with the web applications' Log4j 2 inside their `WEB-INF/lib`: each application's own
  `log4j-api` and `log4j-core`, 2.20.0 or later. A Log4j 2 shared by several applications from Tomcat's
  own `lib/` directory is not supported.
- **JDK 17, 21 or 25** running Tomcat.
- A **JDK**, not just a JRE, wherever you run `logctl`.
- `logctl` runs **on the same machine and as the same operating-system user** as Tomcat.

## Install

1. **Download** the release zip from the [Download](download.md) page and unzip it on the server, for
   example to `/opt/logaperture`, and put its `bin/` on your `PATH`, as in
   [Install on a plain JVM](install-plain-jvm.md#install).

2. **Add the agent** to Tomcat's JVM options. Create or edit `bin/setenv.sh` (Windows: `bin\setenv.bat`),
   which Tomcat's start script reads and an upgrade does not overwrite:

    === "Linux and macOS"

        ```sh
        CATALINA_OPTS="$CATALINA_OPTS -javaagent:/opt/logaperture/lib/logaperture-agent.jar"
        ```

    === "Windows"

        ```bat
        set "CATALINA_OPTS=%CATALINA_OPTS% -javaagent:C:\opt\logaperture\lib\logaperture-agent.jar"
        ```

    If Tomcat already has other `-javaagent` entries, put LogAperture's first. See
    [Agent order](../concepts/agent-order.md).

3. **Restart Tomcat.**

## Check it works

```sh
logctl env
logctl list loggers --show-all
```

`env` names the backend, `Log4j 2`. Each web application that has started its Log4j appears as its own
**context** in the `CONTEXT` column, bound on its own, so two applications with different Log4j versions
work side by side. Tomcat's
own logging, which uses `java.util.logging`, is the context called `system`: you see Tomcat and your
applications in one session.

```sh
logctl set logger com.example.orders DEBUG for 10m
```

A change goes to every context that has that logger. There is no way to aim it at one web application,
so a logger name that two applications share is changed in both; `list loggers` shows each context's
own rows and levels.

## Context names

A web application that includes Log4j's web support (`log4j-web`, or `log4j-jakarta-web` on Jakarta EE)
is named by its **context path**: `log4j2:/orders`. Add it if you can: that name stays the same when the
application is redeployed. An application without it gets a name LogAperture works out from its
class loader (`log4j2`, then `log4j2-2` for a second one), which Log4j itself does not keep stable across a
redeploy.

## Redeploying and undeploying

When a web application is redeployed, the new copy's Log4j is found and the overrides on that context
come back on it. When it is undeployed, LogAperture lets go of that copy, so it does not keep the old
class loader alive.

## If a web application's Log4j is not controlled

- A Log4j older than 2.20.0 is found but not controlled. `logctl doctor` says so
  (`log4j2.version`) and names the web application's class loader.
- A web application that never logs through Log4j is never bound. That is not a fault.

See [Troubleshooting](../troubleshooting.md#log4j-2).

## Uninstall

Remove the `-javaagent` option from `setenv.sh` and restart Tomcat. Optionally delete its state file,
whose path `logctl env` shows.
