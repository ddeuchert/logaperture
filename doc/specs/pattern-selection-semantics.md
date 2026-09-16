# Pattern targeting as pure selection (issue #49)

Status: signed off — implementation not yet started.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.7, §18.9 (both roadmap
entries, resolved differently by this spec than either originally described), §5 (Feature 1
definition), §6.1 (persistence tiers), §9 (capability/audit model), §11.1 (component
versioning).
Builds on, unchanged: [`doc/specs/level-control.md`](level-control.md) (the segment-anchored
glob grammar and `NameFilter` — this spec changes nothing about which strings are valid
patterns, only what happens once one is resolved), [`doc/specs/persistence.md`](persistence.md)
(the override/expiry/persistence tiers, reused as-is).
Supersedes: [`doc/specs/pattern-level-targeting.md`](pattern-level-targeting.md)'s standing-rule
sections (issue #41, slice 3 — the glob-matching grammar itself, slice 1, is untouched and stays
the foundation).
Tracks: [issue #49](https://github.com/ddeuchert/logaperture/issues/49), which supersedes issue
[#41](https://github.com/ddeuchert/logaperture/issues/41)'s slice 3.

**A note on issue #42.** Top-level §18.9's reset-namespace-split proposal (`reset logger` /
`reset loggers` / `reset handler` / `reset handlers`, `--include-sticky`) was worked out in
[issue #42](https://github.com/ddeuchert/logaperture/issues/42) and a spec,
`doc/specs/reset-command-surface.md`, but its implementation (PR #48) was **closed unmerged** —
none of it ever landed on `develop`. Field-testing that unmerged branch is what surfaced the
insight behind this spec (see "Motivation" below), but this spec builds directly on top of
issue #41's actually-shipped surface (`logctl reset <target>` / `logctl reset --all`, a single
`resetLevel(target)`/`resetAll()`), not on #42's. §18.9's proposal remains open, unimplemented,
and entirely out of scope here — a deliberate choice to keep this spec narrow (see Scope) rather
than fold it in just because `reset-command-surface.md` is being retired as a document.

## Functional summary

After this feature, the user will be able to:

- Set a level on every logger matching a **leading-star** pattern in one command, exactly as
  today: `logctl error *.deployment.scanner`. This does precisely what typing the same command
  against each currently-matching logger by hand would do — nothing more. A logger created
  tomorrow that would also match is **not** affected; there is no standing, invisible rule
  waiting to catch it.
- Notice that `set` on a **trailing-star** target (`logctl debug org.apache.*`) is now a usage
  error (Decision #5) rather than a fan-out onto every currently-matching descendant
  individually. Every descendant, present and future, already inherits `org.apache`'s level from
  the framework, so doing that was always redundant bookkeeping; this spec rejects the trailing
  star on `set` outright, naming `logctl debug org.apache` as the fix, rather than silently
  rewriting the command for the operator.
- See a preview of exactly which currently-known loggers a leading-star `set` command is about to
  affect before it takes effect, and confirm before it applies — kept from today's behavior
  (Decision #1), reworded to drop the now-inaccurate "standing rule" framing.
- **Keep pre-provisioning a logger that doesn't exist yet by its exact name** —
  `logctl debug org.MyBean.ThisIsNew` still works today even if nothing has instantiated
  `org.MyBean.ThisIsNew` yet, and it still will after this spec: "no future-attaching" is a
  **pattern-only** restriction. An exact name is never resolved against "currently-known
  loggers" the way a pattern is — it always creates or replaces a `LevelOverride` for that
  literal string, "Known" state and all (`level-control.md`'s "Baseline capture": "Logback
  accepts a level on a logger that hasn't been instantiated yet, and this slice must too"),
  exactly as it does today. Only a *pattern* target is bounded to what currently exists —
  because only a pattern needs to "find" matches at all; an exact name has nothing to find.
- Reset one logger, or every logger matching a pattern, the same way, using today's existing
  grammar: `logctl reset com.acme.batch.Worker`, `logctl reset *.deployment.scanner` — reverting
  whatever is currently overridden under that scope. This is actually **more** capable than
  today's shipped `logctl reset <pattern>`, which only acts when `<pattern>` is a tracked rule's
  own exact pattern string (see "Motivation" and Decision #2's discussion) — with no `PatternRule`
  left to look up, this spec makes `resetLevel` resolve a pattern's *current* matches directly,
  the same way `listLoggers`/`levels` already does.
- Get "this logger and its descendants" from `logctl debug org.apache` **alone**, with no
  trailing star needed at all — the framework's own inheritance already means the descendants
  come along for free. This is a real change in what you type, not just in what LogAperture
  tracks underneath it (see "Motivation" and Decision #5).
- Keep using `logctl levels *.mygroup.*` to look up a whole subtree by an abbreviated name —
  unaffected; `listLoggers` was always a live query, never a standing rule.

## Motivation

Every logging framework this project targets — Logback, Log4j 1.x/2.x, JUL, and JBoss
LogManager (WildFly's own) — already propagates an explicit level down to any descendant logger
with no explicit level of its own, present *and* future, via its own parent-chain lookup
(`doc/logaperture-spec.md` §4.3). Setting `org.apache` to `DEBUG` and never touching
`org.apache.tomcat` again means a `org.apache.tomcat` logger instantiated next week inherits
`DEBUG` automatically, with no help from LogAperture at all. This was already documented as a
known fact in `pattern-level-targeting.md`'s "Bare name vs. trailing wildcard" section — but that
section used it only to explain what a **bare name** target does, not to question whether a
**trailing-wildcard** target's own standing-rule machinery was worth its cost.

Concretely field-testing #42's scoped-exclusion mechanism (PR #48, against a live WildFly
container — never merged, see the note at the top of this document) is what surfaced the real
question. The walkthrough: `logctl trace a.*` fans out a `TRACE` override onto sixteen loggers
and installs a standing `PatternRule`; `logctl reset logger a.a.a` reverts that one logger and
carves it out of the rule; a *new* logger, `a.a.a.cat`, created afterward by ordinary application
traffic, is picked up by the periodic sweep and silently given its own `TRACE` override and audit
record a few seconds later — indistinguishable, from the operator's vantage point, from having
been set by hand, except that nobody set it by hand. The user never ran a command that mentions
`a.a.a.cat`. The only reason it ended up overridden is a persisted rule the operator has no direct
way to inspect (short of #42's own `(cascading)` marker — never shipped either — built only
because this invisibility was already a known problem).

That is the actual defect, not a bug in the exclusion arithmetic: **a pattern-based `set`
produces state a user cannot distinguish, from the visible surface, from having typed each
matched command individually — except that it silently keeps mutating the system after the
command has finished running.** Fixing the exclusion arithmetic (which #42/PR #48 did correctly,
and thoroughly) treats a symptom of a design that shouldn't exist in the first place, given that
the very propagation the standing rule exists to reimplement is already guaranteed by the
framework underneath it, for the one shape (trailing-wildcard, i.e. "this logger and its
descendants") where that guarantee actually applies. **This spec addresses that root cause
directly against issue #41's already-shipped standing-rule mechanism** — #42's unmerged branch is
the field test that revealed the problem, not something this spec builds on or needs.

**The resolution goes one step further than "trailing-wildcard doesn't need it."** A
leading-wildcard pattern (`*.Worker`) never had a framework guarantee to lean on in the first
place — `org.acme.Worker` and `com.other.Worker` share no ancestor, so nothing about the
framework's own hierarchy would ever propagate a level from one to the other. But the same
transparency objection applies regardless of *why* a match was found: the user's only tangible,
inspectable artifact is the result (`org.acme.Worker` and `com.other.Worker` now both read
`TRACE`), which looks identical whether they typed two commands or one wildcard one. A wildcard
is a **selection convenience** — a means of building a list of 0..N existing loggers to act on in
one call — and nothing about that convenience implies an ongoing promise to also act on a logger
that gets created afterward. So this spec drops standing-rule behavior for **every** pattern
shape, not only the one a framework happens to make redundant.

**Trailing-star `set` is a stricter case than "drop the standing rule," though — it's not just
that a future descendant shouldn't be auto-covered, it's that fanning an override out onto every
*current* descendant individually was never buying anything, at any point in time.** Setting
`org.apache` to `DEBUG` alone already makes every current and future descendant with no level of
its own read `DEBUG` — that's not a promise LogAperture has to keep by watching for new loggers,
it's a fact about the framework that's already true the instant `org.apache` itself is set. A
leading-star pattern (`*.Worker`) has no such shortcut: `org.acme.Worker` and `com.other.Worker`
share no ancestor, so the only way to get `DEBUG` onto both **right now** is an override on each,
individually — genuine selection, still worth doing even with no standing-rule follow-up. This
spec treats the two shapes differently on `set` for exactly this reason (see Decision #5):
leading-star keeps today's fan-out-to-current-matches behavior; trailing-star drops the fan-out
entirely, because there was never a real "select N loggers" step to perform in the first place —
only ever one logger, `org.apache` itself, worth touching. **`reset`, by contrast, keeps full
selection semantics for both shapes** — `reset org.apache.*` still needs to find and revert
*whatever is currently overridden* under that scope, which, unlike `set`, cannot be collapsed to
"just the one ancestor node": a descendant could carry its own hand-set override (from an
exact-name `set` run directly against it) that only a real scan of current matches will find.

## Scope

**In scope:**

- Retiring `PatternRule`, `PatternRuleRegistry`, and every piece of machinery that exists only to
  serve them: the pattern-sweep pass (`applyStandingRules`/`applyRulesToUncoveredLoggers`/
  `expirePatternRule`/`adoptPatternRule`), `LevelOverride.originPattern`, and the "Precedence"
  rules that exist only to protect an exact-name override from a rule reclaiming it later.
- Re-specifying `setLevel`/`resetLevel` with a pattern target as pure selection: resolve once
  against currently-known logger names, then act on each resolved name exactly as the exact-name
  path already does.
- **`resetLevel` on a pattern moving from an exact-string `PatternRuleRegistry` lookup to a real
  current-match resolution.** Today's shipped behavior (`pattern-level-targeting.md`) only acts
  when the target is *exactly* a tracked rule's own pattern string — `logctl reset
  org.apache.tomcat` against a live `org.apache.*` rule is a no-op today, and reverting the whole
  rule requires typing `org.apache.*` back verbatim. Once `PatternRule` is gone there's no rule
  identity left to match against, so `resetLevel` on a pattern becomes exactly what
  `listLoggers`/`levels` already do: resolve the pattern against currently-known loggers, and
  revert whichever of them carry an override. This is a genuine capability increase inside this
  spec's own scope (a natural consequence of removing `PatternRule`, not scope creep from #42) —
  see Decision #2.
- **`setLevel` rejecting a trailing-star target outright** (Decision #5) — distinct from, and in
  addition to, retiring the standing-rule machinery: even a one-time fan-out onto every current
  descendant is redundant for `set`, since the framework's inheritance already covers them once
  the literal ancestor is set. `resetLevel`/`listLoggers` are unaffected — a trailing star keeps
  full selection semantics on both.
- `ResetOutcome`/`ResetOutcomeData` dropping the `patternRuleRetired` boolean — nothing is ever
  "retired" once there is no rule.
- State-file schema: drop the `patternRules:` section entirely; bump `schemaVersion` (4 → 5).
- Carrying forward unchanged: the segment-anchored grammar itself (`level-control.md`); today's
  `logctl reset <target>` / `logctl reset --all` command grammar and signatures (unaffected by
  this spec — only what happens *inside* a pattern-targeted `resetLevel` call changes);
  `listLoggers`'s glob support (always a live query, never touched by any of this).

**Explicitly out of scope:**

- **Issue #42's reset-namespace split and `--include-sticky`** (top-level §18.9) — never shipped,
  stays a separate, still-open future issue. This spec neither depends on it nor completes it; see
  the note at the top of this document.
- Anything about the squelch engine (top-level §7 / Feature 3) — unaffected, was never in scope
  for #41 either.
- Handler-level targeting — stays exact-name-only, unaffected (the handler namespace was already
  flat and non-hierarchical; nothing here changes that).
- `logctl undo` (§14.5) — a different, still-deferred feature.
- Changing the grammar itself (what strings are valid patterns) — untouched; only what happens
  once a pattern resolves to a match set changes.

## Operations

### `setLevel(target, level, options)`

**Exact name:** entirely unchanged from today.

**Target containing a trailing `.*` (Decision #5):** rejected as a usage error before anything
else is evaluated — `IllegalArgumentException`/exit-2 usage error, not
`ConfirmationRequiredException`, since there is nothing to confirm: no matching, no capability
check, no mutation is even attempted. The message names the fix:

```
'org.apache.*': a trailing wildcard isn't accepted for a level-setting command. Every descendant
of 'org.apache' already inherits its level from the logging framework once 'org.apache' itself is
set — run 'logctl debug org.apache' instead.
```

This applies whenever the target's *trailing* segment is `*`, regardless of what precedes it —
`org.apache.*` and `*.apache.tomcat.*` (leading **and** trailing) are both rejected the same way;
only a target with **no** trailing star reaches the pattern-selection path below. (`reset`'s
identical-looking target is not rejected — see `resetLevel`, below, and Decision #5's discussion.)

**Target with a leading star and no trailing star (`*.Worker`, `*.apache.writer`):** a one-time
selection, resolved and applied in the same call, nothing left standing afterward.

1. Resolve every currently-known logger name the pattern matches (`listLoggers`'s own matcher —
   unchanged).
2. If `options.confirmed` is `false`, throw `ConfirmationRequiredException` carrying the resolved
   match list, exactly as today — kept (Decision #1).
3. Capability pre-flight against every currently-matched logger, all-or-nothing, before mutating
   any of them — unchanged in mechanism from today's pattern path (`checkSetLevelPermitted`), now
   with no precedence-filtering step in front of it: every match is a candidate, full stop (see
   "Precedence, retired" below).
4. For every currently-matched logger: create/replace its `LevelOverride` (no `originPattern`
   field — there is no rule to attribute it to), apply, one `MUTATION` audit record per logger,
   same as an exact-name `setLevel` call repeated N times.
5. Return a `SetLevelResult` whose `overrides()` has one entry per logger mutated in step 4, plus
   the union of blocking-handler warnings across all of them — unchanged from today.

Nothing is persisted beyond the individual `LevelOverride` entries created in step 4. There is no
step 5/6 analogous to `pattern-level-targeting.md`'s original spec (no `PatternRule` created, no
registry to commit it to).

### `resetLevel(target)`

**Exact name:** unchanged — reverts to baseline, no-op if not overridden.

**Pattern — a real behavior change from today's shipped `resetLevel`.** Today, a pattern target
is looked up by **exact string match** against `PatternRuleRegistry`: no rule tracked under that
exact string is a no-op, even if loggers matching it are currently overridden by some other
means. With `PatternRule` gone, there is nothing to look up — `resetLevel` on a pattern resolves
its *current* match set (same matcher as always) and reverts whichever of those loggers carry an
active `LevelOverride`, regardless of how that override came to exist. This also drops the
trailing-star/leading-star asymmetry `set` now has (Decision #5) — `reset` keeps full selection
semantics for both shapes, since a descendant under a trailing-star scope can carry its own
override (from an exact-name `set` run directly against it) that only a real scan finds.

Mechanically: resolve `target`'s current match set, and for each currently-known logger in that
set that carries an active `LevelOverride`, revert it — exactly the same operation `resetAll`
already performs over "every override," just filtered by the pattern instead of unconditionally.

### `resetAll()`

Unchanged in shape and behavior — it was never rule-aware beyond retiring every `PatternRule`
alongside every override, and that clause simply disappears along with the type it referred to.

## Precedence, retired

`pattern-level-targeting.md`'s "Precedence" section existed to answer two questions that no
longer arise:

- **"A logger with its own exact-name override, that also matches an active pattern"** — existed
  to protect a deliberately-set override from being silently reclaimed by a rule's sweep *after
  the fact*. There is no sweep and no rule, so nothing reclaims anything after the fact; the only
  way a pattern-based `set` ever touches a logger is the operator directly including it in a
  command they just ran. A pattern-based `set` therefore overwrites every one of its matches
  unconditionally, **including a logger that already carries its own override, of any tier** —
  the same way running `logctl trace a.b` a second time overwrites whatever `a.b` held before,
  with no special-casing. This is a real, visible behavior change from #41's shipped precedence
  rule (worth its own callout — see Decision #2) but falls directly out of "does exactly what
  typing each match individually would do": typing `logctl trace a.b` by hand has never checked
  what tier or origin `a.b`'s current override has before overwriting it, and a pattern that
  happens to include `a.b` shouldn't behave differently just because it was spelled as a pattern.
- **"A logger that matches more than one active pattern rule"** — existed only because two
  different standing rules could both persist and both keep trying to claim the same logger over
  time. With nothing persisted, this can't happen: two pattern-based `set` calls are two
  sequential, independent operations, and whichever ran more recently simply holds the current
  override, the same "last write wins" idiom every other repeated `setLevel` already follows.

## Confirmation and CLI behavior

Applies only to a **leading-star** target — a trailing-star target on `set` is rejected
(Operations, above) before confirmation ever enters the picture; there is nothing to preview or
confirm for a call that never mutates anything.

**Server-side gate** — mechanism unchanged, kept per Decision #1: `setLevel` on a leading-star
target with `options.confirmed == false` still doesn't mutate anything, still throws
`ConfirmationRequiredException` carrying the match list.

**`logctl`'s preview**, reworded to drop the standing-rule framing (compare
`pattern-level-targeting.md`'s original wording, which this replaces):

```
This will set ERROR on 3 currently-known loggers matching '*.deployment.scanner':
  org.jboss.as.server.deployment.scanner.FileSystemDeploymentService
  org.jboss.as.server.deployment.scanner.DeploymentScanner
  com.acme.deployment.scanner.Watcher
A logger created later that would also match this pattern is not affected — re-run this
command if you need it too.
Apply? [y/N]
```

`--yes` still skips the prompt; the non-interactive-without-`--yes` failure (Decision #4 of
`pattern-level-targeting.md`, unaffected) still applies, reworded the same way:

```
'*.deployment.scanner' matches 3 currently-known loggers. Pass --yes to apply ERROR to all of
them non-interactively (this affects only loggers that exist right now).
```

A trailing-star target never reaches this flow at all — `logctl debug org.apache.*` fails
immediately with the usage error from Operations, above, whether or not `--yes` was passed.

`logctl reset <pattern>` still prompts for nothing — unaffected either way this spec's open
decisions land, since reset was never the risk shape this section is about.

## Data model

```
LevelOverride {                                      — logaperture-api, changed
    loggerName, level, reason, appliedAt, source, tier, expiresAt   // unchanged
    originPattern: String?                            REMOVED — nothing to attribute an
                                                        override to; every override is now
                                                        indistinguishable in shape from one
                                                        set by an exact-name call
}

SetLevelOptions, SetLevelResult, ConfirmationRequiredException      — unchanged from
    `pattern-level-targeting.md`'s shapes (list-shaped `overrides`, the confirmation
    exception carrying `pattern`/`matches`) — this spec changes what produces them, not
    their shape.

ResetOutcome / ResetOutcomeData {                    — logaperture-api, changed
    revertedLoggerNames: List<String>                 unchanged
    patternRuleRetired: boolean                       REMOVED — nothing is ever "retired"
                                                        once there is no rule
}

PatternRule, PatternRuleRegistry                      — REMOVED ENTIRELY (logaperture-api,
                                                          logaperture-core)
```

`resetAll()` stays `void`, as shipped — no return-shape change; only its internal bookkeeping
(no more `PatternRule`s to retire) simplifies.

## Sweep integration

The periodic sweep tick (`sweepExpiredOverrides` + `verifyAndReapply`, driven by `SweepPolicy`)
loses its third pass entirely — `applyStandingRules` and everything it calls
(`applyRulesToUncoveredLoggers`, `expirePatternRule`) are deleted, not merely narrowed. The sweep
goes back to doing exactly two things: expiring `FOR`-tier overrides past their deadline, and
re-asserting a drifted override against a reconfiguration that fought it — both entirely
per-`LevelOverride`, with no notion of a pattern anywhere in either pass. No new thread, no new
scheduling primitive removed either — this is strictly a subtraction from what the sweep already
does.

`"pattern-sweep"` retires as an audit `source` value — every mutation via a pattern-based `set`
is now a live, in-session `"jmx"` record, indistinguishable from an exact-name call, because
that's exactly what it is.

## Persistence — state file schema

`schemaVersion` bumps (4 → 5) — additive in spirit (nothing about reading an `overrides:` row
changes) but the `patternRules:` top-level key disappears entirely:

```yaml
schemaVersion: 5
overrides:
  - loggerName: com.acme.batch.Worker
    level: DEBUG
    reason: "investigating slot exhaustion"
    appliedAt: 2026-08-21T03:14:02Z
    source: jmx
    tier: FOR
    expiresAt: 2026-08-21T03:44:02Z
handlerOverrides: []
```

A schema-version-4 file (with a `patternRules:` section) is still readable: `FileStateStore`
drops that key entirely on read rather than failing to parse it, and an `originPattern` field on
a legacy `overrides:` row is dropped the same way `includeChildren` was dropped from a
pre-slice-3 row (`pattern-level-targeting.md`'s own precedent). **Nothing about an already-live,
concretely-persisted `LevelOverride` is lost** by this — every logger a standing rule had already
matched and materialized an override for keeps that override, resumed exactly as any other
override is; only the *rule itself* (the part that would have kept reaching for new loggers) is
gone, which is precisely the behavior this spec retires on purpose. `schemaVersion` 1 through 4
are all still accepted on read for this reason; only a version the reader has never heard of is
the hard failure `FileStateStore` already raises today.

## Capability and audit

Unchanged in mechanism: every name a pattern-based `set`/`reset` actually touches is
capability-checked before mutating any of them, all-or-nothing; one `MUTATION`/`REVERSION` audit
record per logger actually touched, `source = "jmx"` for a live call. The only change is the
disappearance of `"pattern-sweep"` as a possible `source` value (Sweep integration, above) — there
is no longer a machine-initiated actor that creates an override on a pattern's behalf.

## CLI output

`reset <pattern>` (today's `Commands.resetPattern`) drops the "no standing rule was tracked under
that pattern; nothing to do." and "Standing rule '...' retired." lines entirely — there is no
rule to report on. It renders exactly like a scoped `resetAll`: one baseline-reversion line per
logger actually reverted, or a plain "nothing was overridden" when the match set has nothing
currently active:

```
org.apache.tomcat → INFO (baseline)
org.apache.tomcat.connector → INFO (baseline)
```

```
'org.apache.tomcat.*' — nothing was overridden.
```

`logctl debug org.apache.*` (the apply side) prints no confirmation line at all — it fails first:

```
'org.apache.*': a trailing wildcard isn't accepted for a level-setting command. Every descendant
of 'org.apache' already inherits its level from the logging framework once 'org.apache' itself is
set — run 'logctl debug org.apache' instead.
```

## Versioning

Removing `originPattern` from `LevelOverride`, dropping `patternRuleRetired` from
`ResetOutcome`/`ResetOutcomeData`, and dropping `PatternRule`/`PatternRuleRegistry` from the
public API are all breaking changes to `logaperture-api`/the MXBean surface. Pre-1.0, per
top-level §11.1, this is moot ("no skew guarantee before 1.0") — the same reasoning
`pattern-level-targeting.md` already invoked for its own breaking changes — but called out
explicitly here rather than left as an incidental diff, per that established convention.

## Testing

Per top-level §12, unit-first — largely **subtractive** relative to
`pattern-level-targeting.md`'s existing suite:

- Delete: every `LevelControlServiceTest`/`FileStateStoreTest` case that exercises
  `PatternRule`/`PatternRuleRegistry`/`applyStandingRules`/the exact-string `resetPattern` lookup
  directly, and every CLI test asserting the "Standing rule ... retired"/"no standing rule was
  tracked" renderings.
- Keep, adjusted: leading-star `setLevel` tests, asserting the *absence* of standing behavior
  instead of its presence — a logger added to the fake adapter *after* a pattern-based `set` ran
  is confirmed **not** picked up by a subsequent simulated sweep tick (the direct negative of
  `pattern-level-targeting.md`'s original assertion); a logger with its own pre-existing
  override, of any tier, is confirmed overwritten by a later pattern `set` that matches it
  (Precedence, retired, above).
- New: `resetLevel` on a pattern that isn't itself a tracked rule's exact string, but that
  currently matches an overridden logger, is confirmed to revert it — the direct positive of
  today's shipped no-op (Operations, above).
- New: `setLevel` on any target with a trailing `*` — `org.apache.*`, `*.apache.tomcat.*` —
  throws the usage error before matching, capability-checking, or mutating anything.
  `resetLevel`/`listLoggers` on the identical trailing-star string are asserted to work exactly as
  before — same test fixture, opposite expectation, to lock in the asymmetry (Decision #5).
- `FileStateStore`: round-trips a schema-5 file with no `patternRules:` key; reads a schema-4
  file (with one) without crashing and without resuming anything from it beyond the ordinary
  `overrides:` rows it also contains.
- `LevelControlEndToEndIT`/`WildFlyContainerIT`: re-run the scenario that motivated this spec —
  now spelled `logctl trace a` (not `a.*`, which the new `setLevel` rejects) — create a new
  descendant logger via traffic afterward, and confirm it is **not** overridden by LogAperture,
  only by framework inheritance, as the new exit-criterion proof, replacing the old "picked up
  within one sweep interval" assertion.

## Exit criterion

A leading-star `setLevel` (`confirmed = true`) applies to every currently-matching logger in a
real `java -jar` + Logback (and, separately, WildFly/JBoss LogManager) process; a logger created
afterward that would also match is confirmed **not** covered by it — no LogAperture-attributed
override or audit record for it. A trailing-star target on `setLevel` — `logctl debug
org.apache.*` — fails with the Decision #5 usage error and mutates nothing, in the same real
process. Separately, `logctl debug org.apache` (the literal ancestor, no star) is confirmed to
bring every current *and* subsequently-created descendant to the same effective level purely
through the framework's own inheritance, with no override or audit record on any of them but
`org.apache` itself. `resetLevel` on a trailing-star target reverts every currently-overridden
logger it matches — including one set directly by exact name under that scope, and regardless of
whether the pattern string itself was ever a tracked rule — and nothing more.

## Decisions

| # | Decision | Status |
|---|---|---|
| 1 | Does a pattern-based `set` still require confirmation/`--yes`, now that it's bounded and current rather than unbounded and future-reaching? | **Resolved** — keep confirmation; see discussion below |
| 2 | A pattern-based `set` now unconditionally overwrites a match's pre-existing override, of any tier or origin, with no precedence protection | **Resolved** — yes, falls out of the core principle |
| 3 | Legacy state file: a `patternRules:` section is dropped on read, not migrated to anything | **Resolved** — yes, no data is destroyed (each rule's already-materialized overrides are separate `overrides:` rows and are unaffected) |
| 4 | Leading-wildcard patterns lose standing-rule behavior along with trailing-wildcard, even though no framework guarantee makes it redundant for that shape | **Resolved** — decided explicitly in this issue's design discussion: a pattern is a selection convenience for both shapes, uniformly, "and nothing more" |
| 5 | A trailing-star target on `set`: reject as a usage error, or silently normalize to the literal ancestor name (`org.apache.*` → `org.apache`)? | **Resolved** — reject; see discussion below |

**Decision #1, discussion.** Resolved as keep confirmation. `pattern-level-targeting.md`'s
original rationale for prompting specifically contrasted apply ("unbounded, future-reaching")
against reset ("bounded, current state," which is why reset never prompts). That contrast is
gone — a pattern-based `set` is now exactly as bounded and current as a pattern-based `reset`.
The risk that's left is a narrower one: matching more (or different) loggers than the operator
expected, in a single batch mutation. That risk is real regardless of future-reaching-ness —
`logctl error *.deployment` matching forty loggers instead of the four the operator had in mind
is just as surprising whether or not any of it persists — so confirmation stays, reworded to drop
the now-inaccurate "standing rule" language (see "Confirmation and CLI behavior" above), rather
than dropped for symmetry with reset.

**Decision #2, discussion.** Resolved as yes. Falls directly out of "does exactly what typing
each match individually would do" (Precedence, retired, above): typing `logctl trace a.b` by hand
has never checked what tier or origin `a.b`'s current override has before overwriting it, and a
pattern that happens to include `a.b` shouldn't behave differently just because it was spelled as
a pattern. The same principle is what motivates `resetLevel`'s own generalization (Operations,
above) from an exact-string rule lookup to a real current-match resolution.

**Decision #3, discussion.** Resolved as yes. Pre-1.0, and no data is destroyed by dropping a
legacy `patternRules:` section on read — every logger a rule had already matched keeps its own
persisted `LevelOverride` row independently of the rule that once produced it; only the rule's
own "keep reaching for new loggers" behavior is gone, which is precisely what this spec retires.

**Decision #5, discussion.** Resolved as reject, not silently normalize. Both readings were
internally consistent with this spec's core principle, but they diverge exactly where it matters:
if `org.apache.tomcat` already carries its own hand-set `STICKY` override, `set org.apache.*`
silently normalized to `set org.apache` would leave `org.apache.tomcat` **untouched** — a real
difference from the old fan-out behavior, where the same command would have overwritten it
(Decision #2) — with nothing telling the operator their mental model of what the trailing star
does is out of date. Silent normalization was closer to the letter of "we just treat `trace a.*`
as `trace a`" from this issue's original framing, but it reintroduces exactly the kind of "typed
one thing, a different thing happened" gap this whole spec exists to close, just on the `set` side
instead of the sweep side. Rejecting costs the operator one glance at an error and a one-word
edit, and never leaves them wondering what actually happened — worth that cost for never being
silently surprising.

## Divergence from prior specs

Applied once sign-off is complete, ahead of implementation (same convention
`pattern-level-targeting.md` used for its own breaking changes):

- [`pattern-level-targeting.md`](pattern-level-targeting.md): slice 3 (standing-rule apply/sweep,
  `PatternRule`, "Precedence", the exact-string `resetLevel` lookup) marked "Superseded —
  retired; see `pattern-selection-semantics.md`." Slice 1 (grammar) and slice 2
  (`includeChildren`'s removal) are unaffected and stay as shipped.
- Top-level `doc/logaperture-spec.md` §18.7: a short forward-pointing note that the standing-rule
  mechanism it describes as shipped has since been retired in favor of this spec. §18.9: a note
  that the scoped-exclusion mechanism it once described was never shipped (PR #48 closed
  unmerged) and that §18.9's own reset-namespace-split proposal remains open, untouched by this
  spec.
- `handler-floor-control.md`, `persistence.md`: no change — neither ever depended on
  `PatternRule`, and neither has any shipped content from #42 to correct.
- `cli-transport.md`: the `--include-children` row's already-landed "Superseded (shipped)" note
  (from #41) gets a further update noting that no wildcard-based replacement exists either —
  `logctl debug org.apache` alone is what covers descendants now.

Full discussion: <https://claude.ai/artifact/RDZa983pzdCYpkcAsBh83o> — written before this
document's correction against #41's actual shipped baseline (rather than the unmerged #42
branch); the decisions and their resolutions are unaffected, only some Operations/Data-model
details described there don't match this file's final text.
