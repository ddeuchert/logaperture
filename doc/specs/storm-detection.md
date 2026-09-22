# `logctl storms` — Report-Only Log Storm Detection

Status: **sign-off complete — all ten decisions resolved and folded in.** Ready for
implementation; nothing is implemented yet.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §7.1 (automatic storm
collapse — this slice is its report-only half), §4.2 (two-stage pipeline — gate stage),
§9.3 (capability model — `view`), §9.6 (suppression must never be silent — the reason
detection alone is worth shipping), §17 (roadmap — M1, Layer 1: "storm detection in
report-only mode"; **"M1 ships nothing that modifies behaviour"**).
Builds on: [`doc/specs/top.md`](top.md) (always-on per-context measurement installed at
`installContext` and re-armed via `reapplyOnReset`; adapter owns the mechanism, `core` owns
the clock and policy; `AggregateLevelControl` merge; bounded LRU state per §16.7; JMX-first
surface; `VIEW`-only, no audit; JUL/JBoss-LogManager adapter first, Logback a fast-follow),
[`doc/specs/doctor.md`](doctor.md) (sibling read-only diagnostic; "skipped, not failed"
degradation; `context` stamping; the small hard-coded seed list whose *mechanism* is in
scope but whose *contents* are not frozen by the spec).

## Functional summary

After this feature, the user will be able to:

- Run **`logctl storms`** against a live JVM and see every log storm the agent has detected —
  a burst of near-identical log events from one logger, past a volume threshold, within a
  short window — with, for each: which logger, the exception type (if any) and a normalized
  form of the message, when it started, how many events it has produced, its current rate,
  and whether it is still going or has ended (and when).
- See the **first occurrence** of each storm in full — the original message and stack trace —
  so the diagnostically useful event is in front of them without opening the log file.
- Limit the listing to the worst N storms (`--limit`), and get the same data as `--json` for
  scripting or a monitoring check.
- Trust that running it changed nothing: no event was suppressed, dropped, trimmed, or
  delayed. `logctl storms` only reports what already happened.
- Run it against a framework this slice doesn't instrument yet without it failing — it
  reports that no storms are being tracked, not an error.

## Scope of this slice

The M1 (§17) Layer 1 read-only diagnostic release. §7.1: "A storm *detector* that only
reports … is precisely the diagnostic that identifies a tight-loop bug, and it changes no
behaviour at all. That puts it in the read-only Layer 1 release, and it is probably the most
compelling single thing in that release." M1's overriding constraint (§17): **nothing that
modifies behaviour.**

**In scope:**

- One new read-only operation, `activeStorms()` / a report accessor, surfaced as `logctl
  storms` / `--json`, on the JMX surface first per `level-control.md`'s convention.
- An always-on **gate-stage observer**: a framework `Filter` installed once per context that
  sees every candidate log event, fingerprints it, updates a per-fingerprint tally counter,
  and drives a per-fingerprint two-state (`ONGOING` / `ENDED`) machine. The observer
  **never denies an event** — it returns exactly what it would have returned with the
  observer absent (chaining to any pre-existing filter, see "Adapter SPI"). It is an
  observer wearing a `Filter`'s clothes.
- **Fingerprinting** — the full two-tier scheme from §7.1: a cheap key computed on every
  event — `(logger name, level, throwable class, normalized message)`, allocation-free on the
  hot path — with one sampled `getStackTrace()` to pin a top-3–5-frame sub-key *once the
  cheap key already looks like a storm* (never per-event).
- **Message normalization** — a small, conservative, content-agnostic transform so
  `"failed for order 4821"` and `"failed for order 9134"` fingerprint together.
- A per-fingerprint **tally counter and two-state machine** (`ONGOING` / `ENDED`): a plain
  count that resets on a gap, crosses a threshold to declare a storm, and goes quiet to end
  it — see "Detection algorithm". No per-event timestamp history; no background timer.
- **First-occurrence retention** — the rendered message and stack trace of the first event
  of each tracked storm, size-capped, captured lazily on first reaching storm state.
- `--limit N` worst-first truncation; `--json`; `context` stamping when the result spans more
  than one context (mirrors `doctor`/`top`).
- Bounded memory (§16.7): a lock-striped `ConcurrentHashMap` of tracked fingerprints
  (approximate-LRU-evicted) and a small capped active/recently-ended storm history — see
  "Bounded state".
- JUL / JBoss LogManager adapter implementation only for this slice; Logback a fast-follow
  (mirrors `doctor` #5 and `top`).
- Read-only: `VIEW` capability only (§9.3), no new capability, no audit record — reads aren't
  audited, only mutations are (§9.7 precedent, same as `doctor`/`top`).

**Explicitly out of scope for this slice** (deferred, each with what it needs):

- **Any suppression, collapse, rate-limit, dedupe, or trim.** The whole of §7.1's
  suppression behaviour — "subsequent identical occurrences are counted rather than written",
  gate-stage denial to reclaim CPU, self-relaxing collapse — is **M2**. This slice detects
  and reports; it does not act.
- **The in-log announcement lines** from §7.1's worked example (`storm collapse engaged: …`,
  `storm continuing: …`, `storm ended: …`). Writing new lines into the application's log is a
  behaviour change, however additive, and M1 ships none. Whether the agent emits these to its
  *own* §4.5 diagnostic writer (not the app framework) is Decision #5.
- **`sampleFull` / the keep-one-in-N escape hatch (§7.4)** — only meaningful once something is
  being suppressed. M2.
- **Per-rule / declarative-rule interaction (§7.2–7.3)** — there is no rule engine in M1. The
  detector is the content-agnostic layer that runs *underneath* rules (§7.1); the rules
  themselves are later.
- **Tuning the normalization ruleset or any storm thresholds as curated content** — the
  *mechanism* (a defined normalization, configurable thresholds) is in scope; the exact
  regexes and default numbers are seeded conservatively and not frozen by this spec (mirrors
  `doctor`'s chatty-logger list).
- **A history store that survives JVM restart** — active + recently-ended storms live in
  bounded memory only. Persistence across restart/redeploy is not in this slice (and largely
  moot: a storm is a live-incident diagnostic).
- **The Logback adapter implementation** — fast-follow once the JUL / JBoss LogManager slice
  ships (Decision #9).

## The operation

`logctl storms [--json] [--limit N]`

No target, no tier, nothing to confirm — the universal `--pid`/`--json` options and `--limit`
are the only ones that apply.

```
$ logctl storms
[ONGOING]  com.acme.batch.Worker  org.acme.SlotException  "no capacity"
           started 2026-09-05T03:14:02Z (27m ago) — 3,104,772 events — ~114,000/min
           first occurrence:
             03:14:02 ERROR [com.acme.batch.Worker] Unable to reserve slot
                 org.acme.SlotException: no capacity
                     at com.acme.batch.Worker.reserve(Worker.java:88)
                     ... 41 more

[ENDED]    org.apache.http.impl.conn  (no exception)  "connection reset by peer"
           03:02:11Z–03:09:40Z (7m29s) — 812,004 events — ended 12m ago

2 storms tracked — 1 ongoing, 1 ended. measured since agent start, 2026-09-05T14:02:11Z.
```

A fingerprint that never crossed the threshold is never listed — "tracked" means "reached
storm state at least once", not "every fingerprint the detector has a counter for".

`--json` emits `{"storms": [...], "trackedCount": N, "ongoingCount": N,
"measurementStartedAt": "..."}`, one object per storm (`StormData`, below), sorted worst-first
(ongoing before ended, then by event count). `--limit` applies identically to `--json`;
`trackedCount` is the true pre-truncation count, same discipline as `top`'s.

Each storm also carries `context`: `null` from a single-context service, or the owning
context's stable key once `AggregateLevelControl` stamps it. The text renderer shows a
`[context]` prefix only when the result actually spans more than one context — same rule as
`logctl status` / `levels` / `doctor` / `top`.

`logctl storms` never exits non-zero for what it finds — purely informational, like `doctor`
and `top`. A non-zero exit is reserved for "the command itself failed" (couldn't attach, JMX
error).

## Data model

```
StormReport {
    storms: List<Storm>              // already sorted worst-first
    trackedCount: int                // storms that reached storm state, pre-`--limit`
    ongoingCount: int                // subset still ONGOING
    measurementStartedAt: Instant?   // when detection began; null only if no context installed one
}

Storm {
    fingerprint: StormFingerprint    // the identity below
    status: StormStatus              // ONGOING | ENDED
    firstEventAt: Instant
    lastEventAt: Instant
    endedAt: Instant?                // set iff status == ENDED
    eventCount: long                 // total matching events since firstEventAt
    firstOccurrence: String?         // rendered first message + trace, size-capped (Decision #6)
    context: String?                 // owning context's stable key, or null
}

StormFingerprint {
    loggerName: String
    level: Level
    throwableClass: String?          // null when the event carried no Throwable
    normalizedMessage: String        // after the Decision #3 transform
    topFrames: List<String>?         // present only if frame escalation ran (Decision #2)
}
```

Rate (events/min) is a rendering-time derivation from `eventCount` and the elapsed
`firstEventAt … (lastEventAt | now)` span — not a stored field, same reasoning as `top`'s
"keep the wire shape to raw counts".

Framework-independent — lives in `api`, alongside `DoctorFinding` and `LoggerByteCount`.

## Detection algorithm

The detector answers one question on every candidate event — *is this same message repeating
far too fast right now?* — and must answer it without measurably slowing the application. So
it keeps **no per-event history**. It keeps a **tally counter**, like a door counter clicked
once per arrival with an occasional glance at the clock.

For each distinct **fingerprint** (the §7.1 cheap key: logger, level, throwable class,
normalized message) the detector holds two mutable numbers:

- `count` — matching events seen in the current burst
- `lastSeenNanos` — `System.nanoTime()` of the most recent matching event

On each matching event:

1. **Gap check.** If `now - lastSeenNanos` exceeds the window (default 10 s), this fingerprint
   is not in a burst — set `count = 1`.
2. **Otherwise** `count++`. If `count` reaches the threshold (default 1,000) and this
   fingerprint is not already a storm, the state flips to `ONGOING` and `firstEventAt` is
   recorded *inside* the per-entry critical section; the one sampled `getStackTrace()` and
   the first-occurrence render happen *after* the lock is released and are published back with
   a short second write (see "Bounded state"), so the locked section stays arithmetic-only.
3. Set `lastSeenNanos = now`; advance `lastEventAt` and the lifetime `eventCount` (kept for
   the report, separate from `count`).

A storm becomes `ENDED` when a fingerprint is next examined — its own next event, or a
`logctl storms` sweep — and `now - lastSeenNanos` exceeds the quiet period (default 60 s).
`endedAt` is `lastEventAt`. No background timer, no sweeper thread.

A fingerprint that reaches the threshold again *after* its storm has `ENDED` becomes a **new**
storm, with its own `firstEventAt` and first occurrence; the ended entry stays in history
until evicted (see "Bounded state").

**The rate shown in the report is not `count`.** It is the lifetime average
`eventCount / (lastEventAt − firstEventAt)`, derived at render time — the same
raw-counts-on-the-wire discipline `top` uses.

**What this deliberately gives up.** The counter resets on any gap longer than the window, so
a burst that pauses for just over the window and resumes is reported as two storms rather than
one, and a fingerprint hovering right at the threshold may be noticed a few seconds late or —
if it also straddles a reset — missed entirely. Every case it misses is a *marginal* one:
something repeating just fast enough to look suspicious but not fast enough to be
unmistakably broken. A genuine runaway loop emits thousands per second and crosses the
threshold near-instantly regardless of reset alignment. This is the intended trade: **err
toward saying nothing over adding arithmetic to the hot path.** A smoothed sliding-window
estimate would close the marginal gaps at the cost of more work per event, and is explicitly
not done.

**Slow-burn volume** — a fingerprint emitting, say, 20 events/second for hours: real disk
pressure, but never 1,000 within 10 s — is **not** detected here, by design. Catching it needs
a second counter on a longer clock, i.e. the per-event overhead this rules out. `logctl top`
surfaces that case by byte volume instead.

**Per-event cost:** compute the fingerprint (a few field reads and a hash), one map lookup,
one subtraction, one comparison, one increment. Allocation-free once a fingerprint is known.
The sampled `getStackTrace()` fires at most once per storm, never on the steady-state hot
path.

**Tunables** (seeded, `-D`-overridable, not frozen by this spec):

| property | default | meaning |
|---|---|---|
| `logaperture.storm.thresholdEvents` | `1000` | `count` that declares a storm |
| `logaperture.storm.windowSeconds` | `10` | gap that resets `count` |
| `logaperture.storm.quietSeconds` | `60` | silence that marks a storm `ENDED` |

## Adapter SPI

`LoggingAdapter` gains two default methods, mirroring `installByteCounting()` / `byteCounts()`:

```
/** Installs an always-on gate-stage observer on every context this adapter
 *  can act on right now, feeding each candidate log event to `detector`
 *  reduced to a framework-independent observation. The observer MUST NOT
 *  deny any event: it returns exactly the result the framework would have
 *  produced without it, chaining to any filter already installed.
 *  Idempotent: safe to call again (context-install retry, or core's periodic
 *  re-verification standing in for a reconfiguration hook this framework
 *  doesn't have) without installing a second observer or losing state.
 *  Default no-op, for a framework this slice doesn't instrument. */
default void installStormDetection(StormObserver detector) { }

/** Everything the detector has accumulated — one entry per fingerprint that
 *  reached storm state. Default empty. */
default List<Storm> storms() { return List.of(); }
```

`StormObserver` (in `core`) receives a `StormObservation` — a small value type carrying
`loggerName`, `level`, `throwableClassName` (nullable), the raw message, `hasThrown`, a
lazy `Supplier<String>` for the rendered first-occurrence text (invoked at most once per
fingerprint, only when a new storm is first recorded, and outside `StormDetector`'s
per-entry lock), and an `Instant`. The adapter's `Filter` does the framework-specific
extraction and nothing else; all fingerprinting, normalization, the tally counter, and the
state machine live in `core` (`StormDetector`) — the interesting, testable logic.

**JUL / JBoss LogManager attach point.** `java.util.logging` and JBoss LogManager both allow
exactly **one** `Filter` per `Logger` and one per `Handler`. The installed filter captures
any filter already present and delegates to it for the actual allow/deny verdict, so the
observer is verdict-transparent; on teardown it restores the captured filter. The
frame-escalation `getStackTrace()` runs only on a sampled event once the cheap key already
indicates a storm — never on the hot path — and, per its "invoked at most once per
fingerprint" contract, only once per burst: two throw sites storming *concurrently* under the
same cheap key are not distinguished by it (sequential, non-overlapping bursts are). See
[issue #77](https://github.com/ddeuchert/logaperture/issues/77).

## Reconfiguration and lifecycle

Installed once per context at the same `installContext` point `TopService` / `DoctorService`
are constructed (`NoneContainer` / `WildFlyContainer`), and re-armed on a framework
reconfiguration via the existing `onReset` hook — folded into the same `reapplyOnReset`
lambda that already re-applies baselines, overrides, and `topService.startMeasuring()`.
Accumulated storm state is **not** cleared by a re-arm: it's keyed by fingerprint, not filter
identity, and remains meaningful history across a reconfiguration the user didn't ask to
reset.

`measurementStartedAt` is recorded once, in `core` (a `StormService`, matching `TopService`),
the first time a context arms detection — not moved forward by a later re-arm.

## Bounded state (§16.7)

Two capped structures per context, all defaults `-Dlogaperture.storm.*`-tunable and seeded,
not frozen.

**Fingerprint counters** — the two-number tally (`count`, `lastSeenNanos`) plus a small state
field, for *every* fingerprint seen, most of which never storm. An unbounded map keyed on
`(logger, normalized message)` is exactly the memory-leak shape §16.7 names.

- A `ConcurrentHashMap` keyed by the 64-bit fingerprint hash. Each entry's per-event update
  (`count++`, gap check, the state flip) is guarded by `synchronized` on the entry object, so
  contention is **striped per fingerprint**: threads storming one fingerprint self-contend on
  that entry, and unrelated loggers on other threads never touch it.
- This is a deliberate divergence from `top`'s `TopCounters` (one `synchronized` map per
  context; issue [#24](https://github.com/ddeuchert/logaperture/issues/24) tracks the
  contention that adds). The storm `Filter` runs on *every enabled event*, on the calling
  thread, before any per-handler lock — a single per-context monitor there would be a
  serialization point that gets monotonically worse as thread count rises, and a scalability
  cliff under **virtual threads** (a logging path carrying tens of thousands to millions of
  threads). Striped locking scales flat with thread count instead.
- The locked section does no I/O, acquires no nested lock, and allocates nothing — so it
  cannot pin a carrier thread harmfully even on JDK 21 (a virtual thread never *blocks*
  inside the monitor), and the concern is gone entirely on JDK 24+ (JEP 491). The sampled
  `getStackTrace()` and the first-occurrence render run *outside* the entry lock and publish
  their result via an `AtomicReference` on the entry.
- The detector holds **no `ThreadLocal` or per-thread accumulator** anywhere — all state is
  in the shared map. Per-thread state with millions of virtual threads is a footgun; this is
  a standing constraint, not an implementation detail.
- Capped at **4,000** distinct fingerprints (`-Dlogaperture.storm.maxTrackedFingerprints`),
  **approximate-LRU** evicted: each entry carries `lastTouched`; on insert past the cap,
  sample a handful of entries and evict the oldest. (No `LinkedHashMap` access-order — that
  needs the very per-context lock this design removes.) A mid-ramp fingerprint that is
  evicted simply re-inserts on its next event and re-climbs; acceptable degradation, and only
  reachable when thousands of *other* fingerprints are more active — itself a `top`/`doctor`
  signal.
- Lock-free counters (`LongAdder` / CAS) are a possible later refinement, unnecessary for
  this slice given how short the critical section is.

**Storm history** — fingerprints that reached storm state (active + recently ended). Read
only on `logctl storms`, written only on storm engage/end — not a hot path; a plain
`synchronized` structure is fine.

- Capped at **100** (`-Dlogaperture.storm.maxHistory`). `ONGOING` storms are never evicted;
  the least-recently-active `ENDED` storm is evicted first. If every entry is `ONGOING` at
  the cap (an app catastrophically broken in a hundred distinct ways at once), the *new*
  storm is dropped and a disclosed "N detected, not retained" count is surfaced in the report
  — never a silent loss of a storm under observation.
- Retained first-occurrence text is capped at **8 KB** per storm
  (`-Dlogaperture.storm.firstOccurrenceBytes`), truncated with a marker, so one enormous
  trace can't dominate the footprint.

Worst-case footprint per context is roughly 1.3–1.4 MB (counter map ~500 KB + full history
~850 KB), well inside §16.7's "under 5 MB" headline. A deployment with several contexts holds
that per context.

## Failure handling

- A framework/handler this can't observe (Logback and `none` this slice) contributes nothing
  — `storms()` returns empty, never a `logctl storms` failure. Same "skipped, not failed"
  discipline as `doctor`/`top`.
- If the observer's own bookkeeping throws, it is swallowed and the event passes through
  untouched — a detector bug must never break the application's logging or drop a line.
- `logctl storms` never exits non-zero for findings.

## Testing

- Unit — `core` (`StormDetectorTest`): `count` crossing the threshold before a window-gap
  transitions the fingerprint to `ONGOING` with the right `firstEventAt`; a gap longer than
  the window resets `count` to 1 without a storm; further matching events keep a storm
  `ONGOING` and advance `lastEventAt`/`eventCount`; a gap beyond the quiet period, observed on
  the next event or a sweep, transitions it to `ENDED` with `endedAt == lastEventAt`; a
  fingerprint that resets after a gap and then re-crosses the threshold is a *new* storm, not
  a revival of the ended one; two events with the same exception type but different throw
  sites, in **sequential, non-overlapping bursts**, split via the sampled frame sub-key —
  **known gap:** two *concurrently interleaved* bursts sharing a cheap key are not split;
  see [issue #77](https://github.com/ddeuchert/logaperture/issues/77); normalization merges
  numeric-variant messages
  and keeps genuinely different ones apart; the counter map evicts least-recently-updated at
  capacity; storm history keeps `ONGOING` ahead of `ENDED`; a throwing observation is
  swallowed and the event passes through.
- Unit — `core` (`StormDetectorConcurrencyTest`): many threads feeding one fingerprint
  concurrently produce an `eventCount` equal to the number of observations (no lost updates)
  and exactly one `ONGOING` transition / one first-occurrence render; threads feeding
  thousands of distinct fingerprints past the counter-map cap keep the map at its bound and
  never throw; the first-occurrence supplier is never invoked while the per-entry lock is
  held (a supplier that asserts on lock state, or a timing probe).
- Unit — `core` (`StormServiceTest`, `AggregateLevelControlTest`): `activeStorms()` sorts
  worst-first and `--limit` truncates (0/negative = all); a second arm call doesn't move
  `measurementStartedAt`; a multi-context merge stamps `context` on every row and
  re-sorts/truncates over the merged set; `trackedCount`/`ongoingCount` are pre-truncation;
  an all-`ONGOING` history at capacity drops a new storm and reflects it in the disclosed
  not-retained count.
- Unit — JUL adapter (`JulStormObserverTest`): the installed `Filter` returns the identical
  verdict a pre-existing filter would have (allow *and* deny cases), feeds every candidate
  event to the detector, and a second `installStormDetection` call doesn't double-install or
  double-count; the rendered first-occurrence supplier is invoked at most once per
  fingerprint.
- Unit — CLI (`CommandsTest`, `JsonTest`): text renderer's counts and `--json`'s
  `trackedCount`/`ongoingCount` use the true counts, not `storms.size()`, when a test wires a
  report whose numbers deliberately differ; an empty report renders "No log storms detected."
  rather than throwing; `[context]` prefix appears only for a multi-context report.
- Cross-process (`logaperture-it`'s `WildFlyContainerIT`): a probe deployment driven into a
  tight exception-throwing loop produces a storm that `logctl storms` reports with a
  plausible count/rate and a non-empty first occurrence, and `logctl storms --json`
  round-trips with the documented shape; a second, distinct loop is reported as a separate
  storm.

## Open decisions (sign-off)

Numbering is stable and matches the review artifact.

*Resolved during review:* the observer is a **real gate-stage `Filter`** on JUL / JBoss
LogManager, capturing and delegating to any pre-existing filter so it is verdict-transparent,
restoring it on teardown (was #1, Option A) — the seam §7.1's M2 suppression builds on, and it
sees volume before a level/filter drops it. Fingerprinting is the **full two-tier scheme** —
cheap key on every event, one sampled `getStackTrace()` to pin a top-3–5-frame sub-key once
the cheap key already looks like a storm (was #2, Option B). Message normalization is a
**small hard-coded transform** — collapse digit / hex / UUID runs to a placeholder, collapse
whitespace, trim, cap length — contents seeded but not frozen (was #3, Option A). Detection
uses a **plain tally counter, not a sliding window** — see "Detection algorithm"; thresholds
`1000` events / `10 s` window / `60 s` quiet, all `-D`-tunable; slow-burn volume is out of
scope by design (was #4, resolved to the simplest option — err toward missing a marginal
storm rather than add hot-path arithmetic). The agent **announces storms nowhere** at
detection time — `logctl storms` / JMX / `--json` are the only surfaces (was #5, Option A).
The **first occurrence is retained** as rendered message + trace, size-capped, captured
lazily on first reaching storm state (was #6, Option A). The **detector logic lives in
`core`** (`StormDetector`), fed by a dumb adapter `Filter` that only extracts a
framework-independent `StormObservation` — a deliberate divergence from where `top` put
`TopCounters`, for a genuinely hotter path (was #7, Option A). This slice implements the **JUL
/ JBoss LogManager adapter only**, Logback a fast-follow (was #9, Option A). `logctl storms`
is a **standalone command**, with `logctl doctor` printing a one-line pointer when storms are
active (was #10, Option A).

**Bounded state (was #8).** The counter map diverges from `top`'s single `synchronized`
`TopCounters` (issue #24): a `ConcurrentHashMap` keyed by fingerprint hash with per-entry
`synchronized` (lock striped per fingerprint) and approximate-LRU eviction — because the storm
`Filter` runs on every enabled event before any handler lock, and a single per-context monitor
there would be a serialization cliff under **virtual threads**. Caps: **4,000** fingerprint
counters, **100** storm-history entries (`ONGOING` never evicted; all-`ONGOING`-at-cap drops
the new storm with a disclosed count), **8 KB** first occurrence. The locked section is
arithmetic-only (no I/O, no nested lock, no allocation — no harmful carrier pinning even
pre-JEP-491); `getStackTrace()` and the first-occurrence render run outside it. No
`ThreadLocal` / per-thread state anywhere in the detector. Full detail in "Bounded state".

## Exit criterion

Against a plain `java -jar` + JUL process and a standalone WildFly (Logback per Decision #9):

- A logger driven into a tight loop emitting a repeated exception is reported by `logctl
  storms` as one `ONGOING` storm with a plausible count and rate, the correct logger and
  exception class, a normalized message, and a non-empty first occurrence — confirmed by a
  `core` unit test and by `WildFlyContainerIT` against a real probe deployment.
- When the loop stops, a later `logctl storms` shows that storm `ENDED` with an `endedAt`
  and the final count.
- Two independent loops are reported as two separate storms.
- `logctl storms --json` round-trips through a JSON parser with the documented shape —
  unit and cross-process.
- Running `logctl storms` (and the detector running continuously) suppresses, drops, delays,
  or alters **no** log event — asserted by the transparent-filter unit test (identical verdict
  with and without the observer) and by a cross-process check that a known line count through
  a storm is unchanged.
- The per-event path takes no per-context lock — concurrent logging on unrelated fingerprints
  never serializes through a shared monitor (`StormDetectorConcurrencyTest`), so the detector
  is safe to leave armed on a virtual-thread-heavy logging path.
- No capability beyond `VIEW` is required.
