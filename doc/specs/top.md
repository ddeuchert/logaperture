# `logctl top` — Byte Volume Per Logger

Status: signed off, not yet implemented.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §16.1 (`top`), §9.3 (capability
model — `view`'s table already names "logger names and metrics" as its risk surface), §17
(roadmap — M1, Layer 1: "a read-only diagnostic release").
Builds on: [`doc/specs/handler-floor-control.md`](handler-floor-control.md) (`HandlerRef`, the
persistent-handler signal), [`doc/specs/doctor.md`](doctor.md) (sibling read-only diagnostic —
shares the JMX-first convention and the persistent-handler reuse pattern; `doctor`'s own
disk-headroom check deliberately deferred to `top`'s "own infrastructure" rather than duplicate
it — Decision #2 there).

## Functional summary

After this feature, the user will be able to:

- Run **`logctl top`** against a live JVM and see, per logger, how many bytes it has written to
  disk over time — sorted worst-first, with a bytes-per-hour rate and a projected daily total.
- See what fraction of each logger's volume is stack-trace bytes, so "is one exception spamming
  traces" is answerable without opening the file.
- See how long the numbers have been measured over (e.g. "since agent start, 6h 12m ago"), so a
  rate is never mistaken for an instantaneous reading.
- Limit the listing to the worst N offenders (`--limit`), or get every tracked logger.
- Get the same figures as `--json`, for scripting or a monitoring check.
- Run `logctl top` against a framework this feature doesn't instrument yet without it failing —
  it reports that no measurements are available, not an error.

## Scope of this slice

Layer 1 (§17) — read-only measurement, no rule engine, no suggested-fix *command* (none exists
yet to suggest — see "Explicitly out of scope"). §16.1 frames this as "probably the single most
valuable operations feature"; this slice is the smallest version of it that is still genuinely
useful standalone.

**In scope:**

- One new read-only operation, `topLoggers()`, surfaced as `logctl top` / `--json`, JMX first
  per `level-control.md`'s convention.
- Continuous, always-on byte-count measurement of every **persistent** (file-backed) handler's
  actual formatted output, attributed to each record's originating logger name — installed once
  per context at context-install time, not a point-in-time sample (contrast `doctor`'s
  disk-headroom check, which is deliberately a 300ms sample precisely so it doesn't need this
  machinery — Decision #2 there). "Always-on" matters: by the time a customer notices a full
  disk and runs `logctl top`, the interesting volume already happened, so measurement has to
  have been running since boot, not started on demand.
- The stack-trace-byte fraction per logger — answerable purely from measurement (does the
  record carry a `Throwable`, how many of the formatted bytes came from its trace), no rule
  engine required.
- `--limit N` (default 10) worst-offender truncation, sorted by bytes-per-hour descending.
- JBoss LogManager / JUL adapter implementation only for this slice (mirrors `doctor.md`
  Decision #5 — WildFly is the primary motivating environment per §16); Logback is a
  fast-follow, not bundled with this slice.
- Bounded memory for the counter state (§16.7): a capped, LRU-evicted map of tracked logger
  names.
- Read-only: requires only the `VIEW` capability already used by `listLoggers`/`doctor`; no new
  capability, no audit record.

**Explicitly out of scope for this slice** (deferred, each with what it needs):

- **The `suggested: trimStackTrace on ... — would save ~9.4 GB/day` line** from §16.1's own
  worked example. `trimStackTrace` is a Feature 3 (squelch engine) render-stage action that
  does not exist yet (M2, per the roadmap) — this slice reports the stack-trace-byte fraction
  as data, not a command to run. Revisit once Feature 3 ships.
- **Per-rule byte accounting** (§16.1: "extend to ... per-rule") — same dependency, same
  deferral.
- **Per-throwable-type breakdown** (§16.1: "extend to per-throwable-type") — a second counter
  dimension (logger × throwable class), more unbounded-state risk for a first slice; a natural
  follow-on once the flat per-logger version is proven out.
- **Hierarchical rollup** — a leaf logger's bytes are not also folded into an ancestor's row.
  Matches the flat, per-actual-logger-name model `doctor`'s checks already use, and the
  worked example in §16.1 itself names a specific leaf logger
  (`org.apache.http.impl.conn`), not a package prefix.
- **A `logctl top --reset` command** — this slice's only window is "since this context was
  installed." Additive later without a spec change if it turns out to matter in practice.
- **Non-persistent (console-only) handlers** — mirrors `doctor.md` Decision #3's reasoning
  exactly: in a typical production deployment nothing reads stdout, so a console handler's
  volume isn't the volume that fills a disk. A logger that writes only to a console handler
  reports zero bytes here, not a false reading.
- **The Logback adapter implementation** — a fast-follow once the JBoss LogManager / JUL slice
  ships, not bundled with it (mirrors `doctor.md` Decision #5).

## On interpretation — expected vs. anomalous volume

Raised during sign-off: a logger that intentionally emits one line per unit of business
activity — an access-log-style logger writing one line per incoming request is the concrete
case — will legitimately dominate byte volume in most applications, for reasons that have
nothing to do with misconfiguration. `top` cannot tell "this is high because that's the
logger's job" from "this is high because something's wrong" by volume alone, and this slice
does not attempt to.

This isn't a gap to close before shipping — it's a limit of what pure volume measurement can
say, and worth stating plainly rather than implying `top`'s ranking is a verdict. Two things
are true at once: the tool's job is to say *where the bytes are going*, not to judge whether
they should be; and the one piece of context this slice already reports — the stack-trace-byte
fraction — is a partial, incidental proxy for the distinction anyway. An access-log logger
should read near 0% stack traces; a logger whose volume comes from a misbehaving/looping
exception should read high. That's a side effect of a metric built for a different purpose
(§16.1's own "98% stack traces" column), not a feature designed to answer this question, and it
won't catch a verbose-but-exception-free case (e.g. a logger dumping full request/response
bodies).

Deliberately not attempted this slice, pending real usage to say whether it's worth the
complexity: any form of "expected volume" allowlist a user could mark a logger against (the
inverse of `doctor.md` Decision #1's chatty-logger seed list — "known high-volume by design"
instead of "known chatty"), or a rolling-window comparison that could flag a *change* in a
logger's volume rather than its absolute size (blocked on Decision #3's "since agent start"
window regardless). Revisit once this slice has run against a real workload long enough to know
which of these, if either, is worth building.

## The operation

`logctl top [--json] [--limit N]`

No target, no tier, nothing to confirm — the universal `--pid`/`--json` options and `--limit`
are the only ones that apply.

```
$ logctl top
org.apache.http.impl.conn   412 MB/h   (9.6 GB/day)   98% stack traces
com.acme.billing              88 MB/h   (2.1 GB/day)   12% stack traces
ROOT                            4 MB/h   (96 MB/day)    0% stack traces

measured over 6h 12m (since agent start, 2026-09-05T14:02:11Z) — 3 loggers tracked.
```

A logger with zero measured bytes is never listed — "tracked" means "has emitted at least one
byte through a counted handler," not "every logger name this JVM knows about" (that list can be
large and is `logctl levels`'s job, not this one's).

`--json` emits `{"loggers": [...], "measurementStartedAt": "...", "trackedCount": N}`, one
object per logger (`LoggerByteCountData`, below), already sorted worst-first; `--limit` applies
identically to `--json`.

## Data model

```
LoggerByteCount {
    loggerName: String        // the record's originating logger, e.g. "org.apache.http.impl.conn"
    totalBytes: long          // measured since measurementStartedAt
    stackTraceBytes: long     // portion of totalBytes attributable to a Throwable's formatted trace
    context: String?          // owning logging context's stable key, or null (mirrors doctor.md)
}
```

Rate (`bytesPerHour`) and the stack-trace percentage are rendering-time derivations from
`totalBytes`/`stackTraceBytes` and the elapsed measurement duration — not stored fields, same
reasoning as `doctor.md`'s severity-is-derived discipline: keep the wire shape to raw counts,
computed for either output the same way.

Framework-independent (lives in `api`, alongside `DoctorFinding`).

## Adapter SPI

`LoggingAdapter` gains:

```
/** Installs render-stage byte counting on every persistent handler this
 *  adapter can act on right now, attributing each counted record's bytes to
 *  its originating logger name -- doc/specs/top.md. Idempotent: safe to call
 *  again (e.g. after a reconfiguration re-hook re-creates handlers) without
 *  double-counting or losing prior totals for handlers that still exist.
 *  Default no-op, for a framework this slice doesn't instrument. */
default void installByteCounting() {
    // no-op by default
}

/** Byte-count totals accumulated since installByteCounting() was first
 *  called, one entry per logger name that has emitted at least one counted
 *  byte. Default empty. */
default List<LoggerByteCount> byteCounts() {
    return List.of();
}
```

The JBoss LogManager / JUL implementation wraps each persistent handler's installed `Formatter`
(`Handler.setFormatter`) with a counting decorator: call the real formatter once (no extra
formatting work — "nearly free" per §16.1), measure the returned string's UTF-8 byte length,
and add it to a counter keyed by `record.getLoggerName()`. A record's `Throwable` (if any) is
formatted once by the real formatter already; the stack-trace share is measured by formatting
just `record.getThrown()`'s trace separately with a throwaway `PrintWriter`/`StringWriter` and
diffing lengths — cheap relative to the I/O the record is about to cause regardless, and never
double-invokes the handler's actual formatter.

Persistent-handler selection reuses `handlerDiagnostics(ref).isPersistent()`, the same signal
`doctor`'s duplicate-output check already keys on — no new SPI surface needed for "which
handlers count."

## Reconfiguration and lifecycle

Installed once per context, at the same `installContext` point `DoctorService` is constructed
(`NoneContainer`/`WildFlyContainer`). A framework reconfiguration event (JUL has none of its
own — polled, per §4.3) that discards and re-creates handlers loses the counting wrap along with
everything else; `TopService`/`installByteCounting()` re-attaches via the existing `onReset`
hook (folded into the same `reapplyOnReset` lambda that already re-applies baselines and
overrides), matching every other per-handler concern this codebase already re-establishes on
reset. Accumulated totals are **not** cleared by a re-install — they're keyed by logger name,
not handler identity, and remain meaningful history across a reconfiguration event that a
customer didn't ask `top` to reset.

`measurementStartedAt` is recorded once, the first time a context's `installByteCounting()`
runs — owned by `core` (`TopService`), not the adapter, matching the "adapter is a dumb
getter/setter, core owns policy" discipline `LoggingAdapter`'s own class doc states. A later
re-install (post-reset) does not move this instant forward.

## Bounded state (§16.7)

The counter map is capped at a fixed default of 1,000 distinct logger names, evicting the
least-recently-updated entry once the cap is reached — an unbounded map keyed on
logger name is exactly the memory-leak shape §16.7 calls out by name. Configurable via
`-Dlogaperture.top.maxTrackedLoggers=N`, matching the existing `-Dlogaperture.sweep.seconds=`
convention for a tunable with a sane default.

## Failure handling

- A framework/handler this can't instrument (Logback and `none` this slice; any handler with no
  persistent target) contributes nothing — `byteCounts()` returns empty for it, never a `top`
  failure, mirroring `doctor.md`'s "skipped, not failed" discipline.
- `logctl top` never exits non-zero for what it finds — purely informational, like `doctor`.

## Testing

- Unit — JBoss LogManager adapter: a counted persistent handler's formatted output is measured
  correctly; a console (non-persistent) handler contributes nothing; a record carrying a
  `Throwable` splits into `totalBytes`/`stackTraceBytes` correctly; `installByteCounting()`
  called twice does not double-count a handler that was already wrapped.
- Unit — core: `TopService.topLoggers()` sorts worst-first, `--limit` truncates correctly,
  `measurementStartedAt` is stable across a second call; a throwing/empty adapter degrades to
  an empty tracked-logger list, not a failed call; the bounded-map cap evicts the
  least-recently-updated logger once exceeded.
- Cross-process (`logaperture-it`, mirroring `WildFlyContainerIT`'s pattern): run `logctl top`
  against real standalone WildFly after generating known volume on a known logger and confirm
  the reported byte count is in the right ballpark (exact-byte assertions are too brittle
  cross-process; an order-of-magnitude/monotonicity check is the right bar, same caution
  `doctor.md`'s disk-headroom cross-check used).

## Open decisions (sign-off)

*Resolved during review, Option A throughout:* only persistent (file-backed) handlers count
toward a logger's bytes, reusing `handlerDiagnostics(ref).isPersistent()` (was #1); attribution
is flat by `record.getLoggerName()`, no hierarchy rollup (was #2); measurement is continuous
counters since context-install, rate computed as `totalBytes / elapsedSinceInstall`, no reset
command this slice (was #3); the `suggested: trimStackTrace ...` line from §16.1's own example
is omitted entirely — this slice reports the stack-trace-byte fraction as data, not a command to
run (was #4); the counter map is capped at 1,000 distinct logger names with LRU eviction,
configurable via `-Dlogaperture.top.maxTrackedLoggers=N` (was #5); a reconfiguration event
re-wraps newly-created persistent handlers via the existing `onReset` hook without clearing
accumulated totals (was #6); `core`'s `TopService`, not the adapter, owns
`measurementStartedAt` (was #7); `AggregateLevelControl` gains a `topLoggers()` that merges and
context-stamps every registered context's results, exactly like `diagnose()` does for
`DoctorFinding` (was #8, confirming existing precedent); and `--limit` defaults to 10, with
`--limit 0` showing every tracked logger (was #9).

**Raised but deliberately not resolved into a mechanism this slice** — see "On interpretation"
above: whether/how to distinguish a logger that's high-volume by design (an access log) from
one that's high-volume because something's wrong. No decision needed because no mechanism is
being built for it yet; revisit once this slice has run against a real workload.

## Exit criterion

Against a plain `java -jar` + JUL process and a standalone WildFly (Logback is a fast-follow,
Decision from "Explicitly out of scope" — not part of this exit criterion):

- `logctl top` run after generating known volume on a known logger through a persistent handler
  reports that logger with a plausible byte count and rate, and omits a console-only logger
  entirely.
- A record carrying a `Throwable` contributes correctly to `stackTraceBytes` as well as
  `totalBytes`.
- `logctl top --json` output round-trips through a JSON parser with the documented shape.
- `--limit` truncates the text and JSON output identically.
- No capability beyond `VIEW` is required.
- Measured overhead per logged event stays within noise of the `top`-uninstrumented baseline —
  confirmed by a JMH benchmark per §10's discipline, not eyeballed.
