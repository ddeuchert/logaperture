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
checks don't run on a plain JVM with Logback in 1.0. The last line counts the checks that ran.

With `--json`, each finding carries the **check id** below in its `check` field. See
[JSON output](json-output.md#doctor).

## Handlers and loggers

### `handler.unbounded-growth`

A file handler with no size limit, or no limit on how many rotated files it keeps, so its total size
can grow until the disk is full. `WARNING`, raised to `CRITICAL` when `disk.headroom` also finds that
handler's disk close to full.

**Fix:** configure size-based rotation with a backup limit on that handler.

### `logger.verbosity-left-on`

The root logger at `DEBUG`, `TRACE` or `ALL`, or one of a few known-chatty framework loggers
(`org.hibernate`, `org.apache.http`, `com.zaxxer.hikari`, `org.apache.commons.dbcp2`) configured
at one of those levels. `WARNING`.

**Fix:** for a framework logger, `doctor` gives the `logctl reset logger` command; for a level in the
application's own configuration, change it there.

### `handler.duplicate-output`

Two or more file handlers at the same level, so the same lines are written twice. `INFO`: it can be
deliberate.

### `handler.autoflush`

A handler that flushes after every record, which costs disk I/O on a busy logger. `WARNING`.

**Fix:** turn autoflush off on that handler, if losing the last few lines in a crash is acceptable.

### `disk.headroom`

For each disk a file handler writes to, how long until it is full at the rate LogAperture measures
while `doctor` runs. `CRITICAL` under 4 hours, `WARNING` under 24 hours, `OK` otherwise or when
nothing was written during the measurement.

**Fix:** free space, or reduce what is written there: `logctl top` shows which loggers write most.

## The agent

### `agent.order`

Where LogAperture is on the `-javaagent` list. `OK` when it is first. `INFO` when other agents are
listed ahead of it: anything they log while starting up is out of reach of `drop` and `trim` rules.
Agents set in `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` count as listed first. Not reported when
LogAperture was attached to a running JVM. See [Agent order](../concepts/agent-order.md).

**Fix:** list LogAperture's `-javaagent` before the others, where the site allows it.

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
soon as the handler appears, which on WildFly can be some time after startup.

**Fix:** if it never appears, check the name against `logctl list handlers --show-all`.

### `recipe-files`

Whether every recipe file reads cleanly. `OK`, or `INFO` for each file that can't be read: its
recipes are not offered. `logctl list recipes --verbose` shows each error.

**Fix:** correct the file; for a library's file, tell its maintainers.
