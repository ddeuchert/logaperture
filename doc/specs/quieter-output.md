# Quieter, consolidated LogAperture output (issue #123)

Status: **signed off 2026-09-29** (Q1-Q5, Q7-Q10 agreed; Q9's default raised to 10m; Q6 not
done now). Not implemented. Target: 1.0.0-beta.1 (feature freeze
2026-10-15).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.5 (self-diagnostics), §9.6
(suppression is never silent), §9.7 (the audit trail).
Builds on: [`vendor-defaults.md`](vendor-defaults.md) ("Audit"), [`persistence.md`](persistence.md)
(resume), [`drop-rule.md`](drop-rule.md) ("Periodic summary line"),
[`environment-report.md`](environment-report.md).
Split from #123's original proposal: routing *all* of LogAperture's output through the
application's logging is [#124](https://github.com/ddeuchert/logaperture/issues/124) (1.1.0).

## Functional summary

After this feature, the user will be able to:

- Start an application with a vendor defaults file and see one line from LogAperture saying it is
  active, what it loaded and what it restored, instead of a screenful.
- Scan a server's startup log for errors without LogAperture's drop summaries showing up as
  errors: on WildFly they appear as one INFO line every 10 minutes, in the server's own format,
  under a category (`org.logaperture.drop`) the operator can raise, lower or redirect like any other.
- Turn all of LogAperture's own messages up or down with one setting,
  `-Dlogaperture.diagnostics.level`, which now covers every message.

## What's noisy today

Measured in a WildFly test run and in a real deployment's startup log (not quoted here):

| Output | When | Lines | Where it lands on WildFly |
|---|---|---|---|
| Vendor defaults loaded | start | 1 | console only |
| One audit record per vendor entry | start | one per logger, handler and rule entry | console only |
| One audit record per resumed sticky setting | start | one per setting | console only |
| "level control installed" (×2), "handler-level install deferred/complete" | start | 4 | console only |
| Drop summary, one per `drop` rule | first sweep after each rule's first match, then every 5 min | one per rule | `server.log` as `ERROR [stderr]`, with a second, UTC timestamp and a `WARN` inside |
| ~60 other messages printed straight to `System.err` | on failures and oddities | varies | `server.log` as `ERROR [stderr]` |

Two causes: audit and startup messages are one line per item, and messages reach the console by
two paths -- `Diagnostics` and the audit log hold the `System.err` captured when the agent
started (the raw console), while `core`'s direct prints use whatever `System.err` is at the time,
which WildFly has wrapped into its logging at `ERROR`.

## Decisions

### Q1 — One audit record per vendor defaults load

**Agreed:** loading the vendor defaults file writes one audit record, source `vendor-defaults`:
`loaded <path> sha256=<hex> (12 loggers, 2 handlers, 10 rules, 3 recipes)`. The per-entry
`MUTATION` records at install go away. The hash proves exactly which content applied; the entries
themselves are visible through `logctl list`'s `VENDOR` column and `list rules`. Records are still
written per entry for what *differs* from a clean load: an entry that failed to apply, one the
verification sweep had to put back, and the handover of exported sticky settings (already one line
each, `export-round-trip.md`). A rejected file still writes no audit record (vendor-defaults.md).

### Q2 — One audit record per resume

**Agreed:** resuming saved state writes one record, source `resume`: `restored 3 logger overrides,
1 handler override, 2 rules from <state file>`, instead of one per entry. Per-entry records remain
for the exceptions -- expired while stopped, dropped, or not resumable -- which already print their
own messages.

### Q3 — One startup banner

**Agreed:** the "vendor defaults loaded", "level control installed" (both) and "handler-level
install complete" lines are replaced by one line, written once the first logging context is
installed:

```
[logaperture] LogAperture 1.0.0-beta.1 active (WildFly): vendor defaults /opt/app/vendor-defaults.yaml (12 loggers, 2 handlers, 10 rules); 3 sticky settings restored
```

It is written at every level except `ERROR` (Q7), since it's the one confirmation an operator
needs that the agent is running. A rejected vendor defaults file is still a `WARN` with every
error listed, as today; the writable-file warning stays a `WARN`. "handler-level install deferred"
becomes `DEBUG` unless a delay was configured (`-Dlogaperture.handlerInstallDelaySeconds` > 0).

### Q4 — Drop summaries: consolidated, and not during startup

**Agreed:** one line per interval for all `drop` rules together, only when something was
dropped, listing the rules by count:

```
drop summary: 440 events suppressed by 10 rules in the last 10m (vendor:drop-deployment 307, r3 60, ...)
```

The first summary comes one interval after the agent starts, not on the first sweep after a rule's
first match, so a normal startup writes none. More than 5 rules are listed as the top 5 and `+N
more`; per-rule detail is `logctl list rules`' hit counts. §9.6 holds: nothing suppressed goes
unmentioned, it is summarised.

### Q5 — Drop summaries go through the platform logger on JUL/WildFly

**Agreed:** where the JUL adapter is in use (WildFly, JBoss LogManager), the summary is logged
through a `java.util.logging` logger, `org.logaperture.drop`, at `INFO`, so it appears in the
server's own format and the operator can raise, lower or redirect it in `standalone.xml` or with
`logctl set logger org.logaperture.drop WARN`. Elsewhere (Logback, none), it goes to the diagnostics
writer as today. This is the narrow, safe part of #124: the summary is written from the sweep
thread, never from inside a log handler, so it can't loop through LogAperture's own wrappers.

Two guards: LogAperture's rule gate never applies a rule to a record from an `org.logaperture`
logger (so no `drop` or `trim`, including one attached to `org` or the root, can hide or reshape a
summary -- §9.6), and `add rule` refuses an `org.logaperture` target outright.

### Q6 — The audit trail stays where it is

**Not done now** (sign-off, 2026-09-29). The audit trail keeps going to stderr by default;
`-Dlogaperture.audit.file=<path>` already sends it to a file, which is enough for now. Q1 and Q2
already cut its startup volume to two records. A default file, rolling and an `Audit` line in
`logctl env` can come back as a separate change.

### Q7 — Every message through one writer; default level WARN

**Agreed:** `core`'s ~40 and the JUL adapter's direct `System.err` prints go through `Diagnostics`
(`core` takes a dependency on the dependency-free `logaperture-bridge`), so
`-Dlogaperture.diagnostics.level` governs all of them. One prefix, `[logaperture]`, and one format,
`[logaperture] LEVEL message` -- no second timestamp, since every console and log already has one.
`Diagnostics` always writes to the stream captured at agent start, so no LogAperture line is
re-labelled `ERROR [stderr]` in `server.log`; only Q5's summary goes into the platform logs. (The
audit log already writes to the stream captured at start.) The
default level becomes `WARN` (from `INFO`): with Q3's banner, nothing routine is lost. Each message
gets a deliberate level; per-event failure messages (a rule or storm evaluation that threw) are
rate-limited to one per message per minute, so a broken rule can't flood the console.

### Q8 — Where this leaves the level control

**Agreed:** no new setting beyond Q4's interval (Q9). The vendor defaults
file does not gain a way to set LogAperture's own verbosity in this change; it's a JVM setting
(`-D`), as today, and could join the file in a later release if asked for.

### Q9 — The drop-summary interval is configurable, not switchable off

**Agreed:** `-Dlogaperture.drop.summaryInterval=<duration>` (e.g. `30m`), default `10m` (raised
from the proposed 5m at sign-off), minimum `1m`. There is no `off`: §9.6 makes suppression never silent, and Q5 already lets the operator
route or raise the summary's level.

### Q10 — Release placement

**Agreed:** 1.0.0-beta.1, one PR. Q1-Q3 and Q7 are small and local; Q4-Q5 is the largest part.
If time runs short at the freeze, Q5 (the platform logger) moves to 1.1 with #124 and the
consolidated summary (Q4) ships through the diagnostics writer.

## Out of scope

- Routing all of LogAperture's diagnostics through the application's logging, and anything on
  Logback beyond today's stderr: #124 (1.1.0).
- Hash-chained audit records and syslog/Event Log mirroring (§9.7): unchanged, still future work.
- A vendor-defaults-file setting for LogAperture's own verbosity (Q8).
- A default audit file, with rolling (Q6, not done now).

## Testing

- Unit: one vendor audit record with the file's hash; one resume record with counts; the banner
  text; consolidated summary format, ordering, `+N more`, and no summary before one interval; the
  rule gate skipping `org.logaperture` records and `add rule` refusing the target; every former
  direct print now honouring the level; rate-limiting of per-event failures.
- `WildFlyContainerIT`: the drop summary appears in `server.log` at `INFO` under
  `org.logaperture.drop`, not as `ERROR [stderr]`; a start with a vendor defaults file writes one
  banner line to the console.

## Decision table

| # | Decision | Status |
|---|---|---|
| Q1 | One audit record per vendor defaults load, with the file's SHA-256; per-entry only for exceptions | **Agreed** |
| Q2 | One audit record per resume, with counts; per-entry only for exceptions | **Agreed** |
| Q3 | One startup banner replaces the INFO lines; shown at every level but `ERROR` | **Agreed** |
| Q4 | One consolidated drop summary per interval; none before the first interval | **Agreed** |
| Q5 | On JUL/WildFly the summary goes through `org.logaperture.drop` at INFO; rules never apply to `org.logaperture` | **Agreed** |
| Q6 | Audit to its own file by default | **Not done now** -- stays stderr; `-Dlogaperture.audit.file` remains |
| Q7 | Every message through `Diagnostics`, one format, captured stderr, default `WARN`, per-event failures rate-limited | **Agreed** |
| Q8 | No vendor-file setting for LogAperture's own verbosity in this change | **Agreed** |
| Q9 | `-Dlogaperture.drop.summaryInterval`, default 10m, minimum 1m, no `off` | **Agreed** (default 10m) |
| Q10 | Beta 1, one PR; Q5 moves to 1.1 before the freeze moves | **Agreed** |
