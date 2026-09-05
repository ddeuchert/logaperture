# `logctl doctor` — Diagnose the Configuration

Status: draft, not yet implemented.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §16.2 (`doctor`), §16.1
(`top` — sibling read-only diagnostic, separate spec), §9.3 (capability model), §17
(roadmap — M1, Layer 1: "a read-only diagnostic release").
Builds on: [`doc/specs/level-control.md`](level-control.md) (`LoggingAdapter` SPI,
`listLoggers`), [`doc/specs/handler-floor-control.md`](handler-floor-control.md) (the
handler-level SPI this extends with configuration, not just level, per handler).

## Functional summary

After this feature, the user will be able to:

- Run **`logctl doctor`** against a live JVM and get a read-only report of common
  logging-configuration problems — nothing is changed, nothing can be changed by running it.
- See findings such as: a file handler with no size cap or unlimited backup count, `DEBUG`
  or `TRACE` left on at the root logger (or a known-chatty framework logger), the same
  content being written twice to two handlers that both persist to disk, autoflush left on
  for a busy handler, and an estimate of how long the log volume's disk has left at the
  current write rate.
- See each finding's severity (`OK` / `INFO` / `WARNING` / `CRITICAL`), a one-line summary,
  and — where there's an unambiguous one — the exact fix.
- Get the same findings as `--json`, for scripting or a monitoring check.
- Run `logctl doctor` against a framework or handler type a given check doesn't understand
  without it failing — that one check is skipped, not the whole command.

## Scope of this slice

The M1 (§17) Layer 1 read-only diagnostic release — deliberately no rule engine, no
suppression, no mutation of any kind. §16.2 calls this "a few days of work" precisely
because it needs none of Feature 3's machinery.

**In scope:**

- One new read-only operation, `diagnose()`, surfaced as `logctl doctor` / `--json`, and on
  the JMX surface first per `level-control.md`'s "JMX first, `logctl` later" convention.
- The five checks §16.2 names:
  1. **Unbounded file-handler growth** — no size cap, or a backup/retention count that
     permits unbounded total size.
  2. **`DEBUG`/`TRACE` left on** — at the root logger, or on a logger from a small built-in
     seed list of known-chatty framework packages (Hibernate, Apache HttpClient, common
     connection-pool packages — **Resolved: Option A**, a hard-coded seed list for v1).
  3. **Duplicate output** — two handlers that both **persist to disk** (i.e. `handlerDiagnostics`
     resolves a `targetPath` for each) rendering the same content at the same level.
     **Resolved: Option C** — a non-persistent handler (a `CONSOLE`/stdout handler, most
     concretely) is excluded from this check entirely, not merely down-weighted. In a typical
     production deployment stdout is attached to nothing — no redirect, no aggregator, no
     terminal — so "console and file both at INFO" costs nothing and would be a false
     positive; the check only fires when duplicating the content genuinely duplicates disk
     usage. See "Adapter SPI" — `HandlerDiagnostics.targetPath` presence is exactly the
     persistent/non-persistent signal this reuses, no new SPI surface needed.
  4. **Autoflush on a busy handler** — a handler with autoflush enabled where it's likely to
     cost real throughput (Open decision #4).
  5. **Disk headroom** — usable space on the log volume vs. current write rate, rendered as
     a time-to-full estimate.
- `--json` output, matching `cli-transport.md`'s existing per-command conventions.
- JBoss LogManager / JUL adapter implementation (WildFly is the primary motivating
  environment per §16 — "the customer runs on a box … no network path to your support
  team"). Logback sequencing is Open decision #5.
- Read-only: requires only the `VIEW` capability (§9.3) already used by `listLoggers`; no
  new capability, no audit record (reads aren't audited, only mutations are — §9.7's own
  precedent).

**Explicitly out of scope for this slice** (deferred, each with what it needs):

- Any fix/mutation action. `logctl rotate --max-size 50M --keep 3` is named in §16.2 as "a
  legitimate follow-on" — a separate feature, on top of this one's diagnosis.
- `top`'s per-logger byte counting (§16.1) — sibling feature, own spec; `doctor`'s disk-
  headroom check does not depend on it (**Resolved: Option A** — a short in-process sample,
  below).
- Storm detection (§7.1) — sibling feature, own spec.
- Growing or curating the "known-chatty framework logger" list beyond a small seed — content
  work, not a mechanism question; the mechanism (a configurable/extensible list) is in scope,
  the list's contents are not frozen by this spec.
- Deep inspection of handler types this can't cheaply introspect (e.g. a syslog handler) —
  report what's discoverable per handler, skip silently what isn't, rather than modeling
  every handler type that exists.
- Tracking write rate as a metric over time — the disk-headroom estimate is a point-in-time
  sample (Resolved: Option A), not a maintained rate history.
- `doctor --fix` or any interactive remediation — read-only means read-only.

## The operation

`logctl doctor [--json]`

No target, no tier, nothing to confirm — the universal `--pid`/`--json` options are the only
ones that apply.

```
$ logctl doctor
[WARN]  FILE has no size cap — writes are unbounded.
        suggested: configure a size-based rotation policy on FILE.
[WARN]  root logger is at DEBUG — meaningfully more volume than the INFO default.
[OK]    no duplicate output across persistent (file) handlers.
[OK]    no autoflush handlers found on a busy path.
[WARN]  /var/log has 4.2 GB free at ~180 MB/h — about 23h to full at the current rate.

5 checks run — 0 critical, 3 warning, 0 info, 2 clean.
```

Note the third line: `CONSOLE` and `FILE` are both at `INFO` in this example, same as the
stock WildFly config — but since `CONSOLE` doesn't resolve a `targetPath` (Decision #3,
Option C), the duplicate-output check finds nothing to flag and reports clean rather than
raising a false positive against a console handler that, in most production deployments,
nothing is actually reading.

`--json` emits `{"findings": [...], "checksRun": N}`, one object per finding
(`DoctorFindingData`, below); `suggestedFix` is `null` when a check has no unambiguous fix to
name. Phone-test clean: no `--json` addition changes that.

## Data model

```
DoctorFinding {
    check: String             // stable id, e.g. "handler.unbounded-growth"
    severity: Severity        // OK | INFO | WARNING | CRITICAL
    subject: String           // what it's about -- a handler ref, a logger name, a path
    summary: String           // one line, what's rendered by default
    detail: String?           // optional longer explanation
    suggestedFix: String?     // an exact command/config change, when unambiguous
}
```

Framework-independent (lives in `api`, alongside `HandlerFloor`), so both `core` (to run the
checks) and every control surface (to render them) depend on it without depending on any one
adapter.

## Adapter SPI

`handler-floor-control.md`'s handler SPI is level-only (`handlerLevel`/`setHandlerLevel`).
Several `doctor` checks need static **configuration** facts about a handler that level
control never needed to ask for — file size caps, backup/retention counts, autoflush,
target path. New default method, empty unless an adapter overrides it:

```
/** Best-effort static facts about a handler's own configuration, for
 *  doctor's checks -- never a level. Every field empty where this
 *  framework/handler type doesn't expose the concept (e.g. a console
 *  handler has no file size cap). Default: everything empty. */
HandlerDiagnostics handlerDiagnostics(HandlerRef ref);

HandlerDiagnostics {
    maxFileSizeBytes: Long?
    backupCount: Integer?
    autoFlush: Boolean?
    targetPath: Path?
}
```

**Likely needs reflection into JBoss-LogManager-specific handler subclasses** (e.g.
`org.jboss.logmanager.handlers.PeriodicSizeRotatingFileHandler`'s `getRotateSize()` /
`getMaxBackupIndex()`) — none of this is on the JDK's base `java.util.logging.Handler` /
`FileHandler`, the same shape of problem `JulLoggingAdapter`'s retired `JbossHandlerNames`
solved for names (`doc/specs/handler-floor-control.md` "Adapter SPI"). Unlike that attempt,
this reflection target is real WildFly configuration WildFly itself populates, not something
already confirmed absent — but it still needs the same wrong-classloader care documented
there, and the same complete-degrade-to-empty discipline on any failure.

## Failure handling

- A check that can't run for this adapter/handler (the fact it needs is empty, or the
  adapter throws) is **skipped silently** for that subject — never a `doctor` failure. The
  summary line's "N checks run" already reflects only what actually ran.
- `logctl doctor` never exits non-zero for findings, however severe — it's advisory, exactly
  like the handler-floor warning (`handler-floor-control.md`). A non-zero exit is reserved for
  "the command itself failed" (couldn't attach, JMX error), not "found problems."

## Testing

- Unit — JBoss LogManager adapter: each check against a hand-built handler/logger
  configuration exercising both the flagged and the clean case.
- Unit — core: `diagnose()` aggregates findings from every check; a throwing/empty adapter
  fact degrades that one check to "skipped," not a failed call.
- Cross-process (`logaperture-it`, mirroring `WildFlyContainerIT`'s pattern): run `logctl
  doctor` against real standalone WildFly's stock `standalone.xml` and confirm the known
  baseline findings (or their absence) against that real configuration.

## Open decisions (sign-off)

*Resolved during review:* the chatty-logger seed list ships as a small hard-coded list —
Hibernate, Apache HttpClient, common connection-pool packages — for v1 (was #1, Option A);
the disk-headroom estimate uses a short in-process sample rather than waiting on `top`'s own
infrastructure (was #2, Option A); duplicate-output detection only counts two handlers that
both persist to disk — a `CONSOLE`/stdout handler is excluded from the check entirely, not
merely down-weighted, since in a typical production deployment nothing captures stdout at all
(no redirect, no aggregator, no terminal), so flagging it alongside a real `FILE` handler
would be a false positive against a cost that isn't actually there (was #3, Option C — see
"The five checks" above and the example's third line).

4. **"Busy handler" threshold for the autoflush check.** Is there a volume signal available
   yet to judge "busy" against, or does this check fire for autoflush-on-any-handler
   generically until `top` exists to give it a real threshold?
5. **Adapter sequencing.** JBoss LogManager / JUL only for this slice (mirrors
   `handler-floor-control.md`'s own sequencing), with Logback as a fast-follow, or both
   together?
6. **Severity thresholds.** Is "no size cap" always `WARNING`, or does it escalate to
   `CRITICAL` when the disk-headroom check is also low? Any cross-check severity
   interaction, or are all five checks scored independently?

## Exit criterion

Against a plain `java -jar` + Logback process and a standalone WildFly:

- `logctl doctor` on WildFly's stock `standalone.xml` (no config changes) reports its actual,
  known configuration accurately — including whatever the resolved open decisions above
  determine should or shouldn't fire against the stock config.
- `logctl doctor --json` output round-trips through a JSON parser with the documented shape.
- Every check that cannot run for an adapter/handler is skipped, never a `doctor` failure —
  confirmed by disabling the JBoss-LogManager-specific reflection path (as `JulLoggingAdapterTest`
  already does for name resolution) and re-running.
- No capability beyond `VIEW` is required; `VIEW` withheld makes `logctl doctor` exit 6 the
  same way `logctl levels` does today.
