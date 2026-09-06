# `logctl storms` — Report-Only Log Storm Detection

Status: **draft — sign-off review in progress.** Open decisions below are
enumerated for review, not yet resolved; nothing is implemented.
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
  sees every candidate log event, fingerprints it, updates per-fingerprint sliding-window
  counts, and drives a per-fingerprint engage/continue/end state machine. The observer
  **never denies an event** — it returns exactly what it would have returned with the
  observer absent (chaining to any pre-existing filter, see "Adapter SPI"). It is an
  observer wearing a `Filter`'s clothes.
- **Fingerprinting** per §7.1: a cheap key computed on every event —
  `(logger name, level, throwable class, normalized message)` — allocation-free on the hot
  path; frame-based escalation (top 3–5 frames) is Decision #2.
- **Message normalization** — a conservative, content-agnostic transform so
  `"failed for order 4821"` and `"failed for order 9134"` fingerprint together (Decision #3).
- A per-fingerprint **windowed state machine**: `ENGAGED` when the count crosses the
  threshold within the window; `ENGAGED` (continuing) while it stays above; `ENDED` once it
  falls quiet (Decision #4). Each transition is recorded with a timestamp and the count so
  far.
- **First-occurrence retention** — the rendered message and stack trace of the first event
  of each tracked storm, size-capped (Decision #6).
- `--limit N` worst-first truncation; `--json`; `context` stamping when the result spans more
  than one context (mirrors `doctor`/`top`).
- Bounded memory (§16.7): a capped, LRU-evicted map of tracked fingerprints and a capped
  active/recently-ended storm history (Decision #8).
- JUL / JBoss LogManager adapter implementation only for this slice; Logback a fast-follow
  (Decision #9, mirrors `doctor` #5 and `top`).
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
fingerprint, only when a new storm is first recorded), and an `Instant`. The adapter's
`Filter` does the framework-specific extraction and nothing else; all fingerprinting,
normalization, windowing, and the state machine live in `core` (`StormDetector`) — the
interesting, testable logic (Decision #7).

**JUL / JBoss LogManager attach point (Decision #1).** `java.util.logging` and JBoss
LogManager both allow exactly **one** `Filter` per `Logger` and one per `Handler`.
Whichever seam is chosen, the installed filter captures any filter already present and
delegates to it for the actual allow/deny verdict, so the observer is transparent. The
frame-escalation path (Decision #2), if taken, calls `Throwable.getStackTrace()` only on a
*sampled* event after the cheap key already indicates a storm — never on the hot path.

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

Two capped structures, both LRU-evicted, both `-Dlogaperture.storm.*`-tunable with sane
defaults (Decision #8):

- **Fingerprint counters** — the sliding-window state for *every* fingerprint seen, most of
  which never storm. Capped (default in the low thousands); least-recently-updated evicted.
  An unbounded map keyed on `(logger, normalized message)` is exactly the memory-leak shape
  §16.7 names.
- **Storm history** — fingerprints that reached storm state (active + recently ended). Much
  smaller; capped (default ~100), ongoing storms never evicted ahead of ended ones, then
  least-recently-active ended storms drop first.

Retained first-occurrence text is individually size-capped (Decision #6) so one enormous
trace can't dominate the agent's footprint.

## Failure handling

- A framework/handler this can't observe (Logback and `none` this slice) contributes nothing
  — `storms()` returns empty, never a `logctl storms` failure. Same "skipped, not failed"
  discipline as `doctor`/`top`.
- If the observer's own bookkeeping throws, it is swallowed and the event passes through
  untouched — a detector bug must never break the application's logging or drop a line.
- `logctl storms` never exits non-zero for findings.

## Testing

- Unit — `core` (`StormDetectorTest`): a fingerprint crossing the threshold within the window
  transitions to `ONGOING` with the right `firstEventAt`/count; staying above keeps it
  `ONGOING` and advances `lastEventAt`; falling quiet transitions it to `ENDED` with
  `endedAt`; two events with the same exception type but different throw sites do / don't
  merge per Decision #2; normalization (Decision #3) merges numeric-variant messages and
  keeps genuinely different ones apart; the fingerprint-counter map evicts
  least-recently-updated at capacity; storm history keeps ongoing ahead of ended; a throwing
  observation is swallowed.
- Unit — `core` (`StormServiceTest`, `AggregateLevelControlTest`): `activeStorms()` sorts
  worst-first and `--limit` truncates (0/negative = all); a second arm call doesn't move
  `measurementStartedAt`; a multi-context merge stamps `context` on every row and
  re-sorts/truncates over the merged set; `trackedCount`/`ongoingCount` are pre-truncation.
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

### #1 — Observer attach point

Where the always-on observer hooks into JUL / JBoss LogManager.

- **A (leaning): a real gate-stage `Filter`**, installed on the root logger or per handler,
  capturing and delegating to any pre-existing filter for the actual verdict. This is the
  seam §7.1's M2 suppression grows directly out of, and it sees volume *before* any
  level/filter drops it — closer to the true event rate. Cost: one-`Filter`-only frameworks
  mean we must chain carefully and restore on teardown; a misbehaving app that swaps its
  filter out from under us silently un-installs the observer until the next re-arm tick.
- **B: piggyback on `top`'s render-stage formatter wrapper.** Already built, already
  re-armed, provably transparent (it only measures). Cost: it sits *after* the level check
  and any filter, so it never sees a storm that's being dropped by level — and a storm of
  `DEBUG` events under an `INFO` root is a real tight-loop-bug shape. Also conflates two
  concerns in one wrapper, and gives M2 nothing to build on.
- **C: both seams, detector fed from whichever fires.** Maximum fidelity, most surface area
  and the hardest to reason about for a first slice.

### #2 — Fingerprinting scope this slice

- **A: cheap key only** — `(logger, level, throwable class, normalized message)`. Simplest,
  fully allocation-free. Cost: two different bugs that both throw `IllegalStateException`
  with the same normalized message merge into one storm, and the "first occurrence" shown
  might be from the wrong one.
- **B (leaning): the full two-tier from §7.1** — cheap key always; once the cheap key crosses
  the threshold, take *one* sampled `getStackTrace()` and pin a top-3–5-frame sub-key, so
  distinct throw sites split into distinct storms. The expensive call happens once per storm,
  not per event. Cost: more state, a sampled slow path, and a rule for how many frames.
- **C: cheap key this slice, frame escalation as an immediate fast-follow** (own small spec),
  mirroring how `top` deferred per-throwable-type.

### #3 — Message normalization ruleset

The transform that decides which messages fingerprint together.

- **A (leaning): a small, conservative, hard-coded transform** — collapse runs of digits,
  hex, and UUIDs to a placeholder; collapse whitespace; trim; cap length. Contents seeded,
  not frozen by this spec (same status as `doctor`'s chatty-logger list). Predictable,
  cheap, good enough for the tight-loop-bug case.
- **B: also strip quoted string literals and bracketed segments** — merges more aggressively
  (`user 'alice'` ≈ `user 'bob'`). Higher merge rate, higher risk of merging genuinely
  different errors.
- **C: make it configurable from the start** (`-Dlogaperture.storm.normalize=` with a
  ruleset). More surface than a first slice needs; A can gain this later without a spec
  change.

### #4 — Thresholds, window, and the "ended" rule

- **Window model:** rolling window (per-fingerprint event timestamps, or a decaying rate)
  vs. fixed tumbling buckets. Leaning **rolling** — a decaying events-per-second estimate,
  no per-event timestamp list to bound.
- **Engage threshold:** default rate/count that declares a storm. §7.1's example is
  "4,118 in 5s". Leaning a conservative default like **≥ 500 matching events within 10 s**,
  `-Dlogaperture.storm.threshold=` / `.windowSeconds=`.
- **Ended rule:** quiet for Q seconds (no matching event), or rate decays below
  threshold/*k*. Leaning **quiet for 60 s** → `ENDED`, `-Dlogaperture.storm.quietSeconds=`.
- All defaults seeded, not frozen.

### #5 — Does the agent announce storms anywhere at detection time?

M1 modifies no behaviour, so nothing goes into the *application's* log. But:

- **A (leaning): queryable only.** `logctl storms` / JMX / `--json` are the only surfaces. A
  storm that starts and ends between two `logctl storms` runs is still in history (bounded).
  Cleanest "changes nothing" story. The §7.1 engage/continue/end lines arrive with
  suppression in M2.
- **B: also write engage/continue/end lines to the agent's own §4.5 diagnostic writer**
  (stderr or `-Dlogaperture.diagnostics.*`), never the app framework — default off, opt-in.
  Gives an operator a passive signal without polling. Cost: a second output path to spec and
  test, and "default off" limits its value.
- **C: B, default on.** A visible passive signal is arguably the point of a detector. Cost:
  closest to "modifies behaviour" of the three, even though it's the agent's own channel.

### #6 — First-occurrence retention

- **A (leaning): retain the rendered first message + stack trace, size-capped** (e.g. 8 KB),
  captured lazily the first time a fingerprint reaches storm state. This is §7.1's "first
  occurrence below" — the reason the feature is compelling. Cost: bounded but non-trivial
  memory per active storm; the render happens on (or just after) a logging thread.
- **B: retain metadata only** — `firstEventAt`, logger, exception class, normalized message —
  and let the user grep the log file for the timestamp. Near-zero memory. Cost: the headline
  UX ("full trace in front of you") is gone.
- **C: retain a truncated trace** — first N frames + cause summary, ~1 KB. Middle ground;
  needs a frame-trimming util this slice would otherwise not need (it's an M2 render-stage
  concern).

### #7 — Where the detector logic lives

- **A (leaning): `core` owns it.** Adapter `Filter` reduces each event to a
  `StormObservation` and calls a `core` `StormObserver`; `StormDetector` in `core` does
  fingerprinting, normalization, windowing, the state machine, and bounded state. The adapter
  is a dumb extractor. Maximum unit-test coverage without a framework. Matches
  `LoggingAdapter`'s "adapter is a dumb getter/setter, core owns policy" class doc.
- **B: adapter owns it**, like `TopCounters` lives in `logaperture-adapter-jul`. `core` holds
  only the clock. Consistent with how `top` split it. Cost: the genuinely interesting logic
  (windowing, the state machine, normalization) becomes adapter-private and needs the adapter
  (and eventually every adapter) to re-implement or share it.

### #8 — Bounded state specifics

- Fingerprint-counter cap: default and eviction (leaning **~4,000**, least-recently-updated).
- Storm-history cap: default and eviction (leaning **~100**, ongoing never evicted before
  ended, then least-recently-active).
- Property names: `-Dlogaperture.storm.maxTrackedFingerprints=`,
  `-Dlogaperture.storm.maxHistory=`, `-Dlogaperture.storm.firstOccurrenceBytes=`.
- Leaning: resolve as "same pattern as `TopCounters`", numbers seeded not frozen.

### #9 — Adapter coverage this slice

- **A (leaning): JUL / JBoss LogManager only**, Logback a fast-follow. Mirrors `doctor` #5
  and `top` — WildFly is the primary motivating environment (§16).
- **B: JUL + Logback together.** Logback's `TurboFilter` is context-wide and composes
  cleanly (no single-filter constraint), so it may be *less* work than the JUL seam. Ship
  both, match `top`'s eventual end state now.

### #10 — Command surface and `doctor` cross-reference

- **A (leaning): standalone `logctl storms`**, and a one-line pointer from `logctl doctor`
  when storms are active ("N active log storms — run `logctl storms`"). `doctor` is
  config diagnosis, storms are a runtime incident — different commands — but a `doctor` run
  during an incident should not stay silent about one.
- **B: standalone `logctl storms` only**, no `doctor` coupling. Less cross-module wiring;
  `doctor`'s "N checks run" summary stays purely about configuration.

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
- No capability beyond `VIEW` is required.
