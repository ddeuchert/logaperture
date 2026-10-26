# Find where the disk goes

When logs are filling a disk, or you want to know before they do. Two read-only commands help:
`top` measures, and `doctor` checks the configuration. Neither changes anything.

!!! info "Not on Logback yet"
    `top` and `doctor` need `java.util.logging` or JBoss LogManager: WildFly, Quarkus, or (from 1.1)
    a plain JVM that logs through `java.util.logging`. On a plain JVM with Logback they report
    nothing yet. On `java.util.logging`, `top` counts what the JDK's `FileHandler` writes, but
    `doctor` can't check that file's size limits or its disk: the JDK keeps them private.

The output on this page is from WildFly 26.1.3.

## Which loggers write the most

```sh
logctl top --limit 5
```

```
io.undertow.request                 0 MB/h  (5 MB/day)  0% stack traces
org.wildfly.extension.undertow      0 MB/h  (0 MB/day)  0% stack traces
org.logaperture.sample.work.Worker  0 MB/h  (0 MB/day)  0% stack traces
org.jboss.as.ejb3                   0 MB/h  (0 MB/day)  0% stack traces
org.jboss.as                        0 MB/h  (0 MB/day)  0% stack traces

measured over 46m (since agent start, 2026-10-10T00:42:20.290034554Z) — 35 loggers tracked.
```

Loggers are listed worst first, by bytes written since the agent started, with:

- the rate per hour, and the same rate projected over a day;
- the share of those bytes that is stack traces. A high share means a `trim` rule would save most
  of it without losing the events. See [Silence noise](silence-noise.md).

`--limit` sets how many to show (default 10; `0` for all). The projection is only as good as the
time measured: a few minutes after startup, it mostly reflects startup.

## If `top` says there are no log files

`top` counts bytes written to files. An application that logs only to the console has nothing to
count, and `top` says so:

```
$ logctl top
No log files to measure: `top` counts bytes written to files, and this application's handlers write to none (found: console).
See `logctl list handlers`; send output to a file to measure it.
```

`logctl list handlers --show-all` shows which handlers write to a file (`file` in the `PERSISTS`
column). Quarkus logs to the console by default; turn on its file handler
(`quarkus.log.file.enabled=true`) and `top` has something to measure. This is different from the
shorter "No byte-volume measurements available yet", which means a file handler exists and nothing
has been written through it yet.

With `--json`, `persistentHandlers` is the number of file-backed handlers: `0` for the case above.

## What in the configuration will hurt

```sh
logctl doctor
```

```
[OK]    logaperture-agent.jar is the first -javaagent.
[WARN]  FILE has no size cap — writes are unbounded.
        no size-based rotation is configured
        suggested: configure a size-based rotation policy on FILE.
[OK]    no excess verbosity found at root or on a known-chatty logger.
[WARN]  FILE has autoflush enabled — every record forces a flush.
[WARN]  CONSOLE has autoflush enabled — every record forces a flush.
[OK]    /opt/jboss/wildfly/standalone/log/server.log is stable — no writes observed during the sample window.

5 checks run — 0 critical, 3 warning, 0 info, 3 clean.
```

For disk space, the findings that matter most are:

- **No size cap** on a file handler: the file grows until the disk is full.
- **Disk headroom**: how long until each log disk is full at the current rate. Under 24 hours is a
  warning; under 4 hours is critical, and an uncapped handler on that disk becomes critical too.
- **Verbosity left on**: the root logger, or a known-chatty framework logger, at `DEBUG` or finer.

Every check, and what to do about it, is in [Doctor checks](../reference/doctor-checks.md).

## Then

- Turn a logger down: `logctl set logger <name> WARN sticky`. See [Raise a log level](raise-a-level.md),
  which works in both directions.
- Keep the events but not their traces, or drop a repeating message: [Silence noise](silence-noise.md).
- Fix the handler's rotation in the application's own configuration. LogAperture doesn't change
  handler settings other than the level.
