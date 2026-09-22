# Rule pipeline foundation: `LogRule`, `useParentRules`, matcher library

Status: **signed off 2026-09-22; partially implemented.** `core` (`RuleRegistry`/
`RuleService`/matcher library/`useParentRules` resolution) and the JUL/JBoss LogManager gate
`Filter` are implemented and unit-tested on `feature/71-rule-pipeline-foundation`. Not yet
implemented: the JMX/CLI surface (`list rules`/`reset rule`/`reset rules`), state-file
persistence round-tripping, and container `installContext` wiring — see "Implementation
status" below. No concrete rule type
(`drop`, `trim`) ships in this slice — this is the shared machinery every later rule is built
on, per [`filtering-epic.md`](filtering-epic.md)'s build order (step 2 of 4).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.2 (gate/render stages),
§7.2 (rule model — matchers, actions), §7.3 (evaluation semantics — immutable compiled plan),
§9.2 (restrictions compose), §9.3 (capabilities — `rules.author`, `suppress`), §9.4 (sealed at
boot — protected-category lists), §9.5 (suppression floor / verbosity ceiling), §9.6
(suppression must never be silent), §9.7 (audit trail).
Epic spec: [`filtering-epic.md`](filtering-epic.md) — "What `drop` means", "What `trim` means",
"Matchers", "Logger scope", "Targeting by pattern is shorthand", "Rules as managed objects",
"Evaluation", "Safety limits", "Foundation: what the spike established", Decisions #2–#9,
#13–#16. This document is that epic's step-2 child spec; every epic decision it cites is
**already agreed** and restated here only where this spec's own design depends on it.
Builds on: [`storm-detection.md`](storm-detection.md) (`installStormDetection`'s
verdict-transparent, chain-and-restore `Filter` pattern on JUL/JBoss LogManager — this spec's
gate `Filter` follows the identical shape, see "Relationship to the storm-detection filter"),
[`top.md`](top.md) (the formatter-wrap pattern the render seam extends — trim itself is #34's
job, not this spec's, but the wrap point this spec doesn't touch is inherited from here),
[`persistence.md`](persistence.md) (`PersistenceTier` — `SESSION`/`FOR`/`STICKY` — and the
state-file schema, reused as-is for a rule's own lifetime),
[`pattern-selection-semantics.md`](pattern-selection-semantics.md) (the one-time-expansion
principle a leading-star `<logger>` target on `add rule` follows, cited rather than restated),
[`set-command-surface.md`](set-command-surface.md) / [`reset-command-surface.md`](reset-command-surface.md)
/ [`list-command-surface.md`](list-command-surface.md) (the noun-first command surface this
spec's generic `list rules`/`reset rule` forms follow).
Evidence: [`doc/spikes/rule-pipeline.md`](../spikes/rule-pipeline.md).
Tracks: [issue #71](https://github.com/ddeuchert/logaperture/issues/71). Depends on
[#26](https://github.com/ddeuchert/logaperture/issues/26) (storm detection, merged). Blocks
[#72](https://github.com/ddeuchert/logaperture/issues/72) (`drop`) and #34 (`trim`) — neither
can attach a rule until this lands.

## Functional summary

This slice ships no new user-facing action by itself — `drop` (#72) and `trim` (#34) are what an
operator actually reaches for. What lands here, visible once either of those exists:

- Every rule an operator attaches — once `drop`/`trim` exist to create one — gets a short id,
  shown in `logctl status` and `logctl list rules`, alongside the logger it's attached to, its
  matchers, its tier and expiry, and its hit count.
- A rule attached to a logger automatically reaches that logger's descendants, including ones
  that don't exist yet — the same way a level does — unless a descendant in between is told not
  to inherit it (`useParentRules false`).
- `logctl reset rule <id>` removes one rule by id; `logctl reset rules` removes every rule;
  `logctl reset logger X` removes every rule attached directly to `X` as part of resetting it.
- Nothing about this slice changes what any existing command does — `set`, `reset logger/handler`,
  `list loggers/handlers`, `status`, `top`, `doctor`, `storms` are all untouched.

## Scope of this slice

**In scope:**

- `LogRule` — a minimal common interface every later rule type (`Drop`, `Trim`, ...)
  implements. No concrete implementation ships here; see "Data model".
- Attachment model: a logger holds 0–n configured `LogRule`s, independent of its configured
  level; a `useParentRules` flag per logger, default `true`, with handler-style (not
  level-style) inheritance — filtering-epic.md Decisions #14/#15, restated in "Logger scope
  and inheritance".
- The matcher library — the filtering-epic.md §7.2 subset (`logger`, `level` upper bound,
  `message`, `throwable`) — compiled once into an immutable, swappable plan (§7.3).
- Rule management as generic machinery: short id assignment, `logctl list rules`, `logctl
  reset rule <id>` / `reset rules`, `reset logger X` also dropping that logger's own rules,
  per-event hit counting. Exercised end-to-end only once #72 gives it a concrete rule to
  manage (see "Testing"), but the machinery itself is this slice's to build.
- The gate-stage seam: one handler-level `Filter`, installed on every handler, chaining any
  filter already present, re-detected on every re-arm tick — the mechanism #72's `drop` plugs
  a verdict into. This slice's own `Filter` denies nothing on its own (no concrete rule exists
  yet to deny anything with) but the installation, chaining, and re-arm machinery is built and
  tested here so #72 adds only a verdict, not a seam.
- Safety scaffolding usable by any later action: the suppression-floor / protected-category
  refusal-and-audit check (§9.5) at attach time, and the capability check(s) required to attach
  a rule at all (see "Capability and audit").
- Persistence: a rule is a managed object with the same `SESSION`/`FOR`/`STICKY` tiers and
  expiry an override already has (`persistence.md`, reused as-is), including state-file
  round-tripping.
- `LogRule`/`Drop`/`Trim`'s common fields live in `logaperture-api`; matcher compilation, the
  rule registry, `useParentRules` resolution, and hit counting live in `logaperture-core`;
  the gate `Filter` implementation is JUL/JBoss-LogManager-adapter-only for this slice
  (Logback a fast-follow, matching every other M1/epic slice's precedent).

**Explicitly out of scope for this slice** (deferred to the issue that needs it):

- **Any concrete rule type** (`Drop` — #72; `Trim` — #34) and their own matchers-in-use,
  CLI flags, and safety-set specifics. This spec defines the shape `LogRule` implementations
  fill in, not a filled-in one.
- **The render-stage seam extension** (`ExtLogRecord` copy-and-replace-throwable) — #34's job;
  this slice's `Filter` is gate-stage only.
- **`add rule` itself** — there is no CLI verb to create a rule in this slice, because there is
  no concrete action to name after `add rule`. `logctl list rules`/`logctl reset rule <id>` are
  built and tested against an internal test-double `LogRule` (see "Testing"); `add rule
  drop|trim` is #72/#34's own command-surface work.
- **`frame` matcher, `not`/`any`/`all` composition, rule priority/ordering** — filtering-epic.md
  "Deferred and future enhancements".
- **`--dry-run`** — filtering-epic.md Decision, deferred by the epic itself.
- **The periodic suppression-summary line** (§9.6) — its *shape* depends on which action is
  doing the suppressing (a drop count reads differently from a trim count); this slice's hit
  counter is the data it will be built from, but the line itself is #72/#34's to write.

## Implementation status

Landed on `feature/71-rule-pipeline-foundation`, unit-tested, full reactor build green:

- `logaperture-api`: `LogRule`, `CompiledMatchers`, `RuleAttachOptions`, `RuleResetOutcome`.
- `logaperture-core`: `Capability.RULES_AUTHOR`; `RuleRegistry` (attachment +
  `useParentRules` state); `RuleService` (id assignment, capability/suppression-floor checks,
  audit, `effectiveRules` tree-inheritance resolution, `resetRule`/`resetAllRules`/
  `resetRulesForLogger`); `RulePlan`/`RulePlanSource` (the atomically-swapped compiled plan).
- `logaperture-adapter-jul`: `installRulePipeline`, `JulRuleFilter` — a second, independent,
  verdict-transparent `Filter` from storm detection's, proven to compose with it in either
  install order.

**One spec gap resolved during implementation:** `LogRule` gained a `reason()` field, not
listed in this document's original "Data model" — `AuditRecord`'s `reason` field needs
somewhere to read it from, and `LevelOverride` already carries the equivalent field for the
same reason. `RuleAttachOptions.reason` flows into it at attach time.

**Not yet implemented** (same branch, before this issue is ready to merge):

- The JMX surface (`LevelControlMXBean` additions) and the CLI (`logctl list rules`/`reset
  rule <id>`/`reset rules`, and `reset logger X`'s rule-removal side effect) — "Command
  surface"'s generic forms, exercised in the spec's own testing plan against a
  `CommandsTest`-style stub.
- State-file persistence round-tripping (the `rules:` schema, resume ordering) — "Persistence".
- Container `installContext` wiring (`NoneContainer`/`WildFlyContainer` constructing a
  `RuleService` per context and calling `installRulePipeline` alongside `installStormDetection`)
  and `AggregateLevelControl` multi-context merge for `list rules`.

## Logger scope and inheritance

Restates filtering-epic.md's already-agreed Decisions #14/#15 at implementation altitude — no
new decision here, only the shape `core` needs.

- **`LogRule` attachment is not a per-event pattern test.** A rule is attached directly to a
  specific logger, the same way a `<handler>` is attached to a `<logger>` in `standalone.xml`,
  and reaches descendants by **tree inheritance**, not by re-matching a pattern on every event.
- **A logger holds an ordered `List<LogRule>`**, independent of whether it has a configured
  level — configuring one never touches the other.
- **A logger also holds a `useParentRules` flag, default `true`.** Effective rules for a logger
  are its own configured rules, plus every ancestor's own configured rules accumulated on the
  way up, **stopping at the first ancestor (inclusive of that ancestor's own rules) whose
  `useParentRules` is `false`.** This is the handler fan-out algorithm (`use-parent-handlers`),
  not level's single-winner nearest-ancestor algorithm — additive by default, cancellable on
  purpose.
- **No re-scan.** A descendant created after a rule is attached to an ancestor picks the rule up
  immediately and for free, through ordinary tree lookup — there is no sweep pass analogous to
  the retired `PatternRule` machinery (`pattern-selection-semantics.md`) to re-run.
- Resolution is a plain walk up the logger-name tree (dot-segment ancestors, `ROOT` at the top),
  mirroring how `effectiveLevel` is already resolved — no new tree data structure; `core` already
  has to answer "what's the nearest configured X" for level, and this reuses the same logger-name
  addressing, just with the accumulate-and-stop-on-`false` rule instead of level's
  nearest-single-winner rule.

## Matcher library

The filtering-epic.md §7.2 subset, compiled once, shared by every rule type:

| Matcher | Forms | Notes |
|---|---|---|
| `logger` | attachment target only (exact name, or leading-star one-time expansion) | Not a per-event matcher — see "Logger scope"; the attached-to logger is identity, not a runtime check. |
| `level` | upper bound (`atMost`) | Reuses `Level`'s existing ordering (`level-control.md`). |
| `message` | `contains`, `containsIgnoreCase` | Evaluated against the *formatted* message, cached on the record — a rule-bearing logger is the only one that pays for formatting early (spike finding 6/point 6 in both `top.md` and `storm-detection.md`). |
| `throwable` | `type` (subclasses included), `messageContains`, `anyCause` | `type` compares by class name (or an ancestor's name when `anyCause`/subclass matching is asked for) — no reflection needed at match time, a `String`/`Class` comparison. |

Compiled once into an immutable `CompiledMatchers` value (checks ordered cheapest-first: logger
identity is already known from attachment, so per-event cost is level → throwable type →
message), held by the `LogRule` it belongs to. All matchers on a rule are AND'd — no
`not`/`any`/`all` in this slice (epic Decision #3/deferred list).

`frame` is listed in the epic's matcher table as "out of scope for the first version" — this
slice's `CompiledMatchers` therefore has no frame field at all, not a stub; adding it later is
additive to the type, not a breaking change.

## Data model

```
LogRule {                                     — logaperture-api, new interface
    id: String                                 // short id, see "Rule identity"
    loggerName: String                         // the logger this rule is attached to
    matchers: CompiledMatchers                 // logger-scope excluded — see above
    tier: PersistenceTier                      // SESSION | FOR | STICKY (persistence.md, reused)
    expiresAt: Instant?                        // non-null iff tier == FOR
    createdAt: Instant
    hitCount: long                              // per-event, not per-handler (epic Decision #9)
}
```

`Drop`/`Trim` are not defined here — each is `#72`/`#34`'s own type implementing `LogRule` plus
its action-specific fields (`belowLevel` for both, `sampleFull` policy for `drop`, `frames`/
`collapseCauses` for `trim`). This slice's interface carries only what every rule needs
regardless of action, matching how `LevelOverride` (a concrete type, not an interface — rules
differ from level overrides in having more than one concrete shape) still shares its
tier/expiry fields' *pattern* with every other persisted, expiring thing this codebase manages.

```
LoggerRuleConfig {                            — logaperture-core, new (mirrors LoggerInfo's
    rules: List<LogRule>                        role for level: per-logger state `core` owns)
    useParentRules: boolean                     // default true
}
```

Held per logger name in the same map `core` already keeps baselines/overrides in — not a new
top-level registry class, an additional field set alongside the existing per-logger state.

```
RuleAttachOptions {                           — logaperture-api, new (parallels SetLevelOptions)
    tier: PersistenceTier = SESSION
    expiresIn: Duration?                        // required iff tier == FOR, same validation as
                                                 // SetLevelOptions (persistence.md)
    reason: String?
}
```

## Rule identity: short ids

**Decision needed (see "Decisions" below).** Every other addressable thing in this codebase is
addressed by its own name (a logger by its logger name, a handler by its `HandlerRef`) — a rule
is the first object this codebase manages that has no natural name of its own (two `drop` rules
can be attached to the same logger with different matchers) and therefore needs a **generated**
id.

Proposed: a short, monotonically-increasing, process-lifetime id, prefixed by action-agnostic
letter `r` (`r1`, `r2`, ...) — assigned by `core` at attach time, never reused within a JVM's
lifetime (even after the rule it named is removed), consistent across `SESSION`/`FOR`/`STICKY`
so a resumed `STICKY` rule keeps a *stable* id across a restart (persisted in the state file,
not re-minted on resume — same identity discipline `LevelOverride` gets for free from being
keyed on logger name, made explicit here because a rule has no such natural key). Scoped **per
JVM/context**, not global — the same way a `HandlerRef` is already JVM-scoped — so two contexts
in one `AggregateLevelControl` merge can produce the same bare id; `logctl list rules`/`status`
already stamp `context` on every row spanning more than one context (`storm-detection.md`'s
convention), reused here rather than inventing a globally-unique id scheme this project has no
other use for.

## Evaluation

Rules compile into one immutable plan per context, swapped atomically via a `volatile`
reference (§7.3) — the same discipline `CompiledMatchers` already uses at the single-rule level,
now at the whole-context level: attaching or removing any rule anywhere recompiles the full plan
and swaps the reference, never mutates a live one in place. Evaluation itself (per event, once a
concrete action type exists to evaluate) is #72/#34's to specify — this slice's job stops at
"the compiled plan is ready to be walked," matching how this issue supplies the *machinery* the
next two issues consume, not the walk itself.

**Hit counting is per event, not per handler** (epic Decision #9, spike finding 4) — the same
gap the storm-detection spike caught in its own prototype. The counting point is the gate
`Filter`'s single evaluation per event, before per-handler fan-out, so a rule attached to a
logger with three handlers counts one hit per matching event, not three.

## Relationship to the storm-detection filter

Two independent handler-level `Filter`s end up chained on the same handler once both #26 and
this slice are installed: storm detection's (verdict-transparent, always allows) and this
slice's gate filter (verdict-transparent *in this slice*, since no rule denies anything yet;
verdict-changing once #72's `drop` exists). Both follow the identical **install-once,
capture-and-delegate-to-whatever-was-there, re-detect-on-every-re-arm-tick** shape
`storm-detection.md`'s "Adapter SPI" and "Reconfiguration and lifecycle" sections already
establish — this slice's `installRulePipeline(...)` on `LoggingAdapter` is a sibling of
`installStormDetection(...)`, not a variant of it that tries to reuse the same `Filter`
instance. Keeping them separate means #26's contract (**never denies**) stays literally true of
that filter forever, rather than becoming "never denies, except when the rule-pipeline half of a
shared filter decides to" — a much easier invariant to keep proving in a unit test.

```
/** Installs the gate-stage rule filter on every handler this adapter can act
 *  on right now, evaluating each candidate event against the current
 *  compiled RulePlan. Denies (returns false) only for an event a Drop rule
 *  (#72) matches -- this slice's own plan is always empty, so it denies
 *  nothing yet. Chains any filter already installed (including a prior
 *  installRulePipeline call's own filter, and storm detection's, in
 *  whichever order both were installed). Idempotent, re-armed the same way
 *  installStormDetection is -- doc/specs/storm-detection.md "Adapter SPI".
 *  Default no-op, for a framework this slice doesn't instrument. */
default void installRulePipeline(RulePlanSource plan) { }
```

`RulePlanSource` (in `core`) is the `volatile`-plan reader described in "Evaluation" — the
adapter's filter reads the current plan on every event and does framework-specific extraction
only; matcher evaluation and the eventual drop/trim decision live in `core`, same
adapter-is-dumb discipline `storm-detection.md`'s `StormObserver` split already established.

**Reconfiguration and lifecycle**: installed once per context at the same `installContext`
point as `installStormDetection`/`installByteCounting`, re-armed via the existing
`reapplyOnReset` hook. Same fail-open re-arm gap the spike found and the epic already accepted
(filtering-epic.md Decision #13) — between a framework reset and the next re-arm tick, a
matching event that a later `drop` rule would deny is *not* denied. This slice's own filter
denying nothing yet means the gap is currently unobservable; it becomes real once #72 ships and
is already documented, not a new finding at that point.

## Capability and audit

**Decision needed (see "Decisions" below).** §9.3 lists both `rules.author` ("writing arbitrary
new rules at runtime — a much larger grant than applying one") and `suppress` ("drop/trim
actions specifically, separable from rate limiting") as distinct capabilities; neither exists
yet in `Capability` (`logaperture-core`), which currently has only `VIEW`/`LEVEL_RAISE`/
`LEVEL_LOWER`/`PERSIST`/`HANDLER_RAISE`/`HANDLER_LOWER`. Every `add rule` call in this codebase
*is* runtime rule authoring by construction (there is no signed-rule-pack `rules.apply` path in
this project at all, let alone in this epic) — proposed: add `RULES_AUTHOR` to `Capability`,
required by attaching *any* `LogRule` regardless of action, gating this slice's generic attach
path once #72/#34 give it something concrete to gate. `SUPPRESS` is proposed as `Drop`'s own
concern (§9.3 ties it to "drop/trim actions specifically") — deferred to #72's own spec rather
than added here, since this slice never denies an event on its own. `PERSIST` is required in
addition to `RULES_AUTHOR` whenever `tier != SESSION`, mirroring `persistence.md`'s existing
rule for level overrides exactly.

- **Suppression floor (§9.5).** Attaching a `LogRule` to a protected-category logger is refused
  and audited — the same check `handler-floor-control.md`'s squelch warnings and the storm
  detector's own "never denies" contract both already respect the *existence* of, reused here
  at attach time rather than at evaluation time (an attach-time refusal is cheaper and gives a
  clearer error than letting a rule silently never fire against a category it was never allowed
  to touch).
- **FATAL is a hard limit** (epic "Safety limits") — enforced by `CompiledMatchers`/whatever
  evaluates a concrete rule (#72/#34), not by this slice's attach path; noted here only because
  it constrains "what a matcher's `level` upper bound may express" (an `atMost FATAL` rule is
  legal to attach — being "the fatal ceiling" is not the same as denying attachment, it's a
  runtime no-op on FATAL events specifically enforced by the evaluator).
- **Audit.** Every attach and every removal is a `MUTATION`/`REVERSION` audit record, same
  fields §9.7 already specifies for a level override (principal, source, previous/new value,
  `reason`) — a rule attach's "new value" is its full matcher/tier description, its "previous
  value" is null (attaching is always additive; a second `add rule` on an identical target does
  not update an existing rule in place — filtering-epic.md's "Rules as managed objects" gives
  each attachment its own id, unlike `setLevel`'s one-override-per-logger idempotency).

## Command surface (generic machinery only)

No `add rule` in this slice — see "Explicitly out of scope". What lands:

### `logctl list rules [--show-all]`

Follows `list-command-surface.md`'s established shape: without `--show-all`, every currently
attached rule (there is no "not currently active" state for a rule the way a logger can run at
its configured level with no override — every attached rule *is* an override in this sense, so
`--show-all` has no distinct meaning yet and is not added as a flag until a rule type exists to
give it one). Columns: id, logger, action (blank/`?` until #72/#34 exist — this slice's own test
double supplies a placeholder action name for test purposes only), matchers, tier, expiry, hit
count, and, if a rule is attached above a point where `useParentRules=false` cuts inheritance
off between it and the row being shown, a note saying so (epic "Rules as managed objects").

### `logctl reset rule <id> [--include-sticky]`

Removes one rule by id, refusing on `STICKY` without `--include-sticky` exactly the way `reset
logger <exact-name>` refuses on a sticky level override (`reset-command-surface.md` Decision
#1) — a single named id is "one specific thing," not a set.

### `logctl reset rules [--include-sticky]`

Removes every attached rule, reporting any sticky ones left in place — the set-level
skip-and-report shape `reset-command-surface.md` Decision #1 already established for `reset
loggers`/`reset handlers`.

### `logctl reset logger <target>` — extended, not new

Already-shipped behavior gains one more side effect: resetting a logger also removes every rule
attached **directly** to it (not its descendants' own separately-attached rules — same
"directly attached only" scoping `reset-command-surface.md`'s existing `reset logger X` uses for
level overrides). No new flag; this is additive behavior on an existing command, called out here
because it's this spec's contribution to that command, not a new one.

## Persistence

A `LogRule`'s tier/expiry follow `persistence.md`'s existing grammar and sweep exactly —
`SESSION` lives until JVM stop, `FOR <duration>` auto-reverts (removal, for a rule — there's no
"revert to baseline" the way a level has one; a `FOR` rule simply stops being attached at
expiry, same as `persistence.md`'s expiry sweep already does for an override), `STICKY`
round-trips through the state file. State-file schema: a new top-level `rules:` list, one entry
per attached rule, `schemaVersion` bump (whatever the current version is at implementation time
+ 1) — additive in spirit exactly the way `pattern-selection-semantics.md`'s own schema-4→5 bump
was, an older file with no `rules:` key reads as "no rules," never a parse failure.

```yaml
schemaVersion: <next>
overrides: [...]              # unchanged
handlerOverrides: [...]       # unchanged
rules:
  - id: r1
    loggerName: com.acme.batch.Worker
    action: ...                # #72/#34's own discriminator field, not defined here
    tier: STICKY
    createdAt: 2026-09-22T03:14:02Z
```

Resume ordering mirrors `persistence.md`'s existing resume pass: `STICKY` rules reapply
unconditionally, unexpired `FOR` rules reapply with reduced remaining time, expired `FOR` rules
never reapply and are recorded as a `REVERSION`. A rule's persisted id is reused verbatim on
resume (see "Rule identity") — a resumed `STICKY` rule is the *same* rule from the operator's
point of view, not a new one that happens to look identical.

## Testing

Per top-level §12's cheap-unit-tests-plus-one-shallow-integration-test split. This slice has no
concrete action to exercise end-to-end, so its own suite leans on a small in-repo test double
(`TestRule implements LogRule`) wherever "some rule is attached" is what's under test, exactly
the way `LoggingAdapter`'s own tests already use a fake adapter rather than a real framework.

**Unit — `core`:**

- Attaching a rule to a logger, then resolving effective rules for a descendant created
  afterward with no re-scan, picks it up.
- `useParentRules=false` on an intermediate logger cuts off everything above it; that logger's
  own directly-attached rules still apply.
- Rules attached at nested ancestors accumulate (additive by default).
- A level change on any logger never affects rule scope, and a rule attach/detach never affects
  level scope (the two axes are independent — the footgun the epic spec calls out by name).
- A leading-star `<logger>` target's one-time-expansion resolution (reused, not reimplemented,
  from `pattern-selection-semantics.md`'s existing matcher) produces one attachment/one id per
  currently-known match; a logger created afterward with a matching name gets nothing from that
  call.
- Short id assignment: monotonic, never reused within a JVM's lifetime even after removal, and
  stable across a resume from a persisted `STICKY` rule (see "Rule identity").
- Compiled-plan swap is atomic under concurrent attach/detach — many threads attaching distinct
  rules concurrently never lose an attachment or corrupt the plan (a `volatile`-swap correctness
  test, not a performance one).
- Hit counting is per event: a logger with three attached handlers records one hit per matching
  event through the test-double filter path, not three.
- Suppression-floor refusal at attach time: attaching to a protected-category logger throws and
  is audited, mutates nothing.
- Capability checks: `RULES_AUTHOR` denial blocks attach without touching state;
  `RULES_AUTHOR` + `PERSIST` both required for a non-`SESSION` tier, mirroring
  `LevelControlServiceTest`'s existing `PERSIST` coverage.
- Audit record written for every attach and every removal, with correct previous/new-value
  shape (attach: previous `null`).

**Unit — JUL/JBoss LogManager adapter:**

- `installRulePipeline` installs a verdict-transparent `Filter` (empty plan denies nothing) that
  chains and restores any filter already present — the identical shape
  `storm-detection.md`'s `JulStormObserverTest` already proves for `installStormDetection`,
  repeated here for this slice's own filter.
- A second `installRulePipeline` call doesn't double-install.
- Both filters (storm detection's and this slice's) installed on the same handler both apply,
  in either install order — a real regression test for "Relationship to the storm-detection
  filter"'s claim that they compose cleanly.

**Unit — CLI (`CommandsTest`):**

- `list rules` renders id/logger/matchers/tier/expiry/hit-count columns from a stubbed report
  containing a `TestRule`; empty report renders "No rules attached." rather than throwing.
- `reset rule <id>` / `reset rules` — reverted / nothing-to-reset / sticky-refused / bulk-with-
  skips shapes, mirroring `reset logger`/`reset loggers`' existing `CommandsTest` coverage
  exactly.
- `reset logger X` (existing command) also reports rules removed as a side effect, alongside its
  existing level-override reporting.

**Cross-process (`logaperture-it`'s `WildFlyContainerIT`):** deferred to #72 — there is no
concrete rule to drive through a real WildFly container yet, and this slice's own filter denies
nothing observable end-to-end. This slice's exit criterion (below) is therefore unit-level only,
with #72's own exit criterion covering the first real cross-process proof of the seam this slice
built.

## Exit criterion

Unit-level only, for the reason given above — the concrete cross-process proof lands with #72:

- A `TestRule` attached to a logger via `core`'s attach path is picked up by a descendant
  created afterward, with `useParentRules=false` on an intermediate logger correctly cutting
  inheritance off — proven in `core` unit tests, no real framework needed.
- `installRulePipeline` on the JUL/JBoss LogManager adapter installs a chaining,
  verdict-transparent `Filter` that composes correctly alongside `installStormDetection`'s own
  filter on the same handler.
- `logctl list rules`/`logctl reset rule <id>`/`logctl reset rules` render and behave correctly
  against a stubbed `TestRule`-bearing report.
- A `STICKY` `TestRule` round-trips through the state file with a stable id across a simulated
  resume.
- No capability beyond `RULES_AUTHOR` (+ `PERSIST` for non-`SESSION`) is required to attach; a
  protected-category logger refuses attachment and audits the refusal.

## Decisions (sign-off)

| # | Decision | Status |
|---|---|---|
| 1 | Rule id scheme: per-context monotonic `r<N>`, persisted verbatim across a `STICKY` resume, disambiguated by `context` the same way a `HandlerRef` already is in a multi-context merge | **Agreed** — as written in "Rule identity" |
| 2 | New `Capability.RULES_AUTHOR`, required to attach any `LogRule` regardless of action; `SUPPRESS` deferred to `Drop`'s own spec (#72) rather than added by this slice | **Agreed** — as written in "Capability and audit" |
| 3 | The gate seam is a **second, independent** handler `Filter` (`installRulePipeline`), not a shared/merged filter with storm detection's `installStormDetection` | **Agreed** — as written in "Relationship to the storm-detection filter" — keeps #26's "never denies" contract literally, provably true forever |
| 4 | `logctl list rules` ships in this slice against a test double, with no `add rule` to produce a real row until #72/#34 land | **Agreed** — the machinery is action-agnostic; holding it back would delay its own review/testing for no benefit |
| 5 | State-file `rules:` schema shape (id, loggerName, tier, createdAt, plus an `action` discriminator #72/#34 will define) | **Agreed** — as sketched in "Persistence"; the discriminator field itself is left for #72 to name |

## Divergence from prior specs

None — this is new machinery with no shipped precedent to supersede. `filtering-epic.md` itself
is updated once this slice's decisions are agreed, per that document's own "each member still
gets its own spec and sign-off" statement: fold any changes back into both files together.

## Sign-off

Signed off 2026-09-22. All five decisions accepted as proposed, no changes requested. Review
artifact: <https://claude.ai/artifact/LKAHWf5rUchJi5ch4JdrmF>. Implementation may begin.
