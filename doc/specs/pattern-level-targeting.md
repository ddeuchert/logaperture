# Pattern-based level targeting — apply and reset (issue #41, slices 2–3)

Status: implemented (2026-09-13).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.7 (roadmap entry), §5
(Feature 1 definition), §6.1 (persistence tiers), §9 (capability/audit model), §11.1
(component versioning).
Builds on: [`doc/specs/level-control.md`](level-control.md) (Feature 1 — the segment-anchored
glob grammar, slice 1 of this same issue, already shipped as `NameFilter`/`listLoggers`) and
[`doc/specs/persistence.md`](persistence.md) (Feature 2 — the override/expiry/persistence
tiers this reuses rather than reinventing).
Tracks: [issue #41](https://github.com/ddeuchert/logaperture/issues/41), slices 2 and 3,
taken together (see "Why slices 2 and 3 land in one spec" below).

## Functional summary

After this feature, the user will be able to:

- Set a level on every logger matching a pattern in one command, the same way `logctl levels`
  already looks them up: `logctl error *.deployment.scanner`.
- Have that pattern act as a **standing rule** — a logger that doesn't exist yet (not loaded,
  not yet instantiated) automatically gets the same level the moment LogAperture discovers it,
  with no need to re-run the command.
- See a preview of exactly which currently-known loggers a pattern command is about to affect,
  and confirm before it takes effect — or pass `--yes` to skip the prompt in a script.
- Retire a standing rule with `logctl reset *.deployment.scanner` — every logger it currently
  covers reverts to baseline, and it stops affecting loggers discovered later.
- Target a logger **and** its descendants with `logctl debug org.apache.*` in one command —
  the trailing-wildcard pattern replaces today's `--include-children` flag, which is removed,
  and (per the grammar correction in [issue #41](https://github.com/ddeuchert/logaperture/issues/41)'s
  PR #44) is a true superset of what that flag did, not descendants-only.
- Trust that a logger's own individually-set override is never silently overwritten by a
  standing rule that happens to also match it.
- Get the standing-rule glob support on `logctl reset <pattern>` and on the apply-side
  commands (`error`, `debug`, `set`, ...) — one grammar, one mechanism, everywhere a logger
  target is accepted.

## Why slices 2 and 3 land in one spec

Top-level §18.7 deliberately sequences these two slices of #41 so that neither ships without
the other: retiring `includeChildren` (slice 2) before the standing-rule apply mechanism
(slice 3) exists would leave a gap where **no** mechanism covers "this logger and its
descendants" — today's flag would be gone, and its replacement wouldn't exist yet. So this
spec, and the implementation it describes, treats them as one unit: `includeChildren`
disappears from the operations API in the same change that a trailing-wildcard pattern becomes
able to receive a level.

This spec also depends on a small, separate correction to the already-shipped slice 1 grammar:
[PR #44](https://github.com/ddeuchert/logaperture/pull/44) changes a leading/trailing `*` from
matching one-or-more further segments to zero-or-more, so `org.apache.*` matches `org.apache`
itself as well as its descendants — a true superset of `includeChildren`'s old behavior, not
an approximation of it requiring a second target. Everything below assumes that correction has
landed.

`doc/specs/level-control.md`'s "Superseded (planned, not this slice)" note on `includeChildren`
and top-level §18.7's own text are both updated by this spec landing (see "Divergence from
prior specs" at the end).

## Scope

**In scope:**

- `setLevel` (and its CLI forms `debug`/`trace`/`info`/`warn`/`error`/`set`) accepts either an
  exact logger name (unchanged) or a segment-anchored pattern (slice 1's grammar) as its target.
- A pattern target is a **standing rule**: persisted per the tier it's set at, and
  (re-)applied whenever the periodic sweep discovers a logger it matches that isn't already
  covered by something else.
- `resetLevel` (CLI `reset <target>`) accepts the same either/or target, and retiring a pattern
  reverts every logger it currently covers **and** stops it from applying to future ones.
- `resetAll` also retires every standing rule, not just per-logger overrides — the "get me back
  to normal" escape hatch covers both.
- A preview-and-confirm step before a pattern apply takes effect, with a non-interactive
  escape hatch (`--yes`).
- `includeChildren` removed from `SetLevelOptions`, `LevelControlOperations.setLevel`,
  `LevelControlMXBean.setLevel`, and `LevelOverride` — for every caller, JMX included, not just
  the CLI flag.
- Precedence rules for a logger matched by more than one active standing rule, and for a logger
  that also carries its own exact-name override.
- Persistence: a new `PatternRule` entity, its own registry, and a state-file schema addition
  (`schemaVersion` 3 → 4).
- Audit: a new `"pattern-sweep"` source for a rule catching a newly-discovered logger, alongside
  the existing sources for a live apply.

**Explicitly out of scope for this slice** (deferred):

- Everything top-level §7 / Feature 3 (the squelch engine) owns — no gate/render actions, no
  per-event counters, no burst detection. A standing rule is a flat "this pattern, this level,
  until reset," nothing evaluated per log event.
- The `reset logger`/`reset loggers`/`reset handler`/`reset handlers` command-surface split and
  `--ignore-sticky` (top-level §18.9, [issue #42](https://github.com/ddeuchert/logaperture/issues/42)).
  This spec's `reset <target>` stays the current one-argument `logctl reset <target>` /
  `logctl reset --all` shape; #42's restructuring, when it lands, reuses this slice's matcher
  exactly as its own spec already says, without needing this spec to anticipate its exact
  command spelling.
- Multiple targets in one call (`logctl error <a> <b>`). Top-level §18.7 originally raised
  this as a convenience for "self and descendants," but PR #44's zero-or-more correction
  resolves that specific case with a single pattern target (`org.apache.*` already covers
  both) — so this is no longer needed for the motivating reason and is dropped from this
  slice's scope entirely rather than merely deferred. See Decision #9.
- Handler-level targeting (`logctl handler <name> ...`) stays exact-name-only, unaffected —
  the handler namespace was already decided to be flat, non-hierarchical (top-level §18.9,
  `handler-floor-control.md`).

## Operations

### `setLevel(target, level, options)`

```
SetLevelResult setLevel(String target, Level level, SetLevelOptions options)
```

`target` is resolved exactly like `listLoggers(filter)`'s `filter` (`NameFilter`, slice 1):
no `*` means an exact logger name (today's behavior, unchanged); `*` present means the
segment-anchored pattern grammar, validated the same way and rejected with the same messages
on a malformed pattern.

**Exact name:** unchanged behavior. Creates or replaces the one `LevelOverride` for that
logger (today's idempotency rule), applies it, and returns it. `options.confirmed` is ignored
— an exact-name override cannot affect a logger that doesn't exist yet, so it carries none of
the "amplifying, indefinite" risk a standing rule does, and needs no confirmation regardless of
tier (a `--sticky` single-logger override doesn't prompt today; that doesn't change).

**Pattern:** a standing rule.

1. Resolve every currently-known logger name the pattern matches (the same matcher
   `listLoggers` uses).
2. If `options.confirmed` is `false`, the call does not mutate anything — it throws
   `ConfirmationRequiredException`, carrying the resolved match list, for the caller to render
   a preview and re-invoke with `confirmed = true` once the operator agrees (or `--yes` was
   already given). See Decision #2 for why this is a server-side gate and not CLI-only UX, and
   "CLI behavior" below for the actual prompt.
3. Capability pre-flight against **every currently-matched logger**, before mutating any of
   them — the same all-or-nothing discipline `includeChildren`'s fan-out used
   (`checkSetLevelPermitted`), now driven by the pattern's match set instead of
   `LoggerHierarchy.descendantsOf`. `PERSIST` is checked once in addition, if `tier != SESSION`.
4. For every currently-matched logger **except one that already carries an override not
   originating from a lower-precedence pattern rule** (see Precedence below): create/replace
   its `LevelOverride`, tagged with `originPattern = target`; apply; one `MUTATION` audit
   record per logger, `source` unchanged (`"jmx"` — this is a live, in-session call, exactly
   like any other `setLevel`).
5. Create or replace (idempotent by pattern string, same rule as per-logger overrides) a
   `PatternRule` for `target` in the new `PatternRuleRegistry`, and persist it per `tier`
   exactly as a `LevelOverride` is persisted today.
6. Return a `SetLevelResult` whose `overrides()` list has one entry per logger actually
   mutated in step 4 (an already-covered logger the rule skipped is not included), plus the
   union of blocking-handler warnings across all of them (today's per-target merge, generalized
   from "every `includeChildren` target" to "every pattern match").

### `resetLevel(target)`

**Exact name:** unchanged — reverts to baseline, no-op if not overridden.

**Pattern:** looked up by exact string match against `PatternRuleRegistry` (Decision #5), not
by recomputing which loggers currently match:

- No rule tracked under that exact pattern string: no-op, not an error — same convention as
  resetting a logger with no active override.
- A tracked rule: revert every `LevelOverride` whose `originPattern` equals it (compare-and-
  remove against the current registry entry, same race discipline `applyReset` already uses),
  one `REVERSION` audit record per reverted logger, then remove the `PatternRule` itself. A
  logger the rule *would* match but that currently carries a different, higher-precedence
  override (see Precedence) is left alone — it was never covered by this rule in the first
  place.

### `resetAll()`

Unchanged in shape, expanded in effect: reverts every `LevelOverride` (as today) **and**
retires every `PatternRule` — a `REVERSION` record per reverted logger, no separate record for
each retired rule (its retirement is fully evidenced by the reversions it caused, and by the
state file no longer listing it).

## Precedence

Two situations, both raised as open questions in issue #41 and resolved here:

**A logger with its own exact-name override, that also matches an active pattern.** The
exact-name override always wins, and the sweep never overwrites it: `setLevel` on a pattern
skips a currently-matched logger that already carries an override whose `originPattern` is
`null` (Operations, step 4, above) or that belongs to a strictly newer pattern rule (below).
The sweep (see "Sweep" below) applies a rule only to a logger with **no** active override at
all. If that exact-name override is later reset, the logger becomes eligible again on the next
sweep tick, and a still-active matching rule reclaims it — the rule fills a gap, it does not
fight a standing decision.

**A logger that matches more than one active pattern rule (Decision #1).** The
**most-recently created-or-updated** rule wins — the same "last write wins" idiom the project
already uses for a single logger's override (`setLevel` called twice replaces the earlier
entry) and for precedence generally (top-level §6.6, item 1: "runtime mutations... win over
persisted state"). Concretely: creating/updating a pattern rule re-applies it to every logger
it currently matches, even one already covered by an older rule (Operations step 4's "lower-
precedence pattern rule" clause) — the newer rule's `appliedAt` is later, so it wins
immediately, not just for loggers discovered from that point on. The sweep, when a
newly-discovered logger matches more than one active rule, applies the one with the latest
`appliedAt`.

This is a simple, total order (rules are never "equally new") and requires no notion of one
pattern being "more specific" than another, which — unlike file-glob specificity — is not
well-defined in general for this grammar (`org.apache.*` and `*.writer` neither contains nor
is contained by the other).

## Confirmation and CLI behavior

**Server-side gate.** `setLevel` on a pattern with `options.confirmed == false` never mutates
anything (Operations, step 2) — this holds for every caller, not just `logctl`, per top-level
§8.1's "no surface is a privileged path." A `jconsole` operator manually invoking `setLevel`
with a pattern target and `confirmed = false` gets `ConfirmationRequiredException` back,
listing the matches; they must explicitly pass `confirmed = true` to proceed, the same
deliberate step `logctl`'s prompt embodies. See Decision #2.

**`logctl`'s flow**, layering on top of the server gate:

1. Detect a pattern target the same way the CLI already detects one for `logctl levels` (a
   `*` in the argument) — no new client-side grammar.
2. Call `listLoggers(target)` first (exactly as `logctl reset <logger>` already does, per
   `cli-transport.md`, "reads `listLoggers` to render its confirmation") to render the preview
   without needing the `ConfirmationRequiredException` round trip in the common case.
3. Print the preview:

   ```
   This will set ERROR on 3 currently-known loggers matching '*.deployment.scanner':
     org.jboss.as.server.deployment.scanner.FileSystemDeploymentService
     org.jboss.as.server.deployment.scanner.DeploymentScanner
     com.acme.deployment.scanner.Watcher
   Because this is a pattern, LogAperture will also apply ERROR to any new logger
   discovered later that matches '*.deployment.scanner', until you run
   'logctl reset *.deployment.scanner'.
   Apply this standing rule? [y/N]
   ```

4. `--yes` skips the prompt and calls `setLevel` with `confirmed = true` directly. Without it,
   on a non-interactive invocation (no controlling terminal — same check a real confirmation
   prompt anywhere needs), `logctl` fails with a usage error naming `--yes` rather than hanging
   on a read from a stdin nothing will ever write to (Decision #4).
5. On "y", call `setLevel` again with `confirmed = true`.

`logctl` itself never triggers `ConfirmationRequiredException` in normal operation — its own
`setLevel` call always carries `confirmed = true` by the time it's made (step 4 or 5). `Main`
still catches it explicitly anyway, alongside its existing `CapabilityDeniedException`/
`IllegalArgumentException` catches, and prints its message plainly rather than falling through
to the generic unexpected-failure path — belt-and-suspenders for a race between the preview
and the apply call (a match set that changed in between) or a future bug, not something this
slice's tests need to provoke deliberately.

`logctl reset <pattern>` prompts for nothing — matching the project's existing asymmetry
(reset reverts a bounded, current state; apply creates an unbounded, future-reaching one) and
`logctl reset --all`'s existing no-prompt convention.

## Data model

```
PatternRule {                                       — logaperture-api, new
    pattern: String            // the segment-anchored pattern, verbatim
    level: Level
    reason: String?
    appliedAt: Instant
    source: String             // "jmx" — same convention as LevelOverride
    tier: PersistenceTier
    expiresAt: Instant?        // non-null iff tier == FOR, same rule as LevelOverride
}

LevelOverride {                                      — logaperture-api, changed
    loggerName, level, reason, appliedAt, source, tier, expiresAt   // unchanged
    includeChildren: boolean                          REMOVED
    originPattern: String?                            NEW — null for a directly-set override;
                                                        otherwise the PatternRule.pattern that
                                                        produced it
}

SetLevelOptions {                                    — logaperture-api, changed
    reason, expiresIn, tier                           unchanged
    includeChildren: boolean                          REMOVED
    confirmed: boolean = false                        NEW — see Confirmation, above
}

SetLevelResult {                                     — logaperture-api, changed
    override: LevelOverride                           REMOVED
    overrides: List<LevelOverride>                    NEW — one entry for an exact-name call,
                                                        0..N for a pattern (see Decision #7)
    blockingHandlers: List<HandlerFloor>               unchanged in meaning, now a union across
                                                        every entry in `overrides`
}

ConfirmationRequiredException                        — logaperture-core, new
    pattern: String
    matches: List<String>       // currently-known logger names the pattern resolved to
    // getMessage() renders both fields as readable text (Decision #2a), e.g.:
    // "3 matches for '*.deployment.scanner': [a, b, c]. Re-invoke with confirmed=true
    //  to apply this standing rule." -- so even a raw jconsole invocation, with no
    //  typed client to catch the exception, gets an actionable message rather than
    //  an opaque failure.
```

### `LevelControlOperations` / `LevelControlMXBean`

```java
SetLevelResult setLevel(String target, Level level, SetLevelOptions options);
void resetLevel(String target);
void resetAll();
```

MXBean wire signature (breaking — see "Versioning" below):

```java
SetLevelResultData setLevel(
    String target, String level, String reason, String tier, long forSeconds, boolean confirmed);
```

`includeChildren` is gone from the parameter list entirely, not defaulted or ignored.
`SetLevelResultData` gains only a list-shaped `overrides` field (renamed from the singular
`override`) — nothing more. **Decision #2a, resolved:** `ConfirmationRequiredException`
crosses the JMX boundary the same way `CapabilityDeniedException` already does today (as a
`RuntimeMBeanException`, unwrapped back to itself by `JMX.newMXBeanProxy` for a typed caller —
`logaperture-cli`'s own `Main.java` fix for exactly this unwrapping behavior is what this
relies on) — not as a "successful" return value carrying a `confirmationRequired` flag.
`SetLevelResultData` therefore only ever describes a mutation that actually happened; there is
no return shape a careless caller could mistake for one.

Rejected the return-value alternative for consistency: every other refusal in this API
(`CapabilityDeniedException`, an invalid filter's `IllegalArgumentException`,
`UnknownHandlerException`) is already an exception, not a flagged return value, and an
uncaught exception fails loudly by default where a wrong assumption about an unchecked return
field fails silently. The JMX-marshaling concern that originally motivated considering a
return value doesn't hold up in practice — this project's own `IllegalArgumentException` and
`CapabilityDeniedException` already cross a real `JMX.newMXBeanProxy` connection intact.

## Versioning

Per top-level §11.1: removing `includeChildren` and reshaping `SetLevelResult`/
`SetLevelResultData` are both changes "not permitted until the next major" under the additive-
only rule that applies **from 1.0 onward**. Pre-1.0, §11.1 is explicit that there is no skew
guarantee at all ("the only supported pairing pre-1.0 is an agent and a `logctl` from the same
build") — so this is allowed outright today, the same reasoning issue #41's own follow-up
comment already gave. Worth stating here rather than only in the issue thread, since this spec
is what a future reader checks before assuming §11.1's additive-only rule was violated.

## Sweep integration

Extends the existing sweep tick (`NoneContainer`/`WildFlyContainer`'s `sweepTick`, currently
`sweepExpiredOverrides` + `verifyAndReapply`) with a third pass, `applyStandingRules(now)`:

1. Snapshot `adapter.knownLoggerNames()` and the active `PatternRule`s (ordered by `appliedAt`
   descending — newest first, per Precedence).
2. For each known logger name with **no** active `LevelOverride` at all: find the first
   (newest) active rule that matches it. If one exists, apply + commit + one `MUTATION` audit
   record, `source = "pattern-sweep"` (a new, machine-initiated source, parallel to
   `"expiry-sweep"`/`"verification-sweep"`).
3. `FOR`-tier `PatternRule` expiry reuses `resetLevel`'s pattern path exactly (revert every
   override it produced, remove the rule) the moment `expiresAt` passes, checked in the same
   tick as the existing per-override expiry check.

This is architecturally the same "reuse the sweep thread that already exists" the roadmap
promised — no new thread, no new scheduling primitive.

## Persistence — state file schema

`schemaVersion` 3 → 4, additive (3, not 1, since `handler-floor-control.md`'s `handlerOverrides:`
section and AUTO-mode `mode:` field already moved this file's schema twice before this slice):

```yaml
schemaVersion: 4
overrides:
  - loggerName: com.acme.batch.Worker
    level: DEBUG
    originPattern: null          # was includeChildren: false
    reason: "investigating slot exhaustion"
    appliedAt: 2026-08-21T03:14:02Z
    source: jmx
    tier: FOR
    expiresAt: 2026-08-21T03:44:02Z
handlerOverrides: []
patternRules:
  - pattern: "*.deployment.scanner"
    level: ERROR
    reason: "known noisy on redeploy"
    appliedAt: 2026-09-13T12:00:00Z
    source: jmx
    tier: STICKY
    expiresAt: null
```

A schema-version-1/2/3 file (no `patternRules` key, `includeChildren` instead of
`originPattern` on each override) is still readable: `FileStateStore` treats a missing
`patternRules` key as an empty list, and drops the now-meaningless `includeChildren` field
from a legacy row rather than failing to parse it — a pre-1.0 alpha user's existing state
file must not brick their next restart. `schemaVersion` 1, 2, and 3 are all still accepted
on read for this reason; only a version the reader has never heard of (0, or a future 5) is
the hard failure `FileStateStore` already raises today.

## Capability and audit

- Same capabilities as today (`VIEW`, `LEVEL_RAISE`, `LEVEL_LOWER`, `PERSIST`) — a pattern
  apply's capability check is the existing per-target logic, run against the pattern's match
  set instead of `includeChildren`'s descendant set. No new capability for "creating a standing
  rule" as such — the risk it carries (raising/lowering N loggers, some not chosen individually)
  is already exactly what `LEVEL_RAISE`/`LEVEL_LOWER` gate, evaluated per match.
- New audit source: `"pattern-sweep"` (Sweep integration, above).
- A pattern's `resetLevel` writes one `REVERSION` per logger it actually reverted — no separate
  record type for "rule retired"; the reversions plus the state file's own contents are the
  evidence.

## Testing

Per top-level §12, unit-first:

- `setLevel`/`resetLevel` with a pattern target, against `LevelControlServiceTest`'s existing
  fake adapter: creates one override per current match, tagged with `originPattern`; a
  logger added to the adapter *after* the rule exists is picked up on the next simulated sweep
  tick, not before.
- Precedence: a logger matching two active rules gets the newer rule's level; creating a newer
  rule overwrites a logger already covered by an older one; an exact-name override is never
  overwritten by any rule, and becomes eligible again once that override is reset.
- `ConfirmationRequiredException`: thrown with the correct match list when `confirmed` is
  `false`; no mutation occurs; a second call with `confirmed = true` proceeds.
- `resetLevel` on a pattern with no tracked rule is a no-op; on a tracked rule, reverts exactly
  the loggers it produced and removes the rule; `resetAll` also clears every rule.
- Capability denial blocks a pattern apply against every match, atomically, mirroring today's
  `includeChildren` fan-out tests.
- Sweep: `applyStandingRules` picks up a newly-discovered logger exactly once, respects
  precedence among active rules, and a `FOR`-tier rule's expiry reverts its matches and removes
  itself.
- `FileStateStore`: round-trips a `PatternRule`; reads a `schemaVersion: 1` (or 2, or 3) file
  with no `patternRules` key and no crash; a legacy `includeChildren` field on an override row
  is ignored, not rejected.
- CLI (`CommandsTest`, stubbed transport): the preview lists matches and states the standing-
  rule consequence; `--yes` suppresses the prompt; a non-interactive invocation without
  `--yes` exits with a usage error instead of blocking on stdin.
- `LevelControlEndToEndIT`: a pattern-targeted `setLevel` with `confirmed = true` survives the
  real JMX boundary and a logger added to the fixture after the rule is set is picked up by a
  real sweep tick.

## Exit criterion

A pattern-targeted `setLevel` (`confirmed = true`) applies to every currently-matching logger
in a real `java -jar` + Logback process, a logger added afterward is picked up within one sweep
interval without any further command, and `resetLevel` on the same pattern reverts every
currently-covered logger and the rule stops applying to loggers added after that —
`LevelControlEndToEndIT` proves this over the real JMX boundary, `confirmed = false`'s
`ConfirmationRequiredException` included. `logctl debug org.apache.*` demonstrates the
`includeChildren` replacement with no `--include-children` flag anywhere in the CLI or the
operations API — `CommandsTest` proves the CLI's preview/confirm/`--yes` behavior against a
fake transport. (Not separately exercised: the real `logctl` binary process, invoked with a
pattern target, against a real `LevelControlService` — `CliEndToEndIT`'s own fixture stubs the
operations layer, per that module's own docstring, "the CLI's transport is what's under test,
not the engine"; the CLI-behavior half and the engine-plus-JMX half are each covered for real,
just not combined into one test.)

## Decisions

All ten numbered decisions this spec's text above assumes answers to are settled, as proposed:

| # | Decision | Settled as |
|---|---|---|
| 1 | Precedence among overlapping patterns | Newest rule (`appliedAt`) wins |
| 2 | Confirmation as a server-side `confirmed` parameter, not CLI-only UX | Yes |
| 2a | How a JMX caller reads "not yet confirmed" | `ConfirmationRequiredException`, same as `CapabilityDeniedException` — no return-value alternative |
| 3 | Confirmation applies at every tier, including `--session` | Yes |
| 4 | Non-interactive invocation without `--yes` | Fails loudly, exit 2 |
| 5 | Reset-by-pattern matches by exact string, not by re-resolving | Yes |
| 6 | `resetAll` also retires every standing rule | Yes |
| 7 | `SetLevelResult` becomes list-shaped (`overrides`, not `override`) | Yes, breaking, accepted pre-1.0 |
| 8 | A standing rule can be set at any tier (`SESSION`/`FOR`/`STICKY`) | Yes |
| 9 | Multiple targets in one call | Dropped — motivating case resolved by [PR #44](https://github.com/ddeuchert/logaperture/pull/44)'s zero-or-more wildcard |
| 10 | State-file `schemaVersion` bump, backward-read | Yes (3 → 4, not 1 → 2 as first drafted — the file was already at 3) |

Full discussion: <https://claude.ai/code/artifact/f348e62a-31a9-4afb-9b4c-6180d5e03c83>.

## Divergence from prior specs

Applied now that sign-off is complete, ahead of implementation:

- `doc/specs/level-control.md`'s `includeChildren` row's "Superseded (planned, not this slice)"
  note is updated to "Superseded — removed; see `pattern-level-targeting.md`."
- Top-level `doc/logaperture-spec.md` §18.7 already links here (added when this draft was
  first published); no further change needed now that it's signed off rather than in review.
