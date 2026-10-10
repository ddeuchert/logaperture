# Troubleshooting

## `logctl` can't find the JVM

```
No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.
```

- Check the JVM was started with the `-javaagent` option: the application's log has a line like
  `[logaperture] LogAperture 1.0.0 active (WildFly)` at startup.
- Run `logctl` as the operating-system user the JVM runs as, on the same machine. `logctl` only sees
  JVMs it could attach to.
- In a container, run `logctl` inside the same container.

See [Work with several JVMs](how-to/several-jvms.md) for "Several candidates" and attach errors.

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

## A raised level shows nothing

Usually a handler is at a stricter level than the logger. `set logger` warns about it, with the
command that fixes it. See [Raise a log level](how-to/raise-a-level.md#when-raising-a-logger-shows-nothing).

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
- Rules don't work on a plain JVM with Logback in 1.0.

## `top`, `doctor` or `storms` report nothing

On a plain JVM with Logback they aren't available in 1.0. On WildFly:

- `top` counts from when the agent started; right after startup there's little to show.
- `storms` reports nothing while storm detection is off, which is the default. See
  [Log storms](how-to/storms.md).

## A `sticky` change didn't come back after a restart

- `logctl env` shows the state file. If its directory isn't writable by the JVM's user, LogAperture
  can't save changes: set `-Dlogaperture.home` to a writable directory. This is common in
  containers.
- Two JVMs started from the same directory share a state file name; the second can't keep `sticky`
  changes and says so in its log. Set `-Dlogaperture.instanceId`.

## The vendor defaults file isn't applied

Run `logctl doctor`. If the file has an error, none of it applies, and `vendor-defaults.file` lists
every error with its line number. See [Doctor checks](reference/doctor-checks.md).

## Seeing more of what LogAperture does

LogAperture writes one line at startup, then only warnings and errors. For more, start the JVM with
`-Dlogaperture.diagnostics.level=INFO`, or `DEBUG`. LogAperture's lines start with `[logaperture]`.

## Reporting a problem

[Open an issue](https://github.com/ddeuchert/logaperture/issues) with:

- the output of `logctl env`, which is written to be pasted into a bug report;
- the command you ran and its output;
- any `[logaperture]` lines from the application's log.
