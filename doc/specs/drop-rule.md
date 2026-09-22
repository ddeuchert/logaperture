# `drop`: content-based event gate (issue #72)

Status: **signed off 2026-09-22; implemented** on `feature/72-drop-rule` — `api` (`Drop`,
`SampleFullPolicy`), `core` (matching, gate evaluation, hit counting, `sampleFull`, the
periodic summary line, `Capability.SUPPRESS`, `add rule drop`'s `AggregateLevelControl`/
`RuleService` wiring, persisted-payload round trip), the JUL/JBoss LogManager gate `Filter`
(now actually denying), the JMX surface, the CLI (`logctl add rule drop`), and container
wiring (both containers) are implemented and unit-tested — see "Implementation status" below.
The real-WildFly cross-process proof (`WildFlyContainerIT`) is **not** part of this pass; it
remains this issue's open exit criterion.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.2 (gate/render stages),
§7.2 (rule model — `drop` action), §7.3 (evaluation semantics), §7.4 (the keep-one-in-N escape
hatch), §9.2 (restrictions compose), §9.3 (capabilities — `rules.author`, `suppress`), §9.5
(suppression floor), §9.6 (suppression must never be silent), §9.7 (audit trail).
Epic spec: [`filtering-epic.md`](filtering-epic.md) — "What `drop` means", "Matchers", "Logger
scope", "Targeting by pattern is shorthand", "Rules as managed objects", "Evaluation", "Safety
limits", Decisions #5 (drop semantics), #7 (evaluation order), #9 (FATAL/floor/hit-counting),
#16 (pattern-target expansion). This document is that epic's step-3 child spec; every epic
decision it cites is **already agreed** and restated here only where this spec's own design
depends on it.
Builds on: [`rule-pipeline-foundation.md`](rule-pipeline-foundation.md) (`LogRule`,
`useParentRules` inheritance, the matcher library, the gate-stage `Filter` seam
(`installRulePipeline`/`RulePlanSource`), rule identity/persistence/capability scaffolding —
this spec plugs a concrete action into machinery that spec already built and shipped),
[`storm-detection.md`](storm-detection.md) (the sibling gate-stage `Filter`
(`installStormDetection`) this spec's filter chains alongside; its fingerprint/counter shape
is the model for `drop`'s own hit-and-sample bookkeeping), [`top.md`](top.md) (the
byte-counting wrap a dropped event's bytes-saved figure is derived against),
[`persistence.md`](persistence.md) (`SESSION`/`FOR`/`STICKY`, reused as-is),
[`set-command-surface.md`](set-command-surface.md) (the `[session | for <duration> | sticky]`
tier grammar `add rule drop` follows verbatim).
Evidence: [`doc/spikes/rule-pipeline.md`](../spikes/rule-pipeline.md) — validated on plain
JBoss LogManager and on WildFly 26.1.3 and 33.0.0.
Tracks: [issue #72](https://github.com/ddeuchert/logaperture/issues/72). Depends on
[#71](https://github.com/ddeuchert/logaperture/issues/71) (rule pipeline foundation, merged).

## Functional summary

After this feature, the user will be able to:

- Stop a known-noisy, repeating message from being logged at all, without touching its
  logger's level: `logctl add rule drop --logger com.acme.batch.Worker --message-contains
  "This happens a lot" --below ERROR`. Everything else that logger emits, and that same
  message at ERROR or above, keeps logging exactly as before.
- Narrow a drop to a specific exception type or exception message instead of (or as well as)
  the log message: `--throwable java.net.ConnectException`,
  `--throwable-message-contains "Failed to connect"`, `--any-cause` to match inside a cause
  chain rather than only the top exception.
- Target several loggers at once with a leading-star pattern
  (`--logger '*.AutoUpdateHelper'`), previewed and confirmed like any other pattern target,
  producing one independent drop rule per currently-known match.
- Attach a drop to a logger and have it reach that logger's descendants automatically,
  including ones created later — the same way a level does — unless a descendant in between
  opts out with `useParentRules false`.
- Keep a drop for the session, for a duration, or sticky across restarts — same tier grammar
  as `set logger`.
- Trust it's never silent: `logctl status`/`logctl list rules` show each drop rule's hit
  count; the log itself gets a periodic summary line naming what's being dropped and how much;
  the first matching event, and then one every so often, is let through untouched so the full
  diagnostic is never more than a few minutes stale; a protected-category logger or FATAL
  refuses the rule outright.
- Remove a drop the same way as any other rule: `logctl reset rule <id>`, `logctl reset
  rules`, or `logctl reset logger X` (which also removes every rule attached directly to `X`).

## Scope of this slice

Step 3 of [`filtering-epic.md`](filtering-epic.md)'s four-step build order. `trim` (#34,
step 4) is a separate spec and PR; this slice adds nothing to the render stage.

**In scope:**

- `Drop implements LogRule` (`logaperture-api`) — the concrete type `rule-pipeline-foundation.md`
  left for this issue to define: a `belowLevel` keep-floor bound and a `sampleFull` policy, on
  top of `LogRule`'s common fields.
- `logctl add rule drop <logger-target> [matchers] [--below LEVEL] [--sample-full ...] [tier]
  [--reason <text>]` — the first concrete `add rule` verb; `rule-pipeline-foundation.md`
  deliberately shipped none.
- Wiring `Drop` into the gate `Filter` `rule-pipeline-foundation.md` already installs
  (`installRulePipeline`): the plan this slice's rules compile into now actually denies an
  event, where the foundation's own plan never did.
- `Capability.SUPPRESS`, added to `Capability` and required (in addition to `RULES_AUTHOR`) to
  attach a `Drop` — §9.3's "drop/trim actions specifically, separable from rate limiting",
  deferred from the foundation spec to here (its Decision #2).
- The periodic drop-summary line (§9.6) — foundation's hit counter is the data; this slice
  writes the line, decides its cadence and where it's emitted.
- `sampleFull` (§7.4): the first matching event, then a steady trickle of full events, is kept
  rather than dropped, so the log never goes fully dark on a logger with an active drop.
- Resolving the "install order decides which filter is outer" question
  `rule-pipeline-foundation.md` flagged for this spec to design against — see "Interaction
  with storm detection".
- Registering `Drop`'s `RuleFactory` via `RuleService.registerActionFactory` so a persisted
  `STICKY`/unexpired-`FOR` drop resumes as a live, denying rule again after a restart — the
  foundation spec left every persisted row "not resumed" for exactly this reason.
- `top`'s bytes-saved figure for a drop rule (epic: "`top` shows the bytes a rule saved").
- The first real cross-process proof of the gate seam — `WildFlyContainerIT` coverage the
  foundation spec explicitly deferred to this issue.

**Explicitly out of scope for this slice** (deferred to the issue that needs it):

- **`trim`** (#34) and the render-stage seam entirely — this spec's `Filter` only ever denies
  or allows, never mutates a record that survives.
- **`frame` matcher, `not`/`any`/`all` composition, rule priority/ordering** —
  `filtering-epic.md` "Deferred and future enhancements".
- **`--dry-run`** — deferred by the epic itself.
- **Per-handler delivery targeting (`--to`)** — #56; a drop always applies across every
  handler on the logger.
- **Automatic storm collapse producing drop rules** (#27) — a later, separate producer of the
  same `Drop` type this slice defines; not this slice's job to wire up.
- **Fixing the non-atomic `Handler.getFilter()`/`setFilter()` race** `rule-pipeline-foundation.md`
  documented as pre-existing — out of this issue's scope, same as it was out of the
  foundation's.

## Implementation status

Landed on `feature/72-drop-rule`, unit-tested, full reactor build green:

- `logaperture-api`: `Drop` (`LogRule` + `sampleFull()` + `persistedPayload()`/
  `sampleFullFromPayload()` for the Decision #4 payload bag), `SampleFullPolicy`.
- `logaperture-core`: `Capability.SUPPRESS`; `RuleCandidateEvent`/`GateVerdict`/`RuleGate`
  (the new adapter-facing evaluation SPI, retiring `RulePlanSource` from that role — see
  "Divergence" below); `RuleMatching` (the matcher-evaluation logic `rule-pipeline-foundation.md`
  never needed to write); `DropFactories` (the two `RuleFactory` closures — fresh-attach and
  resume); `RuleService` gains `attach(..., Capability additionalRequired)`,
  `addRuleDrop`/`registerDropSupport`, the per-record-identity decision cache, hit counters,
  `sampleFull` tracking, and `reportDueDropSummaries`; `RuleOperations`/`AggregateLevelControl`
  gain `addRuleDrop` (first-registered-context only — see "Divergence") and
  `reportDueDropSummaries`; `RuleView` gains `hitCount`; `StateFileFormat` bumps to schema 8
  (`payload:` per rule row).
- `logaperture-adapter-jul`: `JulRuleFilter` rewritten to evaluate a `RuleGate` and actually
  deny; `JulLoggingAdapter.installRulePipeline` takes a `RuleGate`, not a `RulePlanSource`.
- `logaperture-control-jmx`: `RuleData` gains `hitCount`; `LevelControlMXBean`/Impl gain
  `addRuleDrop` (exact-name target only).
- `logaperture-cli`: `logctl add rule drop <target> [matchers] [--below LEVEL] [--sample-full
  <duration> | --no-sample-full] [tier] [--reason]`; `logctl list rules` gains a HITS column.
- `logaperture-container-none`/`logaperture-container-wildfly`: call
  `ruleService.registerDropSupport()` before resume, and `aggregate.reportDueDropSummaries(now)`
  from the existing sweep tick.

**Deliberately deferred past this pass** (not silently dropped — recorded here so it isn't
mistaken for "done"):

- **Multi-context fan-out and leading-star pattern-target expansion for `add rule drop`**
  ([#79](https://github.com/ddeuchert/logaperture/issues/79)).
  `AggregateLevelControl.addRuleDrop` attaches to the first registered context only; a pattern
  target is rejected client-side with a clear error rather than mishandled. Real parity with
  `set logger`'s preview/confirm/multi-context broadcast is real additional scope, not folded
  in silently here — a fast-follow, same discipline `rule-pipeline-foundation.md`'s own
  "reset logger's rule-removal side effect is scoped to the exact-name path only" used.
- **`WildFlyContainerIT`** ([#80](https://github.com/ddeuchert/logaperture/issues/80)) — the
  epic's own required real-WildFly scenario (ERROR trimmed... restated for drop: INFO dropped,
  ERROR kept, non-matching/other-logger kept, survives
  `filter-spec`/`pattern-formatter`/new-handler/`:reload`, `sampleFull` and the summary line
  observed for real, a `STICKY` drop resumes after a restart) needs a running WildFly container
  this pass didn't have. Remains this issue's own open exit criterion.
- **`top`'s bytes-saved figure for a drop rule**
  ([#81](https://github.com/ddeuchert/logaperture/issues/81)) — the epic's "`top` shows the
  bytes a rule saved" is not wired up; `top`'s existing byte-counting formatter wrap is
  untouched by this slice.
- **`--dry-run`** — deferred by the epic itself, unchanged.

## Divergence from prior specs

Three real gaps surfaced during implementation, each resolved here rather than left to drift:

1. **`Level` has no `FATAL` member.** Every prior doc (this one included, before implementation)
   talks about `--below FATAL` and "FATAL is a hard limit" as if `Level` already had a `FATAL`
   value; it tops out at `ERROR` (`ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF`) everywhere in this
   codebase, and no event this pipeline ever sees can carry a level beyond what the adapter maps
   to that enum. Resolved pragmatically: `FATAL` is accepted as a CLI-only pseudo-token on
   `--below`, resolved client-side to `levelAtMost = ERROR` (nothing more severe exists to
   spare) — so "a rule may set `--below FATAL` to include ERROR" holds exactly as the epic
   describes, without adding a `Level` member (a cross-cutting change well beyond this issue)
   or a dead compiler-side clamp for a value that can never occur. `CompiledMatchers` needs no
   FATAL-specific enforcement as a result — the safety property is vacuously true, not
   separately coded.
2. **The periodic drop-summary line is not written into the target application's own log.**
   `logaperture-bridge`'s `Diagnostics` class doc already states why: writing into a logging
   framework this agent instruments, from inside it, is a re-entrancy risk. This spec's
   original text proposed exactly that, without checking that precedent. Implemented instead
   via the same plain `System.err.println("[logaperture] ...")` convention `RuleService` already
   uses for its own resume/persistence diagnostics — `core` doesn't depend on
   `logaperture-bridge` (by design), so this isn't routed through `Diagnostics` either, just the
   same convention repeated locally. An operator watching the agent's own stderr/diagnostic
   stream sees the summary; it does not appear interleaved in `server.log`.
3. **No `ExtLogRecord.getFormattedMessage()` caching.** The spike measured real cost savings
   from JBoss LogManager's own per-record formatted-message cache; this adapter has no
   compile-time JBoss LogManager dependency (by design) and doesn't reach for it via reflection
   in this pass. `JulRuleFilter` recomputes the formatted message once per candidate event via a
   shared stateless `Formatter.formatMessage(record)` call instead — still only paid by a
   logger with an active message matcher, just not memoized on the record the way the spike's
   prototype was. A fast-follow if profiling ever shows this matters in practice.

Also: `AggregateLevelControl.addRuleDrop`'s first-registered-context-only scope and the
deferred `WildFlyContainerIT` (see "Implementation status") are scope reductions, not
behavioral divergences from what's specified — recorded there rather than here.

## Data model

```
Drop implements LogRule {                     — logaperture-api, new
    // LogRule's common fields: id, loggerName, matchers, reason, tier,
    // expiresAt, createdAt (rule-pipeline-foundation.md "Data model")
    belowLevel: Level = ERROR                  // keep-floor: spares this level and above
    sampleFull: SampleFullPolicy               // see "The keep-one-in-N escape hatch"
    actionName() = "drop"                      // rendered by `list rules`, persisted verbatim
                                                 // as PersistedRule.action
}

SampleFullPolicy {                            — logaperture-api, new
    enabled: boolean = true                    // §7.4: on by default, explicit opt-out
    every: Duration = 5m                       // one fully-kept event per interval, per rule
}
```

`belowLevel` reuses `CompiledMatchers`' existing `level` matcher (`atMost`,
`rule-pipeline-foundation.md` "Matcher library") — not a new field on `Drop` duplicating what
the matcher already expresses; `Drop.belowLevel` is a convenience accessor over
`matchers().level()` for rendering and for the FATAL hard-limit check (see "Safety set"), not a
second source of truth.

`FATAL` can never be the resolved keep-floor: `--below FATAL` is accepted (a rule may want to
drop everything short of FATAL) but the matcher compiler clamps any attempt to express "drop
FATAL itself" — see "Safety set".

## Matchers in use

The full `rule-pipeline-foundation.md` matcher library applies unchanged; `drop` is simply the
first action to exercise `message`/`throwable` for real (the foundation's own tests used only
`logger`+`level`, via `TestRule`):

| Matcher | Flags | Notes |
|---|---|---|
| `logger` | `--logger <target>` (exact name, or leading-star pattern) | Attachment target, not a per-event check — "Logger scope and inheritance". |
| `level` | `--below LEVEL` | Default `ERROR` (keep-floor). |
| `message` | `--message-contains <text>`, `--message-contains-ignore-case <text>` | Against the *formatted* message, cached on the record. Mutually exclusive with each other (one or the other, not both) — same as `throwable`'s two message forms. |
| `throwable` | `--throwable <type>`, `--throwable-message-contains <text>`, `--any-cause` | `--any-cause` changes which frame of the cause chain the other throwable matchers test, not a standalone matcher. |

At least one of `--message-contains(-ignore-case)` / `--throwable` /
`--throwable-message-contains` is required — a drop with only a `--logger`/`--below` pair
would silence the whole logger below that level, which is what `set logger` already does more
plainly; `add rule drop` without a content matcher is a usage error naming `set logger` as the
right tool.

## Safety set

Restates `filtering-epic.md`'s already-agreed "Safety, required in the first version" list at
implementation altitude:

- **Keep-floor default.** `--below ERROR` unless stated otherwise. A drop never touches ERROR
  or above unless the operator explicitly widens it with `--below FATAL` — and even then,
  FATAL itself is exempt (next bullet).
- **FATAL is a hard limit.** Enforced in `CompiledMatchers`' construction, not left to the
  evaluator: a `level` matcher's `atMost` is clamped so it can never evaluate `true` for a
  FATAL event, regardless of what `--below` was asked for. This makes the limit a property of
  the compiled plan itself — provable by inspecting `CompiledMatchers`, not by trusting every
  caller of it to check separately.
- **Suppression floor (§9.5).** Already enforced generically at attach time by
  `RuleService`/`rule-pipeline-foundation.md`'s "Capability and audit" — nothing new here
  beyond `Drop` being the first action that makes a refusal observable (a protected-category
  `Drop` attach throws and audits, same as any other rule).
- **`sampleFull`, on by default.** See below.
- **Counted and visible.** Foundation's per-event hit counter (`RuleService`'s existing
  counting point, ahead of per-handler fan-out) is `Drop`'s own hit count; this slice adds the
  periodic summary line that makes the count visible *in the log*, not only in `list rules`.

### The keep-one-in-N escape hatch (§7.4)

Top-level §7.4 describes this as time-based ("one completely untouched event per interval"),
which is what `SampleFullPolicy.every` implements; `filtering-epic.md`'s own prose ("the first,
then every Nth, matching event is kept") reads as count-based. **This spec follows §7.4's
time-based form** (Decision #1, below) — a count-based "every Nth" needs a per-fingerprint
counter the way storm detection has one, and a drop rule has no fingerprint concept (it's
scoped to one logger by attachment, not to a message shape); a wall-clock interval is simpler,
cheap (one `volatile` timestamp per rule, an `Instant`/`nanoTime` comparison per candidate
event), and gives the same guarantee §7.4 asks for: nothing is ever more than `every` stale.

- The very first event a newly-attached `Drop` would otherwise deny is always let through in
  full, unconditionally — the rule's own first observation of "does this actually match", not
  a sampled one.
- After that, `every` (default 5 minutes) resets on a keep; the next matching event *at or
  after* the interval elapses is kept in full instead of denied, and the clock resets from
  there.
- `--sample-full <duration>` overrides the interval; `--no-sample-full` is the explicit
  opt-out §7.4 requires to be spelled out rather than defaulted to.
- A kept sample event still counts as a "hit" (it matched the rule) but is flagged
  separately so `top`/the periodic summary can report "N dropped, 1 sampled" rather than
  miscounting a sample as bytes saved.

### Periodic summary line

Modeled directly on storm detection's own end-of-storm/continuing lines
(`storm-detection.md` "The operation"), reusing the same `[logaperture]` logger identity so
both land in the same, already-expected place in the log:

```
03:19:07 WARN  [logaperture] drop summary: r3 (com.acme.batch.Worker, "This happens a lot")
                        — 41,209 suppressed since 03:14:02, 8 sampled through
```

- Emitted per active `Drop` rule with a non-zero count since its last summary, on a fixed
  interval (default 5 minutes — deliberately the same default as `sampleFull.every`, so an
  operator watching the log sees a sampled full event and a summary line on roughly the same
  cadence; the two are independently configurable, this is only the shared default).
- Uses the rule's own `context`-scoped logging pipeline, subject to the same fail-open
  discipline as everything else here — a summary line that can't be written (e.g. the handler
  it would go to is itself misconfigured) is swallowed, never allowed to throw back into
  application code.
- No line is written for a rule with zero hits in the interval — an idle drop rule stays
  silent, same as an idle storm-detection fingerprint never gets a "still nothing happening"
  line.

## Evaluation

Plugs into `rule-pipeline-foundation.md`'s existing gate `Filter`
(`installRulePipeline`/`JulRuleFilter`) and compiled-plan swap — this slice changes what the
plan's evaluation *does*, not how it's installed or re-armed:

- Per candidate event, at the point the foundation's filter already reaches: walk the
  event's logger's effective rule set (tree-inherited per `useParentRules`), in attachment
  order; the first `Drop` whose matchers all hold is terminal (`filtering-epic.md`
  "Evaluation" — several drops OR'd, first match wins). No `Trim` exists yet to run afterward
  on a survivor; this slice's evaluator has a `trim` no-op stage reserved but unimplemented,
  matching how the foundation spec left drop unimplemented for this one to fill.
- A terminal match: increment the rule's hit count (already the foundation's counting point),
  apply the `sampleFull` check (above) to decide keep-through-as-sample vs. deny, and return
  the verdict to the adapter filter.
- Checks run cheapest first inside a rule, per the existing `CompiledMatchers` ordering: level
  → throwable type → message (`rule-pipeline-foundation.md` "Matcher library"). Across rules
  attached to the same effective set, evaluation order is attachment order — no priority
  syntax, per epic Decision #7.

### Interaction with storm detection

`rule-pipeline-foundation.md` flagged, as a design note rather than a defect (nothing denied
an event at that point), that **install order decides which filter is outer**, and that once
`Drop` makes its filter actually deny, a deny short-circuits the chain before reaching whatever
filter ended up as the inner delegate.

This spec fixes install order deliberately, the opposite way from the foundation spec's other
offered option: the rule-pipeline filter is always installed as the **outermost** wrapper,
storm detection's always inner. A `Drop`-denied event never reaches storm detection's observer
at all (Decision #3) — an operator who attached a drop already knows the message is noisy and
took a deliberate step to mitigate it; there is no value in storm detection re-discovering and
reporting the same noise a second time through a different surface, and doing so would make
`logctl storms` a confusing echo of rules the operator already knows about. This also means
the rule-pipeline filter's own deny path costs nothing extra in storm detection's filter (it's
never reached), a strictly cheaper hot path than the "keep both counting" alternative would
have been. `installRulePipeline`/`installStormDetection`'s install call sites
(`ContainerIntegration`'s `installContext`) are ordered accordingly: the rule pipeline installs
first, always, on every context, so it ends up outermost — a one-line ordering change at the
composition root, not a filter-chaining redesign.

One consequence worth naming: a message that is *not yet* covered by a drop rule, and is
storming right now, is still seen and reported by storm detection exactly as before — this
only changes behavior for events a `Drop` already matches. And a rule with a keep-floor
(`--below ERROR`) or a narrow content matcher still lets non-matching events from the same
logger through to storm detection untouched; only the specific events the operator chose to
drop stop being counted as a storm.

## Capability and audit

- **`Capability.SUPPRESS`**, new — required in addition to `RULES_AUTHOR` to attach a `Drop`
  specifically (§9.3: "drop/trim actions specifically, separable from rate limiting" — `Trim`,
  #34, will require it too). `RULES_AUTHOR` alone is not sufficient once a concrete
  behavior-changing action exists to gate; the foundation spec deferred adding `SUPPRESS` to
  this issue for exactly this reason (its Decision #2).
- `PERSIST` required in addition, whenever `tier != SESSION` — unchanged from the foundation
  spec's existing rule, `Drop` doesn't add a second persistence gate.
- Suppression-floor refusal happens at attach time (already built by the foundation spec) —
  nothing new to design here.
- Audit: attach/removal already produce `MUTATION`/`REVERSION` records per the foundation
  spec's "Capability and audit"; this slice adds no new audit event shape. A `sampleFull` keep
  is *not* separately audited (it's an evaluation-time, not a configuration-time, event) —
  it's visible via the periodic summary line and `list rules`' hit count instead.

## Command surface

Follows `set-command-surface.md`'s noun-first grammar and tier token, and
`pattern-selection-semantics.md`'s pattern-target preview/confirm — nothing new invented here,
both reused verbatim.

### `logctl add rule drop <logger-target> [matchers] [--below LEVEL] [--sample-full <duration> | --no-sample-full] [session | for <duration> | sticky] [--reason <text>]`

```
logctl add rule drop com.acme.batch.Worker \
    --message-contains "This happens a lot" --below ERROR
logctl add rule drop '*.AutoUpdateHelper' \
    --throwable java.net.ConnectException \
    --throwable-message-contains "Failed to connect to URL" --below FATAL sticky
```

- `<logger-target>`: exact name, or leading-star pattern — same grammar `set logger` uses.
  A leading-star target previews its current matches (with the id each would get) and asks to
  confirm; `--yes` skips the prompt (`filtering-epic.md` "Targeting by pattern is shorthand").
  Trailing `.*` is rejected, same reason as `set logger`.
- Matchers as in "Matchers in use", above; at least one content matcher (`message`/`throwable`)
  is required.
- `--below LEVEL` defaults to `ERROR`; `FATAL` is accepted as a bound but never actually
  suppresses a FATAL event (see "Safety set").
- `--sample-full <duration>` / `--no-sample-full`: defaults to on, 5 minutes.
- Tier token: omitted → `FOR 4h`, same default `set logger` already uses (Decision #2) — one
  consistent omitted-tier default across every rule-bearing command rather than a
  drop-specific one.
- `--reason <text>`: same as `set logger`.
- Output: one line per attachment (one for an exact target, one per matched logger for a
  pattern target), each naming its new rule id — same shape `set logger` on a pattern target
  already produces. `--json` returns the same per-attachment array `rule-pipeline-foundation.md`'s
  `list rules --json` already uses for a single row.

### Unaffected

`logctl list rules`, `logctl reset rule <id>`, `logctl reset rules`, `logctl reset logger X` —
all already built by the foundation spec; this slice's only contribution to them is that they
now have a real `Drop` row to render/remove instead of only a `TestRule`.

## Persistence

Reuses `rule-pipeline-foundation.md`'s generic `rules:` state-file section and
`PersistedRule.action` discriminator field, unchanged in shape:

```yaml
rules:
  - id: r3
    loggerName: com.acme.batch.Worker
    action: drop
    tier: STICKY
    createdAt: 2026-09-22T03:14:02Z
    context: system
```

`Drop`'s own fields (`belowLevel`, `sampleFull`) live inside `matchers`/a small
action-specific payload the foundation's generic `PersistedRule` doesn't carry field-by-field
— **Decision needed** (see "Decisions" below): either extend `PersistedRule` with an
action-opaque `payload: Map<String,String>`-style bag `Drop`'s own `RuleFactory` decodes, or
give `Drop` a second, action-specific persisted row type joined by `id`. Proposed: the opaque
bag, so `Trim` (#34) doesn't need its own schema bump either — `PersistedRule` stays the one
generic shape every action's `RuleFactory` decodes against, matching the foundation spec's own
framing of `action` as "a discriminator... #72/#34's own... not defined here".

`Drop.registerActionFactory("drop", ...)` is called once, at each container's composition root
(`ContainerIntegration`'s install point, alongside where `RuleService` is constructed) — the
foundation spec named this primitive but registered nothing; this is its first real caller.
A resumed `Drop` keeps its persisted id and immediately starts denying again (no re-arm delay
beyond the normal resume pass) — this is the change that makes the foundation's "every
persisted row today is reported not resumed" note stop being true.

## Testing

Per top-level §12's cheap-unit-tests-plus-one-shallow-integration-test split, extending the
foundation spec's suite with `Drop`-specific coverage rather than duplicating its
already-proven generic-machinery tests.

**Unit — `core`:**

- `Drop` matches below its `belowLevel` and does not match at or above it; `--below FATAL`
  never suppresses an actual FATAL event (the compiler-level clamp, not an evaluator check).
- Message/throwable matcher combinations: contains, contains-ignore-case, throwable type
  (including a subclass), throwable-message-contains, `--any-cause` walking into a cause.
- First-match-terminal across several `Drop`s attached to the same effective set (inherited
  plus own).
- `sampleFull`: the very first matching event is always kept; the next match before `every`
  elapses is denied; the next match at/after `every` is kept and resets the clock;
  `--no-sample-full` denies every match with no exception.
- Periodic summary line: emitted only for a rule with a non-zero count since its last emission;
  silent for an idle rule; correct suppressed/sampled counts.
- Hit counting integrates with the foundation's existing per-event (not per-handler) counter —
  a regression check, not new machinery.
- `Capability.SUPPRESS` required in addition to `RULES_AUTHOR`; denial blocks attach without
  touching state, mirroring the foundation spec's own `RULES_AUTHOR`-denial test shape.
- Suppression-floor refusal on a protected-category logger, audited (reusing the foundation's
  check, proven here against a real action for the first time).
- `Drop`'s resume: a persisted `STICKY`/unexpired-`FOR` row reconstructs via
  `registerActionFactory("drop", ...)` and immediately resumes denying; an expired `FOR` row
  is recorded as a `REVERSION` and never resumed — mirrors `RuleService.resumeFromStateStore`'s
  existing test shape from the foundation spec, now exercised end-to-end with a real action.

**Unit — JUL/JBoss LogManager adapter:**

- A `Drop`-bearing plan's `Filter` actually returns deny for a matching event and allow for a
  non-matching one (the foundation's filter test only proved chaining/re-arm against an
  always-empty plan).
- Install order: the rule-pipeline filter is always outer, storm detection's filter always
  inner, regardless of which `install*` call happens first at a given context — the regression
  test for "Interaction with storm detection"'s fix.
- A `Drop`-denied event does **not** reach storm detection's counters — the actual proof the
  install-order fix works, not just that it's wired that way. A non-matching event on the same
  logger still does.

**Unit — CLI (`CommandsTest`):**

- `add rule drop` parses every matcher flag, the tier token (including the omitted-tier
  default of `FOR 4h`, matching `set logger`), `--sample-full`/`--no-sample-full`,
  `--reason`; a leading-star target previews and confirms exactly like `set logger`'s existing
  coverage; trailing `.*` is a usage error; missing content matcher is a usage error naming
  `set logger`.
- `list rules`/`reset rule`/`reset rules` render a real `Drop` row correctly (action name
  `drop`, `belowLevel`, `sampleFull` state) — the foundation's own tests used `TestRule`
  placeholders for these columns; this is their first real-data proof.

**Cross-process (`logaperture-it`'s `WildFlyContainerIT`):** the epic's own required scenario,
owned by this slice per `filtering-epic.md` "Testing" (deferred here from the foundation spec,
which had no concrete action to drive it with):

- ERROR-level matching event trimmed... — N/A, no trim in this slice; restated for drop: an
  INFO-level matching event is dropped, an ERROR-level matching event (at the keep-floor) is
  kept, a non-matching message on the same logger is kept, the same message on a different
  logger is kept.
- Survives a `pattern-formatter` change, a `filter-spec` write, a new handler added at
  runtime, and a `:reload` — the rule keeps denying afterward (within the accepted re-arm
  gap), proving the foundation's re-arm machinery actually carries a real verdict through
  these events, not just an empty plan.
- `sampleFull` observed for real: across a burst of matching events against a live WildFly
  instance, the periodic summary line and a sampled full event both appear in the server log.
- A `STICKY` drop survives a container restart and resumes denying without a fresh `add rule`.

## Exit criterion

From a plain shell, against a `java -jar` application started with
`-javaagent:logaperture-agent.jar`:

- `logctl add rule drop com.acme.batch.Worker --message-contains "This happens a lot" --below
  ERROR` stops that message from reaching the log at INFO/WARN, while an ERROR-level instance
  of the same message, and every other message from that logger, still logs normally.
- `logctl list rules` shows the new rule's id, matchers, `belowLevel`, tier, and a hit count
  that climbs as matching events occur.
- After several minutes of matching traffic, the log itself shows both a sampled full event
  and a periodic summary line without any further operator action.
- `logctl reset rule <id>` removes it; the message resumes logging at every level immediately.
- Attempting `add rule drop` against a protected-category logger is refused and audited;
  attempting to widen `--below` past FATAL never actually suppresses a FATAL event.
- Every command in `logctl --help` still passes the phone test.

## Decisions

| # | Decision | Status |
|---|---|---|
| 1 | `sampleFull` follows top-level §7.4's time-based form (one full event per `every` interval), not the epic prose's count-based "every Nth" reading — resolves the discrepancy between the two documents in this spec's favor | **Agreed** |
| 2 | `add rule drop`'s omitted tier token defaults to `FOR 4h`, same as `set logger` — one consistent default across every rule-bearing command | **Agreed** |
| 3 | Install order: rule-pipeline filter always outermost, storm-detection filter always innermost — a `Drop`-denied event never reaches storm detection's counters at all. An operator who attached a drop already took a deliberate mitigation step; storm detection re-reporting the same noise through a second surface adds confusion, not value | **Agreed** |
| 4 | `PersistedRule` gains an opaque `payload` bag for action-specific fields (`Drop`'s `belowLevel`/`sampleFull`) rather than a schema bump per action — keeps `Trim` (#34) from needing its own persistence schema change too | **Agreed** |
| 5 | Periodic drop-summary default interval is 5 minutes, matching `sampleFull`'s own default interval (independently configurable, shared default only for a predictable operator-facing cadence) | **Agreed** |
| 6 | `add rule drop` requires at least one content matcher (`message`/`throwable`); a bare `--logger`/`--below` pair is a usage error naming `set logger` instead | **Agreed** |

`filtering-epic.md`'s "Members and build order" table row for step 3 is updated from "new" to
this issue's number alongside this spec, per that document's own "each member still gets its
own spec and sign-off" statement — done as part of drafting this spec, not left for later.

## Sign-off

Signed off 2026-09-22. All six decisions accepted; #3 accepted in the opposite direction from
this spec's original proposal (rule-pipeline filter outermost, so a `Drop`-denied event never
reaches storm detection's counters — see Decision #3's rationale). Review artifact:
<https://claude.ai/artifact/Nop2iUg92UcmFi9Hu1DL2r>. Implementation may begin.
