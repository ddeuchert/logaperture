# Troubleshooting

## `logctl` can't find the JVM

```
No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.
```

- Check the JVM was started with the `-javaagent` option: the application's log has a line like
  `[logaperture] LogAperture 1.0.0 active (WildFly)` at startup (`(Quarkus)` on Quarkus, from 1.1).
  With the [Quarkus extension](get-started/install-quarkus.md) there is no `-javaagent` option; the
  line says `(Quarkus extension)`. If it is missing, check that `quarkus.logaperture.enabled` isn't
  `false`.
- Run `logctl` as the operating-system user the JVM runs as, on the same machine. `logctl` only sees
  JVMs it could attach to.
- In a container, run `logctl` inside the same container.
- On Windows, if the application runs as a service, open the command prompt as Administrator. See
  [Use logctl with a Windows service](how-to/windows-service.md).

See [Work with several JVMs](how-to/several-jvms.md) for "Several candidates" and attach errors.

## `logctl attach` didn't work

- *`was started with dynamic agent loading turned off`*: that JVM has `-XX:-EnableDynamicAgentLoading`.
  No agent can be loaded into it. Restart it with `-javaagent`, or without that flag.
- *`Can't attach to PID … run as the user that owns that process`*: run `logctl` as the JVM's user.
- *`This Java has no attach support`*: run `logctl` with a JDK, not a JRE.
- *`loaded … but not active yet`*: the application hasn't logged through Logback,
  `java.util.logging` or Log4j 2, and nothing is wrong until it does. If it logs through another backend,
  LogAperture can't control it. See [Try without a restart](how-to/try-without-restart.md).
- *A JVM that `jps` shows is missing from the list*: `logctl attach` lists only JVMs that don't have
  LogAperture yet, and only ones it could attach to as your user.

## Log4j 2

From 1.1. See [On Log4j 2](get-started/install-plain-jvm.md#on-log4j-2) for how it works.

### `doctor` says `log4j2.version`

LogAperture found a Log4j 2 **older than 2.20.0** and does not control it: below that version a `drop`
rule can't see every event, and there is no way to read an explicit level. The application logs as it
always did. `logctl doctor` names the class loader the Log4j was found in and its version, and works
even when that Log4j is the only logging framework in the JVM. **Upgrade Log4j to 2.20.0 or later.**

If the application uses `log4j-jul` (`-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager`),
LogAperture controls its `java.util.logging` loggers instead, as it would without Log4j 2, and says so in
a `LogAperture could not control log4j2 … controlling java.util.logging instead` line and in the finding.
The same holds for `log4j2.not-bound` below.

### `doctor` says `log4j2.not-bound`

LogAperture found a Log4j 2 and could not bind it: its adapter failed to start, or no Log4j context
had started three seconds after the Log4j was found. The finding carries the reason, which the
application's standard error also has as a `LogAperture found log4j2 … but did not bind` line. For the
second case, a Log4j on the class path that the application never uses, it is only `INFO`.

### `doctor` says `log4j2.trim`

`trim` rules can't cut a stack trace on **Log4j 2.25.0 to 2.25.3**: those versions render the exception
from its class with nothing for LogAperture to hook. `drop` rules, levels and storm detection are not
affected. Use Log4j 2.24.x, or 2.25.4 or later.

### `logctl` says my change was "put back", or the application logs "changed … outside LogAperture"

Something other than LogAperture changed a level that has a LogAperture override: Spring Boot Actuator, the
application's own `Configurator.setLevel`, or a Log4j reload that changes it. The override wins until it
ends; the line in the application's log names the logger, what it was changed to, and the override.
`logctl reset logger <name>` ends the override, and the logger then goes to the level that change set.
See [Tiers and expiry](concepts/tiers-and-expiry.md#when-the-application-changes-a-level-itself).

### A logger is missing from `list loggers`

On Log4j 2.24.3 and later, a logger appears only while the application holds a reference to it. Name it
anyway: `logctl set logger com.example.Thing DEBUG` works for any name.

### `set handler` can't find my appender

A handler is an appender, by its name in `log4j2.xml`: `logctl list handlers --show-all` shows them. An
appender that is only behind an `<Async>` appender is not listed: use the `<Async>` appender's name.

### Rows for a context called `system`

That is the JDK's own `java.util.logging`, which the connection `logctl` opens starts using. It has no
application loggers and can be ignored. A `set logger` goes to every context, so it shows up under both.

## `logctl` needs a JDK

`logctl` uses the JVM attach mechanism, which only a JDK has. Run it with a JDK, not just a JRE. The
application itself can run on either.

## The server won't start with the agent

Add `-Dlogaperture.disabled=true` to the JVM options and start again. The agent then does nothing.

- If the server starts, LogAperture is involved. Please
  [open an issue](https://github.com/ddeuchert/logaperture/issues) with the server log, `java
  -version`, and every `-javaagent` option.
- If it still fails, the problem is elsewhere.

On WildFly, the log line `The LogManager was not properly installed` means the JVM's logging was
set up before WildFly's: check `JAVA_OPTS` for other agents or logging options.

On Quarkus (from 1.1), a level below the build's `quarkus.log.min-level` shows nothing however it is
set. `logctl` warns when you set one; see [the build's minimum level](get-started/install-quarkus.md#the-builds-minimum-level).
With the Quarkus extension, turn LogAperture off with `QUARKUS_LOGAPERTURE_ENABLED=false` instead.

If a Quarkus application has the extension and is started with the agent as well, only the agent
runs, and the output says `the Quarkus extension starts nothing`. Remove one of them; see
[Agent and extension together](get-started/install-quarkus.md#agent-and-extension-together).

## A raised level shows nothing

Usually a handler is at a stricter level than the logger. The default handler (usually the console)
follows the change on its own; for any other handler, or one you set yourself, `set logger` warns
about it, with the command that fixes it. See
[Raise a log level](how-to/raise-a-level.md#the-console-follows).

Also check:

- the logger's name is right: `logctl list loggers '*.<last part>' --show-all` (see
  [Find a logger](how-to/find-a-logger.md));
- a logger under it doesn't have a level of its own: `set logger` notes it, and `--force` sets it too;
- the change hasn't already reverted: `logctl status`.

## A rule doesn't act

- `logctl list rules` shows the rule and its `HITS`. Zero hits means nothing has matched yet.
- `--below` defaults to `ERROR`: an `ERROR` event isn't touched unless you give `--below FATAL`.
- `--throwable` needs the exact class, not a parent class.
- Events logged before LogAperture started, for example by another agent at startup, are out of
  reach. See [Agent order](concepts/agent-order.md).
- `trim` works on text log formats only in 1.0. A JSON or XML formatter writes the full trace.
- Rules don't work on a plain JVM with Logback yet.

## `top`, `doctor` or `storms` report nothing

On a plain JVM with Logback they aren't available yet. Elsewhere:

- `top` counts from when the agent started; right after startup there's little to show.
- `top` says "No log files to measure": none of the application's handlers write to a file, which is
  the Quarkus default. See [Find where the disk goes](how-to/find-disk-usage.md#if-top-says-there-are-no-log-files).
- `storms` reports nothing while storm detection is off, which is the default. See
  [Log storms](how-to/storms.md).

## A `sticky` change didn't come back after a restart

- `logctl env` shows the state file. If it is under the system temp directory (`/tmp/logaperture-<user>`),
  the default directory wasn't writable and LogAperture fell back; the system may clear it. Set
  `-Dlogaperture.home` to a writable directory to keep changes. If you set that option and its
  directory isn't writable, LogAperture can't save changes at all. This is common in containers.
- Two JVMs started from the same directory share a state file name; the second can't keep `sticky`
  changes and says so in its log. Set `-Dlogaperture.instanceId`.

## The vendor defaults file isn't applied

Run `logctl doctor`. If the file has an error, none of it applies, and `vendor-defaults.file` lists
every error with its line number. See [Doctor checks](reference/doctor-checks.md).

## `logctl` output shows `-` where the guide shows `—`

`logctl` prints `—` for "no value", and `→` and `…` in some messages. A console that can't show
them, such as Windows `cmd` with its default code page, gets `-`, `->` and `...` instead. Nothing
is missing; it's the same output. To see the originals in `cmd` with JDK 18 or later, switch the console to UTF-8 with
`chcp 65001` before running `logctl`.

## Seeing more of what LogAperture does

LogAperture writes one line at startup, then only warnings and errors. For more, start the JVM with
`-Dlogaperture.diagnostics.level=INFO`, or `DEBUG`. LogAperture's lines start with `[logaperture]`.

## Reporting a problem

For a question, or to tell us how a try-out went, use
[Discussions](https://github.com/ddeuchert/logaperture/discussions/245). For a bug,
[open an issue](https://github.com/ddeuchert/logaperture/issues) with:

- the output of `logctl env`, which is written to be pasted into a bug report;
- the command you ran and its output;
- any `[logaperture]` lines from the application's log.
