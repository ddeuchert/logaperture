# Run logctl with JBang

[JBang](https://www.jbang.dev/) runs `logctl` straight from Maven Central, so there is nothing to
download or unpack. With `logctl attach` it also means you can try LogAperture on a running
application with no install and no restart.

You need a JDK (JBang uses the one it finds, and `attach` needs a JDK rather than a JRE) and
JBang itself, from [jbang.dev/download](https://www.jbang.dev/download/).

## Try it on a running JVM

```sh
jbang logctl@ddeuchert/logaperture attach
```

`attach` lists the JVMs it can reach and asks which one. Then any other `logctl` command works on
it:

```sh
jbang logctl@ddeuchert/logaperture env
jbang logctl@ddeuchert/logaperture status
```

```
LogAperture agent  1.1.0  (logctl 1.1.0)
Java               21.0.12  Eclipse Adoptium
OS                 Linux 7.2.11-200.fc44.x86_64  amd64
Logging backend    Logback 1.5.13
Diagnostics level  —
State file         ~/.logaperture/instances/72f73a48f040aab0-logaperture.state.yaml
Vendor defaults    not configured
```

Your versions and paths will differ. The first run downloads `logctl` and takes a few seconds; later
runs start in about a second. What `attach` does, and what it cannot do, is on
[Try LogAperture without a restart](try-without-restart.md). It lasts until the application stops.

To make the short form shorter, JBang can install it as a command:

```sh
jbang app install logctl@ddeuchert/logaperture
logctl status
```

## Pick a version

`logctl@ddeuchert/logaperture` runs the release the project's catalog names. To run another, name
it:

```sh
jbang org.logaperture:logaperture-cli:1.1.0 status
```

Any `logctl` and agent of the same major version work together, so you rarely need to match the
version of the agent on the JVM exactly.

## What differs from the zip

- The `logctl.conf` file and the `JAVA_HOME`, `JAVA_OPTS` and `LOGCTL_JAR_HOME` settings of the
  [launcher](../vendors/logctl-launcher.md) do not apply. JBang chooses the Java, and takes options
  of its own: `jbang run -R-Dlogaperture.home=/data/la logctl@ddeuchert/logaperture status`.
- `logctl attach` writes the agent jar it loads to `~/.logaperture/agent/`, as it does from the zip (under the directory `logaperture.home` names, when you set it).
- Other install paths are on the [Download](../get-started/download.md) page.
