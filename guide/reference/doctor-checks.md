# Doctor checks

`logctl doctor` runs the checks below and prints one line per finding, with a detail line and a
suggested fix where it has them. It never changes anything, and never exits non-zero because of
what it found.

Each finding has a **severity**:

| Severity | Shown as | Means |
|---|---|---|
| `CRITICAL` | `[CRITICAL]` | Likely to cause an outage, such as a full disk, soon. |
| `WARNING` | `[WARN]` | A real problem worth fixing. |
| `INFO` | `[INFO]` | Worth knowing; not necessarily wrong. |
| `OK` | `[OK]` | The check ran and found nothing. |

A check that has nothing to look at doesn't run and prints nothing. For example, the handler
checks don't run on a plain JVM with Logback, and `handler.unbounded-growth` and `disk.headroom`
skip the JDK's own `FileHandler` on a plain JVM with `java.util.logging`, which keeps its file and
limits private. The last line counts the checks that ran.

With `--json`, each finding carries the **check id** below in its `check` field. See
[JSON output](json-output.md#doctor).

## Handlers and loggers

### `handler.unbounded-growth`

A file handler with no size limit, or no limit on how many rotated files it keeps, so its total size
can grow until the disk is full. `WARNING`, raised to `CRITICAL` when `disk.headroom` also finds that
handler's disk close to full.

On Log4j 2 this finds a plain `File`, `RandomAccessFile` or `MemoryMappedFile` appender, also one
behind an `<Async>` appender that has nothing else behind it. A `RollingFile` appender is not
inspected: its policy and strategy are not read, so `doctor` stays quiet on it, as it does for the
JDK's own `FileHandler`.

**Fix:** configure size-based rotation with a backup limit on that handler.

### `logger.verbosity-left-on`

The root logger at `DEBUG`, `TRACE` or `ALL`, or one of a few known-chatty framework loggers
(`org.hibernate`, `org.apache.http`, `com.zaxxer.hikari`, `org.apache.commons.dbcp2`) configured
at one of those levels. `WARNING`.

**Fix:** the command `doctor` gives depends on what a `reset` would do. If it would go back to a level that
is not chatty (the logger was raised with `logctl set logger`, or by the application's code), it is
`logctl reset logger <name>`. If it would go back to a chatty level that the application's own
configuration, or a [vendor defaults file](../vendors/vendor-defaults.md), sets, `reset` would change
nothing, so `doctor` gives `logctl set logger <name> INFO sticky` instead and says which of the two it is.
`sticky` keeps it across restarts; change the level in the configuration (or the file) to make it
permanent. Like any `set logger`, it changes that one logger: a logger under it, such as
`org.hibernate.SQL`, that has a level of its own keeps it.

### `handler.duplicate-output`

Two or more file handlers at the same level, so the same lines are written twice. `INFO`: it can be
deliberate.

### `handler.autoflush`

A handler that flushes after every record, which costs disk I/O on a busy logger. `WARNING`.
On Quarkus, the console and file handlers Quarkus creates itself are reported as `INFO` instead,
marked as the framework's default: the application didn't choose it. A handler the application added
keeps the `WARNING`.

On Log4j 2 this reports a plain `File`, `RandomAccessFile` or `MemoryMappedFile` appender whose
`immediateFlush` is on, which is Log4j's default. A `RollingFile` appender, a console appender and
an `<Async>` appender are not reported.

**Fix:** turn autoflush off on that handler, if losing the last few lines in a crash is acceptable.

### `handler.formatter-kept`

A handler that requires its own formatter, so LogAperture leaves its formatter as it is: `trim`
rules and `top` don't apply to what that handler writes. `INFO`. In practice this is Quarkus's Dev UI
log stream (`MutinyLogHandler`), present in `quarkus dev` only.

**Fix:** nothing to do.

### `quarkus.handler-names`

On Quarkus: the handler names in `application.properties` (`quarkus.log.handler.file."AUDIT"`) could
not all be given to their handlers. One finding per case:

- The configuration could not be read, so every handler is named by type (`console`, `file`,
  `file-2`), or the root's handlers could not be looked at, so the names in `quarkus.log.handlers`
  are not given. `INFO`. Expected on a Quarkus version other than the tested ones.
- A `handlers` list (`quarkus.log.handlers`, or a category's) could not be matched to the handlers on
  its logger: there are fewer than it names, one is of another type, or one writes to a different path,
  endpoint or console format than the configuration says. The finding names the list and what did not
  match. Its handlers keep their type names, so `logctl set handler AUDIT` fails with an unknown
  handler. `WARNING`.
- A list was matched by position and type alone (no path, endpoint or console format to check) while
  its logger has other handlers too, such as one the application added. The names are probably right.
  `INFO`.

**Fix:** use the type names `logctl list handlers` shows, for example `logctl set handler file-2 DEBUG`.
For a list matched by type alone, configure what tells its handlers apart (a console handler's
`format`) and LogAperture checks it.

### `logger.below-floor`

A logger set to a level the logging backend will not emit, because the backend enforces a floor
that is less verbose. On Quarkus this is the build-time `quarkus.log.min-level`: an application
built with the default `DEBUG` never logs `TRACE`, whatever a logger's level says. `WARNING`, one
finding per logger. Not reported where the backend has no such floor, which is every backend but
JBoss LogManager under a framework that sets one. `logctl set logger` gives the same warning when
you set the level, and `logctl list loggers` shows the level that is actually emitted.

**Fix:** on Quarkus, rebuild the application with a lower `quarkus.log.min-level` (for example
`TRACE`). The finding names the value the application was built with.

### `disk.headroom`

For each disk a file handler writes to, how long until it is full at the rate LogAperture measures
while `doctor` runs. `CRITICAL` under 4 hours, `WARNING` under 24 hours, `OK` otherwise or when
nothing was written during the measurement.

**Fix:** free space, or reduce what is written there: `logctl top` shows which loggers write most.

## Log4j 2

### `log4j2.version`

LogAperture found a Log4j 2 older than 2.20.0 and did not control it. `WARNING`. The finding names the
class loader the Log4j was found in (its class and name and an identity, so two web applications' loaders
are two rows) and the version. It is process-wide, with no context, because
nothing was bound.

LogAperture brings its control surface up for it, so `logctl doctor` finds the JVM without `--pid` even when this
Log4j is the only logging framework in it.

**Fix:** upgrade Log4j to 2.20.0 or later.

### `log4j2.not-bound`

LogAperture found a Log4j 2 and could not bind it. `WARNING` when its adapter could not be created
or threw while opening; `INFO` when no Log4j context had started three seconds after the Log4j was
found, which is usually a Log4j the application never used. The finding names the class loader and
the reason, and is the same reason the application printed to its error stream when it gave up. It is kept for as long as that class loader is alive, so a
redeployed web application's old failure goes away when it is collected, and each loader that
failed has its own row (at most 16).

**Fix:** the reason says what happened. Nothing to do for an `INFO` about a Log4j that never started.

### `log4j2.trim`

Trim rules cannot cut a stack trace on this Log4j: 2.25.0 to 2.25.3 render the exception without
going through anything LogAperture can reach. `INFO`, because LogAperture cannot tell whether there
is a trim rule; if there is none this is only worth knowing. Trim rules are still counted, and are
applied nowhere on this Log4j. `drop` rules, levels and storm detection are not affected.

**Fix:** upgrade Log4j to 2.25.4 or later (2.24.x works too).

## The agent

### `agent.order`

Where LogAperture is on the `-javaagent` list. `OK` when it is first. `INFO` when other agents are
listed ahead of it: anything they log while starting up is out of reach of `drop` and `trim` rules.
Agents set in `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` count as listed first. Not reported when
LogAperture was attached to a running JVM (`agent.attached` says so). See
[Agent order](../concepts/agent-order.md).

**Fix:** list LogAperture's `-javaagent` before the others, where the site allows it.

### `agent.attached`

LogAperture was loaded into a running JVM with `logctl attach`, not started by a `-javaagent` entry,
so the `-javaagent` order check has nothing to read. `INFO`, and reported only on an attached JVM.
An attach lasts until the JVM stops.

**Fix:** add `-javaagent:` with the agent jar to the launch line to make LogAperture permanent.

### `agent.early-sweep`

Quarkus only. LogAperture re-applies what Quarkus's own start-up logging setup overwrites as soon as
Quarkus says that setup is done. `INFO` when it could not find the signal Quarkus gives for that (on
a Quarkus version that names it differently). Nothing is lost: a start-up sweep every quarter of a
second for the first 15 seconds, and the periodic sweep, still repair it. Not reported when the
signal was found.

### `agent.duplicate`

The same agent jar listed more than once on the `-javaagent` list. `WARNING` when it is
LogAperture's own jar (it starts once and ignores the rest, with a warning each), `INFO` for
another agent.

**Fix:** remove all but one entry.

## Vendor defaults and recipes

### `vendor-defaults.file`

Whether the vendor defaults file loaded. `OK` with a summary of what it sets, or `WARNING` when it
was rejected: then none of it applies, and the finding lists every error with its line number.
Not reported when there is no vendor defaults file.

**Fix:** correct the file and restart the application.

### `vendor-defaults.writable`

The vendor defaults file, or its directory, can be written by the account the JVM runs as, so
anyone who can run code as that account can change the baseline. `WARNING`. The file still
applies.

**Fix:** make the file and its directory read-only for that account.

### `vendor-defaults.unresolved-handler`

The vendor defaults file sets a handler that doesn't exist yet. `INFO`. The setting is applied as
soon as the handler appears, which on WildFly is a few seconds after startup.

**Fix:** if it never appears, check the name against `logctl list handlers --show-all`.

### `recipe-files`

Whether every recipe file reads cleanly. `OK`, or `INFO` for each file that can't be read: its
recipes are not offered. `logctl list recipes --verbose` shows each error.

**Fix:** correct the file; for a library's file, tell its maintainers.
