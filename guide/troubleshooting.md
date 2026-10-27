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

See [Work with several JVMs](how-to/several-jvms.md) for "Several candidates" and attach errors.

## `logctl attach` didn't work

- *`was started with dynamic agent loading turned off`*: that JVM has `-XX:-EnableDynamicAgentLoading`.
  No agent can be loaded into it. Restart it with `-javaagent`, or without that flag.
- *`Can't attach to PID … run as the user that owns that process`*: run `logctl` as the JVM's user.
- *`This Java has no attach support`*: run `logctl` with a JDK, not a JRE.
- *`loaded … but not active yet`*: the application hasn't logged through Logback or
  `java.util.logging`, and nothing is wrong until it does. If it logs through another backend, such as
  Log4j 2, LogAperture can't control it. See [Try without a restart](how-to/try-without-restart.md).
- *A JVM that `jps` shows is missing from the list*: `logctl attach` lists only JVMs that don't have
  LogAperture yet, and only ones it could attach to as your user.

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
