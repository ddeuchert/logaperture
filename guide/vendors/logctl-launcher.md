# Configuring the `logctl` launcher

If you ship LogAperture inside your own application, `logctl` has to work when the application is
dropped into a folder, whatever environment the caller has. The `logctl` launchers support that
with an optional **conf file** that you generate and place next to them. The release zip does not
include one.

## The file

| Launcher | Conf file | Language |
|---|---|---|
| `bin/logctl` | `bin/logctl.conf` | POSIX `sh`, read with `.` |
| `bin/logctl.cmd` | `bin/logctl.conf.cmd` | batch, run with `call` |

The launcher looks for the file in its own `bin/` directory, and nowhere else. Nothing in the
environment can point it at a different file. If the file exists, the launcher runs it **first**,
before it reads any variable. If it doesn't, the launcher uses the caller's environment.

The file runs inside the launcher, so it can set a variable (replacing whatever the caller had),
clear one (`unset NAME` in `sh`, `set "NAME="` in batch), or compute a value. The launcher's own
directory is available to it as `$here` (`sh`) or `%~dp0` (batch, with a trailing backslash), so a
relative location means the same thing wherever `logctl` is run from.

=== "Linux and macOS"

    ```sh
    # bin/logctl.conf
    JAVA_HOME="$here/../../java"
    unset JAVA_TOOL_OPTIONS
    ```

=== "Windows"

    ```bat
    rem bin\logctl.conf.cmd
    set "JAVA_HOME=%~dp0..\..\java"
    set "JAVA_TOOL_OPTIONS="
    ```

## Which Java runs

After the conf file has run:

1. If `JAVA_HOME` is set, `logctl` runs `$JAVA_HOME/bin/java` and nothing else. If that file isn't
   there, `logctl` stops with exit code 1 and says which path it tried. It never quietly runs a
   different `java` from the `PATH`:

    ```
    logctl: JAVA_HOME is set to /opt/acme/java but /opt/acme/java/bin/java was not found.
    ```

2. Otherwise it runs `java` from the `PATH`.

This is true with or without a conf file: `logctl` honors a `JAVA_HOME` in the caller's
environment. It must be a JDK, not a JRE.

## Where the jar is

By default `logctl` runs `../lib/logaperture-cli.jar`, relative to the launcher, and falls back
to `../target/logaperture-cli.jar` in a source tree. If you keep the jar somewhere else, set
`LOGCTL_JAR_HOME` to the **directory** that holds `logaperture-cli.jar`, in the conf file or in
the environment:

```sh
LOGCTL_JAR_HOME="$here/../../lib/logaperture"
```

When `LOGCTL_JAR_HOME` is set, that is the only place `logctl` looks. If the jar isn't there it
stops with exit code 1 and names the path it tried; it does not go on to the default locations:

```
logctl: LOGCTL_JAR_HOME is set to /opt/acme/lib but /opt/acme/lib/logaperture-cli.jar was not found.
```

## JVM options

`logctl` passes `JAVA_OPTS` to the JVM before `-jar`. Set it in the conf file to give `logctl` its
own flags, such as `-Xmx128m`; clear it there to ignore a value the caller has.
