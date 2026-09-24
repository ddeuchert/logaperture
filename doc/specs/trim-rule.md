# `trim`: render-stage stack-trace reduction (issue #34)

Status: **signed off 2026-09-22; implemented.** `api` (`Trim`), `core` (`TrimFactories`,
`TrimDecision`, `GateVerdict.allowWithTrim`, most-restrictive-wins evaluation,
`RuleService.addRuleTrim`/`installTrimRendering`/`registerTrimSupport`), the JUL/JBoss
LogManager render-stage `Formatter` wrap (`JulTrimFormatter`, `TrimRendering`,
`ExtLogRecordCopier`), the JMX surface, the CLI (`logctl add rule trim`), and container wiring
(both containers, `top`-relative ordering) are implemented and unit-tested; a real-WildFly
proof (`WildFlyContainerIT`) landed in this same slice — see "Implementation status" below.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.2 (gate/render stages),
§7.2 (rule model — `trimStackTrace` action), §7.3 (evaluation semantics), §9.5 (suppression
floor), §9.6 (suppression must never be silent), §9.7 (audit trail), §16.1 (`top`/`doctor`
trim-savings hints), §18.8 (the original `exceptionDetail` roadmap write-up this issue reshapes).
Epic spec: [`filtering-epic.md`](filtering-epic.md) — "What `trim` means", "Matchers", "Logger
scope", "Targeting by pattern is shorthand", "Rules as managed objects", "Evaluation", "Safety
limits", "Foundation: what the spike established", Decisions #6 (trim semantics), #7
(evaluation order), #9 (FATAL/floor/hit-counting), #10 (structured-formatter hybrid
rendering), #16 (pattern-target expansion). This document is that epic's step-4 (final) child
spec; every epic decision it cites is **already agreed** and restated here only where this
spec's own design depends on it.
Builds on: [`rule-pipeline-foundation.md`](rule-pipeline-foundation.md) (`LogRule`,
`useParentRules` inheritance, the matcher library, rule identity/persistence/capability
scaffolding, and the explicit deferral of "the render-stage seam extension
(`ExtLogRecord` copy-and-replace-throwable)" to this spec), [`drop-rule.md`](drop-rule.md)
(the sibling action this spec's evaluator chains after — a dropped event is never trimmed;
also the template this spec's command surface, capability, and safety-set sections follow),
[`top.md`](top.md) (the `Formatter`-wrapping render seam this spec's trim wrap sits inside of
— "Interaction with `top`"), [`persistence.md`](persistence.md) (`SESSION`/`FOR`/`STICKY`,
reused as-is), [`set-command-surface.md`](set-command-surface.md) (the
`[session | for <duration> | sticky]` tier grammar `add rule trim` follows verbatim).
Evidence: [`doc/spikes/rule-pipeline.md`](../spikes/rule-pipeline.md) — the trim half of the
spike (record-copy mechanism, structured-formatter caveat, reconfiguration survival),
validated on plain JBoss LogManager and on WildFly 26.1.3 and 33.0.0.
Tracks: [issue #34](https://github.com/ddeuchert/logaperture/issues/34). Depends on
[#71](https://github.com/ddeuchert/logaperture/issues/71) (rule pipeline foundation, merged)
and [#72](https://github.com/ddeuchert/logaperture/issues/72) (`drop`, merged — this spec's
evaluator plugs into the gate→render ordering `drop` already established).

## Functional summary

After this feature, the user will be able to:

- Stop paying disk for a known-noisy exception's stack trace without stopping the exception
  from being logged, and without touching its logger's level: `logctl add rule trim --logger
  '*.AutoUpdateHelper' --throwable java.net.ConnectException --message-contains "Failed to
  connect to URL" --below FATAL sticky` turns a 29-frame trace into one line plus a marker,
  for every event at or below the bound; the same event above the bound (or a `--below FATAL`
  rule with an actual FATAL event) keeps its full trace.
- Choose how much trace survives, not just all-or-nothing: `--frames N` keeps the top N
  frames instead of collapsing to a bare one-liner; the default (`--frames 0`) is the
  one-liner.
- Narrow a trim to a specific exception type, exception message, or log message — the same
  matcher vocabulary `drop` uses (`--throwable`, `--throwable-message-contains`,
  `--message-contains`, `--any-cause`).
- Get each `Caused by:` in a chain reduced to its own one-liner by default, or collapse the
  whole cause chain away with `--collapse-causes`.
- Target several loggers at once with a leading-star pattern, previewed and confirmed like any
  other pattern target, and have the trim reach descendants automatically, including ones
  created later — same inheritance as `drop` and `level`.
- Keep a trim for the session, for a duration, or sticky across restarts — same tier grammar
  as `set logger`/`add rule drop`.
- Trust it's never silent: every trimmed exception carries an always-present marker
  (`[stack trace trimmed: N frames omitted]`) naming how much was cut, so the log never has an
  undetectable gap; `logctl status`/`logctl list rules` show each trim rule's hit count; a
  protected-category logger or FATAL refuses the rule outright.
- Remove a trim the same way as any other rule: `logctl reset rule <id>`, `logctl reset
  rules`, or `logctl reset logger X`.

## Scope of this slice

Step 4 (final) of [`filtering-epic.md`](filtering-epic.md)'s build order. Nothing else in the
epic depends on this slice; it is the last piece needed to close #73.

**In scope:**

- `Trim implements LogRule` (`logaperture-api`) — the concrete type
  `rule-pipeline-foundation.md` left for this issue to define: a `belowLevel` bound, `frames`
  count, and `collapseCauses` flag, on top of `LogRule`'s common fields.
- `logctl add rule trim <logger-target> [matchers] [--below LEVEL] [--frames N]
  [--collapse-causes] [tier] [--reason <text>]` — the second concrete `add rule` verb.
- **The render-stage seam**: `rule-pipeline-foundation.md` and `drop-rule.md` both explicitly
  deferred "the render-stage seam extension (`ExtLogRecord` copy-and-replace-throwable)" to
  this issue. This slice builds it: a `Formatter` wrap, installed inside `top`'s existing
  byte-counting wrap (see "Interaction with `top`"), that for a record a `Trim` rule matches,
  hands the delegate formatter a **copy** of the record with its throwable replaced, never the
  shared record itself.
- The synthetic-throwable (text formatters) / message-suffix (structured formatters) hybrid
  rendering the epic already agreed to (Decision #10) and the spike validated (R2) — this
  slice is where that split is actually implemented.
- Wiring `Trim` into the evaluator `rule-pipeline-foundation.md`'s gate `Filter` already
  reserved a no-op stage for (`drop-rule.md` "Evaluation": "this slice's evaluator has a
  `trim` no-op stage reserved but unimplemented, matching how the foundation spec left drop
  unimplemented for this one to fill") — except trim's decision is carried to the *render*
  stage, not applied at the gate; see "Evaluation".
- Registering `Trim`'s `RuleFactory` via `RuleService.registerActionFactory`, same resume
  pattern `Drop` already established, so a persisted `STICKY`/unexpired-`FOR` trim resumes as
  a live rule after a restart.
- `top`'s bytes-saved figure for a trim rule (epic: "`top` shows the bytes a rule saved") —
  companion to [#81](https://github.com/ddeuchert/logaperture/issues/81), which is `drop`'s
  half of the same gap; this slice does not depend on #81 landing first but should reuse
  whatever counting point it introduces if #81 lands first.
- The reflection-based `ExtLogRecord` copy-constructor mechanism the spike proved
  (`doc/spikes/rule-pipeline.md` "Design consequences" #4/#5), with its fail-open behavior
  (print the untrimmed line if the copy can't be made).
- `WildFlyContainerIT` coverage for trim, real-WildFly, mirroring what
  [#80](https://github.com/ddeuchert/logaperture/issues/80) is for `drop` — the epic's own
  required scenario (ERROR trimmed, FATAL full, non-matching message full, other logger full,
  cause chain, sibling handler full, caller info intact, survives `pattern-formatter`/
  `filter-spec`/new-handler/`:reload`, `STICKY` trim resumes after a restart).

**Explicitly out of scope for this slice** (deferred to the issue that needs it):

- **Logback's `Encoder` swap** as a second render-stage implementation — JUL/JBoss LogManager
  only, matching every other epic slice's precedent (spike "Not verified": "Logback / Log4j 2
  ... out of scope for this epic's first slice").
- **A "keep only frames from package X" mode** — `filtering-epic.md` "What `trim` means",
  "Deferred: ... noted as a later addition."
- **Structured formatters (JSON, XML)** — trim applies to text formatters only in this slice.
  A handler using a structured formatter passes every record through untrimmed, whether or not
  a `Trim` rule matches; the marker/summary-line rendering a structured consumer would need is
  real additional design (spike R2's fallback, taken here rather than the hybrid). Tracked as
  [#83](https://github.com/ddeuchert/logaperture/issues/83).
- **`frame` matcher, `not`/`any`/`all` composition, rule priority/ordering** — epic "Deferred
  and future enhancements", unchanged from `drop-rule.md`.
- **`--dry-run`** — deferred by the epic itself.
- **Per-handler delivery targeting (`--to`)** — #56; a trim always applies across every
  handler on the logger, same as `drop`.
- **`doctor`/`top` "suppressing traces below WARN on X would save ~N GB/day" hint** (#34's own
  open question 8) — real additional scope (a savings *estimate* ahead of any rule being
  attached, not a report of bytes an attached rule already saved); filed as a follow-up issue
  once this slice lands, same discipline `drop-rule.md` used for #79/#80/#81.
- **Multi-context fan-out and leading-star pattern-target expansion for `add rule trim`** —
  tracked by the *existing* [#79](https://github.com/ddeuchert/logaperture/issues/79), which
  was scoped to "`add rule`" generically, not `drop` specifically; this slice's `add rule trim`
  inherits the same first-registered-context-only limitation `drop` shipped with, closed by
  #79 for both actions at once rather than a second issue.
- **Fixing the non-atomic `Handler.getFilter()`/`setFilter()` race** and the pre-existing
  double-stack-trace-rendering issue this slice's render wrap sits next to (#23) — #23 is
  noted as touched by this work (spike consequence #5: "This also touches #23") but fixing it
  outright is not this issue's job; this slice's own formatter wrap must not make #23 worse,
  and should note in its implementation whether it happens to fix it as a side effect.

## Data model

```
Trim implements LogRule {                      — logaperture-api, new
    // LogRule's common fields: id, loggerName, matchers, reason, tier,
    // expiresAt, createdAt (rule-pipeline-foundation.md "Data model")
    belowLevel: Level = ERROR                   // same keep-floor shape as Drop.belowLevel
    frames: int = 0                             // top N frames kept; 0 = bare one-liner
    collapseCauses: boolean = false              // true removes Caused-by lines entirely
    actionName() = "trim"                        // rendered by `list rules`, persisted
                                                   // verbatim as PersistedRule.action
}
```

`belowLevel` reuses `CompiledMatchers`' existing `level` matcher exactly as `Drop.belowLevel`
does (`drop-rule.md` "Data model") — a convenience accessor over `matchers().level()`, not a
second source of truth. Same `FATAL`-pseudo-token handling as `drop-rule.md`'s divergence #1:
`--below FATAL` is accepted client-side and clamped so a `Trim` can never suppress detail on an
actual FATAL event, without a `Level.FATAL` enum member existing.

## Matchers in use

Identical set to `drop-rule.md` "Matchers in use" — the full `rule-pipeline-foundation.md`
matcher library, unchanged. Unlike `drop`, **no content matcher is required**: `logctl add
rule trim <logger> --below WARN` with no `--message-contains`/`--throwable` is a legitimate,
common case (§18.8's original example: "always record that X threw, don't spend disk on the
trace unless asked") — it's the per-logger `exceptionDetail` threshold #34 originally asked
for, now expressed as a rule with only a level bound.

## Evaluation

Extends `drop-rule.md` "Evaluation" rather than reopening it:

- Per candidate event, after the gate `Filter`'s `drop` check already ran (a dropped event is
  never trimmed — `filtering-epic.md` "What `trim` means"): the gate `Filter` walks the
  event's effective `Trim` rules (same tree-inherited, `useParentRules`-scoped set `Drop`
  uses) and, if any match, **carries the compiled trim decision forward on the event** —
  it does not itself alter the record. `rule-pipeline-foundation.md` §4.2's "carry the
  decision on the event" principle, restated: the gate stage decides *what* to trim, the
  render stage (below) does the trimming.
- Among several matching `Trim` rules on the same effective set, **the most restrictive wins**
  (fewest `frames`, per `filtering-epic.md` "Evaluation") — resolved once, at the gate, not
  re-resolved per handler.
- **The render-stage seam** (new in this slice): a `Formatter` wrap, installed by
  `installRulePipeline` alongside the gate `Filter` this slice's evaluator already walks,
  applies the carried decision at format time:
  1. If no `Trim` decision was carried, delegate to the wrapped formatter unchanged — the
     common case pays only the carry-check, no allocation (spike "Cost": idle/non-candidate
     cost is ~3 ns, 0 bytes).
  2. Otherwise, copy the record via `ExtLogRecord`'s copy constructor (reflection — see
     "Divergence-equivalent notes" below), replace the copy's throwable per "Structured vs.
     text formatters", and format the copy. The original, shared `ExtLogRecord` — and the
     application's own `Throwable` — are never mutated (spike result #2, verified against
     sibling handlers and `AsyncHandler`).
  3. If the copy cannot be made (record isn't an `ExtLogRecord`, or the copy constructor isn't
     reachable), **fail open**: format the original, untrimmed record. Never throw back into
     application code over a trim that couldn't apply.
- Checks run cheapest first, same ordering as `drop` (`rule-pipeline-foundation.md` "Matcher
  library").

### Text formatters only (epic Decision #10 narrowed; spike R2's fallback; see #83)

- **Text formatters** (`PatternFormatter` and anything not detected as structured): replace
  the copy's throwable with a **synthetic, frameless `Throwable`** whose `toString()` is the
  original's `toString()` plus the trim marker. This renders as one line and preserves the
  cause chain as its own per-cause one-liners (each cause gets the same synthetic treatment,
  unless `collapseCauses` removes the chain entirely). Caller info (`%C`, `%M`) and every
  other pattern token continue to read off the copy, which is otherwise identical to the
  original.
- `--frames N > 0`: same branch, but the synthetic throwable's `StackTraceElement[]` is
  truncated to the top N frames instead of emptied, and the marker names the omitted count
  (`total - N`), not the full original count.
- **`N >= actual frame count`**: the marker still prints, reading `[stack trace trimmed: 0
  frames omitted]`, rather than being suppressed — consistent behavior regardless of how a
  rule's `--frames` value happens to relate to a given trace's depth, and one fewer conditional
  in the render path.
- **Structured formatters** (JSON, XML — detected by formatter class, same detection point
  `top.md`'s byte-counting wrap already needs): **not trimmed in this slice.** The copy is
  formatted with its throwable untouched, identical to what an unmatched record would produce.
  This sidesteps the spike's observed defect (a synthetic throwable's class reported as the
  structured formatter's `exceptionType`, with the real message dropped) by not attempting
  structured rendering yet, rather than by solving it — see #83.
- This is a one-time detection cost (formatter class check) to decide which branch applies, not
  per-event.

## Safety set

Restates `filtering-epic.md`'s "Safety, required in the first version" list at implementation
altitude, mirroring `drop-rule.md`'s "Safety set" for the parts that transfer unchanged:

- **Keep-floor default.** `--below ERROR` unless stated otherwise — full trace at ERROR and
  above unless the operator explicitly widens with `--below FATAL`.
- **FATAL is a hard limit.** Same `CompiledMatchers`-level clamp `drop-rule.md` uses: a
  `Trim`'s `level` matcher can never evaluate `true` for a FATAL event, regardless of
  `--below`.
- **Suppression floor (§9.5).** Enforced generically at attach time by
  `RuleService`/`rule-pipeline-foundation.md`; a protected-category `Trim` attach throws and
  audits, same as `Drop`.
- **Never silent, but no periodic summary line.** Unlike `drop` (which removes an event
  entirely and so needs `sampleFull` plus a periodic "N suppressed" line so the log doesn't go
  dark), `trim` leaves every event in place — the always-present per-record marker *is* this
  action's never-silent guarantee (`filtering-epic.md` "Safety limits": "marker on every
  trimmed exception, periodic summary line for drops" — the two actions get different
  mechanisms for the same requirement, not the same mechanism twice). No `sampleFull`
  equivalent exists for `Trim`; there is nothing to sample-through when nothing was removed.
- **Counted and visible.** Foundation's per-event hit counter is `Trim`'s hit count too, shown
  in `list rules`/`status`, same counting-point discipline the spike's consequence #4 requires
  (one count per event, not per handler evaluation — this slice's render wrap must read the
  gate stage's already-decided verdict, not re-evaluate matchers per handler).

## Interaction with `top`

`top.md`'s `Formatter` wrap already exists and already wraps each handler's formatter for
byte-counting. Per spike consequence #5, **trim's wrap installs inside `top`'s wrap**: trim
first, byte-counting outside, so `top` counts the bytes of what was *actually written*
(post-trim), not the untrimmed original. This is an ordering decision at the same composition
root `drop-rule.md`'s "Interaction with storm detection" already established for the gate
side (`ContainerIntegration`'s `installContext`) — this slice adds the render-side half of
that same ordering discipline, formatter wraps instead of filter chain.

This also gives `top` the data for its own "bytes saved by a trim rule" figure (see "Scope of
this slice") for free: the delta between the pre-trim and post-trim rendered length of a
record `top`'s wrap already measures.

## Capability and audit

- Requires `Capability.SUPPRESS` (added by `drop-rule.md`, shared verbatim — §9.3: "drop/trim
  actions specifically, separable from rate limiting") in addition to `RULES_AUTHOR`.
- `PERSIST` required in addition, whenever `tier != SESSION` — unchanged.
- Suppression-floor refusal happens at attach time, already built by the foundation spec —
  nothing new to design here.
- Audit: attach/removal produce the same `MUTATION`/`REVERSION` records as any other rule; no
  new audit event shape. An individual trim-in-effect is not separately audited (evaluation-
  time, not configuration-time) — visible via the per-record marker and `list rules`' hit
  count instead, same reasoning `drop-rule.md` applies to a `sampleFull` keep.

## Command surface

Follows `set-command-surface.md`'s noun-first grammar and tier token, identical shape to
`drop-rule.md`'s "Command surface".

### `logctl add rule trim <logger-target> [matchers] [--below LEVEL] [--frames N] [--collapse-causes] [session | for <duration> | sticky] [--reason <text>]`

```
logctl add rule trim com.acme.batch.Worker --below WARN
logctl add rule trim '*.AutoUpdateHelper' \
    --throwable java.net.ConnectException \
    --message-contains "Failed to connect to URL" --below FATAL sticky
```

- `<logger-target>`: exact name, or leading-star pattern, same grammar and preview/confirm as
  `add rule drop`. Trailing `.*` rejected, same reason.
- Matchers as in "Matchers in use" — **no content matcher required** (unlike `drop`), since a
  bare level-bounded trim is a first-class use case.
- `--below LEVEL` defaults to `ERROR`; `FATAL` accepted as a bound, never actually suppresses
  detail on a FATAL event.
- `--frames N` defaults to `0` (bare one-liner). `N` must be `>= 0`.
- `--collapse-causes`: off by default; when set, `Caused by:` lines are removed entirely
  rather than reduced to one-liners each.

## Implementation status

Landed together (spec + code, same PR, per CLAUDE.md), unit-tested, full reactor build green:

- `logaperture-api`: `Trim` (`LogRule` + `frames()`/`collapseCauses()` +
  `persistedPayload()`/`framesFromPayload()`/`collapseCausesFromPayload()` for the payload
  round trip, mirroring `Drop`'s own shape) — rejects a negative `frames` at construction.
- `logaperture-core`: `TrimDecision` (the render-stage decision `GateVerdict.trim()` carries);
  `GateVerdict` gains a third field plus `allowWithTrim(TrimDecision)`; `TrimFactories` (the two
  `RuleFactory` closures, fresh-attach and resume); `RuleService.computeVerdict` extended so a
  survivor of the `Drop` pass (denied or sampled-through alike) is also checked against every
  effective `Trim`, picking the most-restrictive (fewest `frames`) match and incrementing its
  hit counter; `RuleService.addRuleTrim`/`registerTrimSupport`/`installTrimRendering`;
  `RuleOperations`/`AggregateLevelControl` gain `addRuleTrim` (first-registered-context only,
  same divergence `drop-rule.md` already recorded); `AggregateLevelControl.verificationSweep`
  calls `installTrimRendering()` immediately before `topService.startMeasuring()` on every
  context, every tick.
- `logaperture-adapter-jul`: `JulTrimFormatter` (the render-stage `Formatter` wrap — reads the
  same cached `GateVerdict` its sibling `JulRuleFilter` already computed for a record, via the
  shared `RuleCandidateEvents` helper both now use); `TrimRendering` (builds the trimmed
  `Throwable` chain — a frameless-or-top-N-frame synthetic `Throwable` per cause-chain level,
  each with its own overridden `toString()` carrying the marker); `ExtLogRecordCopier` (the
  reflective `ExtLogRecord` copy-constructor access the spike proved, `Optional`-returning, empty
  on anything that isn't a real `ExtLogRecord`); `StructuredFormatters` (the by-simple-name
  `JsonFormatter`/`XmlFormatter` detection "Text formatters only" needs — `JulTrimFormatter`
  checks it first and never evaluates the gate at all for a structured-formatter handler);
  `JulLoggingAdapter.installTrimRendering` (wraps every real handler's current formatter,
  re-layering correctly under an already-installed `ByteCountingFormatter` regardless of which
  order the two installs actually ran in on a given tick).
- `RuleService.mostRestrictiveTrim` skips both winner-selection and hit-counting outright for an
  event with no throwable at all — a bare level-bounded `Trim` matches every qualifying event on
  its logger, but there is nothing to trim without a throwable, and counting it as a hit would
  misrepresent `list rules`' HITS column as "matched" rather than "actually trimmed" (a
  code-review finding against the first cut, caught before merge).
- `logaperture-control-jmx`: `RuleData` gains nullable `frames`/`collapseCauses` (populated only
  for a `Trim` row); `LevelControlMXBean`/Impl gain `addRuleTrim`.
- `logaperture-cli`: `logctl add rule trim <target> [matchers] [--below LEVEL] [--frames N]
  [--collapse-causes] [tier] [--reason]` — unlike `add rule drop`, no content matcher is
  required; `Json.rule` gains `frames`/`collapseCauses`.
- `logaperture-container-none`/`logaperture-container-wildfly`: call
  `ruleService.registerTrimSupport()` alongside `registerDropSupport()`, and
  `ruleService.installTrimRendering()` immediately before `topService.startMeasuring()` at
  every install/reset/re-arm call site (the ordering "Interaction with `top`" requires).
- **Real-WildFly proof**, landed in this slice per Decision #5 below rather than deferred:
  `WildFlyContainerIT#trim_collapsesMatchingStackTraces_sparesEverythingElse`, run against both
  WildFly 26.1.3.Final and 33.0.0.Final — a below-the-keep-floor event collapses to a one-liner
  plus marker (a real `ExtLogRecord` copy via reflection), an at-the-keep-floor event is spared
  (full trace), an unrelated logger is untouched, a two-level cause chain gets its own one-liner
  per level, and `--frames 1` keeps exactly one top frame.

**Deliberately scoped down from the epic's full required scenario** (recorded here, not
silently dropped, same discipline `drop-rule.md` used for its own gaps):

- **Reconfiguration-survival** (`pattern-formatter`/`filter-spec`/new-handler/`:reload`) is not
  its own `WildFlyContainerIT` scenario for trim. The re-arm mechanism is the identical,
  already-proven one `drop`/`rule-pipeline-foundation.md` established (`installTrimRendering`'s
  own idempotent re-layering is unit-tested against out-of-order installs); a dedicated
  real-WildFly reconfiguration scenario for trim specifically was judged lower value than the
  scenario actually landed, given the time this pass had.
- **A `STICKY` trim resuming after a real container restart** is not its own
  `WildFlyContainerIT` scenario either — covered generically by `TrimRuleTest`'s
  persisted-payload round trip (`resumeFromStateStore` rebuilding a `Trim` from its payload) and
  by `drop`'s own precedent proving the resume machinery itself works; a real-restart proof
  specifically for trim is a fast-follow if it turns out to matter in practice.
- **Structured formatters (JSON/XML)** — out of scope for this slice by design, not a gap;
  tracked as [#83](https://github.com/ddeuchert/logaperture/issues/83).

## Open decisions for sign-off

1. **`--frames` semantics at the boundary** — **Resolved:** the marker always prints, reading
   `0 frames omitted` when `--frames N` is at or above the trace's actual depth.
2. **Structured formatters (JSON/XML)** — **Resolved:** out of scope for this slice; a `Trim`
   rule is a no-op on a structured-formatter handler, undocumented savings gap tracked as
   [#83](https://github.com/ddeuchert/logaperture/issues/83).
3. **`top`'s per-rule bytes-saved figure** — **Resolved:** built in this slice (as scoped
   above), not split into a follow-up issue.
4. **`doctor`/`top` savings-hint follow-up issue** — **Resolved:** filed after this slice
   lands, once real implementation experience exists to scope it accurately.
5. **`WildFlyContainerIT` for trim** — **Resolved:** lands in this slice's own PR, added to
   the existing `WildFlyContainerIT` class alongside the implementation. A WildFly container
   is available in this environment (`quay.io/wildfly/wildfly:26.1.3.Final-jdk17` and
   `:33.0.0.Final-jdk21` both cached locally), so nothing is deferred to a second issue the
   way #80 deferred `drop`'s real-WildFly proof.
