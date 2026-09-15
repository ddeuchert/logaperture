# Reset command surface: logger/handler split + `--include-sticky` (issue #42)

Status: implemented (2026-09-14).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.9 (roadmap entry), §6.1
(persistence tiers), §9 (capability/audit model), §11.1 (component versioning).
Builds on: [`doc/specs/pattern-level-targeting.md`](pattern-level-targeting.md) (the glob
matcher and `PatternRuleRegistry` this reuses rather than rebuilding — issue #41's already-shipped
`resetLevel(target)`/`ResetOutcomeData`), [`doc/specs/handler-floor-control.md`](handler-floor-control.md)
(the handler namespace and its `ALL_HANDLERS` per-handler-restore semantics), and
[`doc/specs/persistence.md`](persistence.md) (the `STICKY` tier this adds a reset-time filter
over).
Tracks: [issue #42](https://github.com/ddeuchert/logaperture/issues/42).

## Functional summary

After this feature, the user will be able to:

- Reset one logger, or every logger matching a glob, without touching any handler override:
  `logctl reset logger com.acme.batch.Worker`, `logctl reset logger *.deployment.scanner`.
- Reset every logger override in one command, handlers untouched: `logctl reset loggers`.
- Reset one handler by exact name: `logctl reset handler CONSOLE` — replaces today's `logctl
  handler CONSOLE reset`.
- Reset every handler override in one command, loggers untouched: `logctl reset handlers`.
- Trust that a routine or broad reset leaves a deliberately-set `--sticky` override alone by
  default, on every form above — and reach for `--include-sticky` on any of them when a sticky
  override genuinely needs to go too.
- Reset just the part of a standing rule's reach that needs to change — one logger, or a
  narrower pattern under it — without retiring the whole rule or needing to remember which
  command originally set it up: point `reset logger` at whatever `logctl status` shows, and
  leave the rest of the rule's coverage exactly as it was, still applying to loggers discovered
  later too.
- Keep using `logctl reset --all` as the "get me back to normal, everything, both namespaces"
  escape hatch.

## Scope

**In scope:**

- The four namespace-scoped `reset` subcommands above, and the parser/help-text changes they
  need.
- `--include-sticky` on every form, changing reset's default from "revert regardless of tier" to
  "revert everything except `STICKY`-tier overrides, unless told otherwise." Named
  `--include-sticky`, not the issue's original `--ignore-sticky` — since the *default* is already
  to ignore/skip sticky entries, a flag called `--ignore-sticky` would read as reinforcing that
  default rather than overriding it (a double-negative trap: "ignore" is what already happens to
  sticky without the flag). `--include-sticky` also matches the existing `--include-children`
  naming shape (`cli-transport.md`) rather than inventing a new one.
- Retiring `logctl handler <name> reset` in favor of `logctl reset handler <name>` — a deliberate
  breaking rename, not a synonym kept alongside it (pre-1.0, per top-level §11.1).
- New/changed MXBean operations backing the above (see Operations).
- **Scoped exclusions against a standing rule** — resetting a target narrower than an entire
  pattern rule's own coverage carves that target out of the rule going forward, rather than
  either retiring the whole rule or being a no-op (see "Partial reset — scoped exclusions").
  This revises `pattern-level-targeting.md`'s `resetLevel(target)` pattern-lookup behavior
  (issue #41, Decision #5) and its sweep step 2 — both get their own "Superseded (planned)"
  notes alongside the others below.
- Updating the "Superseded (planned)" forward-pointing notes already left in
  `cli-transport.md`, `handler-floor-control.md`, and `persistence.md` (see "Cross-reference
  updates" at the end) to describe the shipped behavior instead of the plan.

**Explicitly out of scope:**

- The glob-matching mechanics themselves, and the standing-rule apply/sweep machinery — both
  belong to and already shipped under #41 ([`pattern-level-targeting.md`](pattern-level-targeting.md)).
  This spec's `reset logger <pattern>` is a command-spelling change over that existing
  `resetLevel(target)`, not a new matcher.
- Glob support on `reset handler`/`reset handlers` — stays exact-name-only; the handler
  namespace is flat and short, not deeply-nested the way logger categories are (top-level §18.9).
- `logctl undo` (§14.5) — a different feature (revert to previous value, not baseline).
- Any change to `setLevel`/`setHandlerLevel` (the *apply* side). This item is reset-only.

## Command grammar

```
logctl reset logger <name-or-pattern> [--include-sticky] [--reason ...]
logctl reset loggers [--include-sticky]
logctl reset handler <name> [--include-sticky]
logctl reset handlers [--include-sticky]
logctl reset --all [--include-sticky]
```

Space-separated subcommands under `reset`, per the phone test (`cli-transport.md`, "The phone
test, enforced") — the same reason today's commands are spelled as words rather than run
together. `logctl reset com.acme.batch.Worker` (today's bare, keyword-less form) is retired
alongside `logctl handler <name> reset` — see Decision #4; every reset now names its namespace
explicitly.

`--reason` on `reset logger` follows the same rule the apply-side commands already use (recorded
on the audit entries for whatever it reverts); the broad forms (`loggers`, `handlers`, `--all`)
don't take `--reason` — a routine broad reset has no single logger/handler to attach one reason
to, consistent with `resetAll` not taking one today.

## Operations

All operations below stay on `LevelControlMXBean` — the existing single interface both logger
and handler control already share.

```java
ResetOutcomeData resetLevel(String target, boolean includeSticky);   // new overload
ResetOutcomeData resetAllLoggers(boolean includeSticky);              // new
ResetOutcomeData resetAllHandlers(boolean includeSticky);             // new
ResetOutcomeData resetHandler(String handlerRef, boolean includeSticky); // new overload, was void
ResetOutcomeData resetAll(boolean includeSticky);                     // new overload
```

The existing zero/one-arg overloads (`resetLevel(target)`, `resetHandler(handlerRef)`,
`resetAll()`) are kept as additive per top-level §11.1 ("a new *optional* parameter... a new
overload"), each delegating to its new sibling with `includeSticky = false` for wire-compatibility
— **but this changes what that existing call now does**, since the sticky-skip is a new default,
not a new opt-in. That's a behavioral break riding on a signature that stays additive; §11.1's
compatibility promise is explicitly moot pre-1.0 ("no skew guarantee before 1.0... takes effect at
1.0.0"), so nothing here blocks the change — flagged explicitly per the same convention top-level
§18.7 used for `includeChildren`'s retirement, rather than left as an incidental side effect.

**`resetLevel`/`resetHandler` become outcome-returning everywhere, not void.** `resetHandler`
is `void` today; a plain broad reset that silently skips sticky entries needs to report what it
skipped as much as what it reverted, or a script has no way to tell "nothing was overridden" from
"something was overridden but protected." This is the same lesson already learned and written
down for the logger side (`Commands.java`'s comment on `resetPattern`: diffing `listLoggers`
before/after "was racy against concurrent mutation" and "had no way to tell" a no-op from a
real skip) — reusing `ResetOutcomeData` rather than inventing a parallel `void` + CLI-side-diff
path for handlers avoids repeating that mistake.

**`ResetOutcomeData` gains a skipped-sticky field, and its pattern field becomes list-shaped**,
reused across all five operations:

```java
List<String> revertedNames;      // loggers, or handlers, reverted by this call
List<String> retiredPatterns;    // pattern strings of any rule(s) fully retired by this call
List<String> excludedFrom;       // pattern strings of any rule(s) this call carved target out of
List<String> skippedStickyNames; // reverted-eligible but left alone because STICKY and !includeSticky
```

`patternRuleRetired: boolean` (#41's shape) becomes `retiredPatterns` because a single `reset
logger <target>` call can now affect more than one rule at once (Decision #2) — a boolean can't
say "retired this one, merely excluded from that one." `excludedFrom` is new: empty for an
exact-name target with no covering rule, for a full-rule retirement (nothing partial about it),
or for a non-pattern-adjacent reset entirely. `skippedStickyNames` is empty whenever `includeSticky
= true`, or the target had no sticky entries to begin with — a plain, no-`--include-sticky` reset
with nothing sticky in scope looks exactly as it does today.

## Partial reset — scoped exclusions

This is the resolution of Decision #2. `resetLevel(target)`'s pattern path (`pattern-level-
targeting.md`, shipped under #41) looks up the `PatternRuleRegistry` by **exact string match**:
`logctl reset logger org.apache.*` only does anything if a rule was registered under exactly
`"org.apache.*"`. That works for retiring a rule wholesale, but has no answer for resetting *part*
of what a rule covers — `logctl reset logger org.apache.tomcat` (an exact name inside a live
`org.apache.*` rule) finds no rule under that string and is a no-op today; worse, even a plain
per-logger revert of that name (going through the ordinary exact-name path, not the pattern path)
only lasts until the next sweep tick, which reapplies the still-active rule to any known,
now-override-less logger it matches — there's no way today to durably say "stop this rule from
covering just this one thing."

**The fix: `PatternRule` gains an exclusion set**, using the same `NameFilter` grammar as the
rule's own pattern — each entry is either an exact name or a sub-pattern:

```java
List<String> exclusions;   // NameFilter-grammar entries, same grammar as PatternRule.pattern
```

**Persistence.** `exclusions` is written and read alongside the rest of each `patternRules` entry
(`pattern-level-targeting.md`'s state-file schema); a legacy row with no `exclusions` key reads as
an empty list, the same tolerant-read convention already used for a missing `patternRules` key
entirely. `schemaVersion` moves 4 → 5 for the addition, matching this project's practice of
bumping on every shape change even when the change itself is read-tolerant (the same reasoning
`pattern-level-targeting.md` gave for its own 3 → 4 bump).

**One algorithm, not a special case for full vs. partial.** `resetLevel(target)` no longer keys
off exact-string identity against the registry. Instead:

1. Resolve `target`'s own match set (same matcher as always — one name for an exact target, the
   current matches for a pattern target).
2. For each currently-known logger in that set, find whichever active `PatternRule` currently
   owns it (today's existing precedence resolution — newest `appliedAt` wins on an overlap; see
   `pattern-level-targeting.md`'s Precedence section, unchanged). A logger with no covering rule
   just goes through the ordinary revert path, exactly as today.
3. Revert the current `LevelOverride` on every logger in `target`'s match set, regardless of which
   rule (if any) covers it — unchanged from today's behavior.
4. Group the affected loggers by covering rule. For each rule: if `target`'s scope, together with
   that rule's *existing* exclusions, now accounts for the rule's **entire** original coverage,
   the rule has nothing left to do — remove it outright (its old exclusions go with it; there's no
   rule left for them to qualify). Otherwise, add `target` (or the sub-pattern/name naming
   precisely what fell under that rule) to that rule's exclusion set and leave the rule active for
   everything else.

`logctl reset logger org.apache.*` against the rule that created it is just the case where step 4
finds nothing left over — full retirement, unchanged from #41's behavior and unchanged spelling.
`logctl reset logger org.apache.tomcat` against that same rule is the general case — reverts
`org.apache.tomcat`, leaves the rule active for everything else (including `org.apache.tomcat`'s
own descendants, present and future — the exclusion is exactly the literal name, nothing more).
`logctl reset logger org.apache.tomcat.*` excludes that name **and** its whole subtree, present and
future, the same way the pattern grammar's zero-or-more semantics already work on the apply side.

**Exclusions are scoped to the rule instance, not the pattern string.** If `org.apache.*` is fully
retired and later re-applied (`logctl error org.apache.* sticky` again), that is a brand-new
`PatternRule` with no memory of the old one's exclusions — it reapplies to everything it currently
matches, carve-outs included. This matches the project's existing "recreate = last write wins"
idiom (`pattern-level-targeting.md`'s Precedence section) rather than inventing a second one that
makes exclusions immortal across unrelated rule generations.

**A target can span more than one active rule in one call.** If different loggers under `target`
fall to different currently-winning rules (an overlap case the precedence rules already
anticipate), step 4 above runs once per affected rule — one `reset logger <target>` can retire one
rule and merely exclude `target` from another in the same call. No special-casing: it falls out of
running step 4 per rule rather than once globally.

**Sweep step 2 gets one added check.** `pattern-level-targeting.md`'s sweep currently reapplies the
newest matching rule to any known, override-less logger. It now also skips a logger the winning
rule's own `exclusions` covers — the identical `NameFilter` match test, just run as a negative
filter instead of the rule's own positive one.

**Why the user never needs to know which command created the coverage.** `reset logger <target>`
always does the right thing for whatever currently governs `target` — a rule, a plain override, or
nothing — without the caller needing to know which. This matches `reset`'s existing job description
(`cli-transport.md`'s framing of the deferred `logctl undo`: reset gets you back to baseline without
requiring you to reconstruct history; `undo`, not built yet, is the one that would need to know what
happened before).

## Capability and audit

Unchanged from today's simplification (`LevelControlService`, "every reset requires
LEVEL_LOWER" — the handler side requires `HANDLER_LOWER` the same way `resetHandler` already
does): every name a broad reset actually reverts is capability-checked before mutating any of
them, all-or-nothing, the same discipline pattern-apply already uses for its fan-out. A name
skipped for being sticky is never capability-checked — nothing is attempted against it, so
there's nothing to deny.

One `REVERSION` audit record per logger/handler actually reverted, `source = "jmx"`, unchanged
shape. Nothing is recorded for a sticky-skipped name — it wasn't touched, so there's no
reversion to record; `skippedStickyNames` in the return value (and the CLI's summary line) is
the only place that information surfaces. `resetLevel` on a pattern that itself retires a
`STICKY`-tier `PatternRule` under `--include-sticky` records that retirement exactly as today's
already-shipped pattern reset does. A partial carve-out (Decision #2) gets no separate audit
record either — same convention as full retirement (`pattern-level-targeting.md`: "no separate
record type for 'rule retired'"): the `REVERSION` record for whatever was actually reverted, plus
the state file's `exclusions` entry, are the evidence.

## CLI output

`reset logger`/`reset handler` (single target) keep today's three-outcome rendering
(`cli-transport.md`, "`logctl reset <logger>` and `logctl reset --all`") and pattern-reset
rendering (`Commands.resetPattern`) unchanged, with one addition: a target left alone for being
sticky prints its own line instead of silence —

```
com.acme.payments — left untouched (STICKY; use --include-sticky to include it).
```

The broad forms (`reset loggers`, `reset handlers`, `reset --all`) print a summary, not a
per-name confirmation line (consistent with `resetAll`'s existing `Reverted N override(s).`):

```
Reverted 4 logger override(s). 1 sticky override left untouched (use --include-sticky).
```

omitting the second sentence entirely when nothing was skipped. `--json` emits the
`ResetOutcomeData` shape directly: `{"reverted": [...], "skippedSticky": [...]}` (naming
pending — see Json.java's existing `revertedCount`/`resetPattern` helpers for the established
style to match).

**Partial reset of a standing rule** prints what it reverted, then names what changed about the
rule rather than silence — full retirement (unchanged from #41):

```
org.apache.tomcat → INFO (baseline)
org.apache.tomcat.connector → INFO (baseline)
Standing rule 'org.apache.*' retired.
```

versus a carve-out that leaves the rule active for everything else:

```
org.apache.tomcat → INFO (baseline)
Excluded 'org.apache.tomcat' from standing rule 'org.apache.*' — its own descendants, and any
logger discovered under it later, keep inheriting the rule.
```

**Marking a rule-governed override on `status`/`levels` (Decision #6 — RESOLVED).** Today's
rendering gives no way to see, from the output alone, that an override came from a live standing
rule (and will therefore keep propagating to newly-discovered descendants) rather than a one-off
`setLevel`. The first proposal named the governing pattern (`[org.apache.*]`) — rejected: that's
exactly the provenance-recall the whole point of Decision #2 was to avoid. The marker only needs
to say **that** it cascades, never **which rule** is doing it — the user never needs to know that
to act on it (`reset logger <target>` works the same either way):

```
org.apache.tomcat.connector    DEBUG   STICKY   until reset   "known-noisy"   (cascading)
```

`(cascading)` is a plain boolean — `originPattern != null` server-side — never the pattern string
itself. `--json` gets the same boolean, a new `cascading` field on the override shape `listLoggers`
already returns (additive, top-level §11.1); the pattern string stays internal, not part of any
public surface. Table rendering: a `CASCADE` column, shown only when at least one listed row has
it (`yes`/`—`) — the same conditional-column precedent `cli-transport.md`'s `CONTEXT` column
already established, so the common case (no standing rules active) looks exactly as it does today.
The one-off confirmation line printed by the `reset` command *itself* (immediately above) still
names the specific rule it retired or excluded from — that's transparency about the action just
taken, not a fact the user has to carry forward, so it doesn't carry the same objection.

## Versioning

Adding `includeSticky` as a trailing boolean parameter on five operations is additive by
top-level §11.1's letter (new optional parameter, new overload) but not by its spirit — the
*default behavior* of every existing zero-arg reset call changes underneath any caller that
doesn't pass the new argument. Pre-1.0 this is moot (§11.1's own words: "no skew guarantee
before 1.0"), but it's the same category of change §18.7 already called out explicitly for
`includeChildren`'s retirement, so it gets the same explicit callout here rather than being
buried in a diff.

## Decisions

**#1 — RESOLVED. `logctl reset --all` survives as the both-namespaces umbrella.**
"Get me back to normal" (§6.1) shouldn't require remembering two commands. It gets its own
`includeSticky` overload (`resetAll(boolean)`, in Operations above) for the same reason every
other form does — otherwise `--all` would be the one reset command that can never leave a sticky
override alone. Defined as exactly `resetAllLoggers(includeSticky)` + `resetAllHandlers(includeSticky)`
composed into one call and one `ResetOutcomeData` (concatenated `revertedNames`, unioned
`skippedStickyNames`) — not a third, independently-implemented code path.

**#2 — RESOLVED. Partial reset of a standing rule's coverage.**
Originally framed narrowly ("does `--include-sticky` also un-protect the rule itself"); working
through a concrete example surfaced a bigger, pre-existing gap — #41's shipped `resetLevel(target)`
has no way to reset *part* of what a rule covers, only the whole rule (by its exact original
pattern string) or nothing. See "Partial reset — scoped exclusions" above for the full mechanism.
Resolved as: `PatternRule` gains an exclusion set; `reset logger <target>` on a target narrower
than a covering rule's full scope carves that target (name or sub-pattern, same grammar either
way) out of the rule rather than retiring it, and full-string retirement (today's behavior) falls
out as the case where nothing is left over. A logger's descendants are only carved out when
`target` itself is a sub-pattern that says so — an excluded exact name's own descendants, present
and future, keep inheriting the rule. Exclusions belong to the rule *instance*; retiring and
re-creating a rule for the same pattern string starts clean. A single call may affect more than
one rule when `target` spans loggers currently owned by different active rules — no special
case, falls out of resolving ownership per logger. The original sticky-tier symmetry still holds
as part of this: a `STICKY` rule (whole or partial) needs `--include-sticky` to touch, exactly
like a `STICKY` single-logger override.
Still open, tracked separately as **Decision #6**: the exact display marker for surfacing which
rule governs a given override on `status`/`levels`.

**#3 — RESOLVED. No confirmation/preview on any reset form.**
None of the broad forms prompt, including `reset logger <pattern>`. This isn't a new call —
`pattern-level-targeting.md` already made this exact call for pattern reset specifically ("no
confirmation either way... reverting a bounded, current state is the opposite risk shape from
applying an unbounded, future-reaching one"), and `reset --all` already doesn't prompt today for
the same "someone typing this at 3am wants it to just work" reason (`cli-transport.md`). `reset
loggers`/`reset handlers` revert exactly what's active right now — bounded, not amplifying — so
the same reasoning extends to them without needing a new argument.

**#4 — RESOLVED. Bare `logctl reset <name>` does not survive as shorthand.**
Not raised explicitly in the issue text, but the command-shape table there shows only the four
namespaced forms. Retired alongside `logctl handler <name> reset` — keeping a keyword-less
logger-only shorthand around would mean two spellings for the same thing (`reset <name>` and
`reset logger <name>`) forever, undercutting the whole point of the namespace split being uniform.
A clear error on `logctl reset <bare-name>` naming the new form is cheap and keeps the surface
single-spelling.

**#5 — RESOLVED. A single, explicitly-named sticky target gets skipped too.**
Naming a sticky target exactly (`reset logger <exact-sticky-name>`) does **not** count as consent
by itself — uniform with every other form. The issue's own wording ("reset skips `--sticky`-tier
overrides" on "each form above") already read this way, and the alternative (bare-name resets
bypass the protection, only pattern/broad resets honor it) would be a second, harder-to-remember
rule rather than one flag with one meaning. This is the decision most likely to surprise an
existing script — `logctl reset com.acme.payments` on a sticky override currently just works, and
now needs `--include-sticky com.acme.payments` — worth calling out in the release notes when this
ships.

**#6 — RESOLVED. Display marker for a rule-governed override on `status`/`levels`.**
Decision #2's exclusion mechanism only helps if the operator can tell, from the output, that an
override is rule-governed (and will keep propagating to newly-discovered descendants) rather than
a one-off `setLevel`. The first proposal named the governing pattern in brackets
(`[org.apache.*]`) — rejected as inconsistent with Decision #2's own philosophy: the marker's job
is to say a row **will cascade to descendants discovered later**, never to identify **which rule**
is doing it — the user never needs that to act (`reset logger <target>` doesn't need it either).
Resolved as a plain boolean, `(cascading)` in text / `cascading: true` in `--json`, driven by
`originPattern != null` without ever surfacing the pattern string on this surface. See "CLI
output" above for the conditional `CASCADE` column and the one exception (the `reset` command's
own one-off confirmation line still names what it acted on — that's feedback about the action just
taken, not an ongoing fact to track).

## Cross-reference updates

All landed alongside the implementation:

- `cli-transport.md`: "Superseded (shipped)" notes on the `resetLevel`/`resetAll` command
  mapping and the detailed `logctl reset <logger>` / `logctl reset --all` section; the
  `#42` "deferred" scope bullet struck through; the `undo`-section and Naming-reconciliation
  literal command mentions updated to the new spelling.
- `handler-floor-control.md`: its "Superseded (planned)" note resolved to "(shipped)".
- `persistence.md`: its "Superseded (planned)" note resolved to "(shipped)" — the
  exit-criterion sentence it points at stays as written (it accurately described that
  slice's behavior at the time).
- `pattern-level-targeting.md`: "Superseded (shipped)" notes on `resetLevel(target)`'s
  exact-string lookup (Decision #5) and on sweep step 2; the `PatternRule` data model and
  state-file schema sections note the new `exclusions` field and `schemaVersion` 4 → 5.
- Top-level §18.9: Status updated to "Shipped", matching §18.7's treatment.

## Testing (sketch)

- Unit: each new/changed operation, sticky-skip on/off, against a hand-built override registry
  fixture (mirrors `LevelControlServiceTest` patterns already in place for `resetLevel`).
- Unit: partial-reset scenarios directly off this spec's worked example — reset an exact name
  under a live sub-pattern rule leaves its descendants covered (both currently-known and
  discovered afterward via a simulated sweep tick); reset a sub-pattern wipes out the whole
  named branch and stops the sweep from reclaiming anything under it; a target spanning two
  overlapping rules retires one and excludes from the other in a single call; exclusions do not
  survive a full retire-then-recreate of the same pattern string.
- CLI: `ParserTest` for the new subcommand grammar and the retired forms' error messages;
  `MainRunTest`/`Json` tests for the new summary line, the retirement-vs-exclusion rendering, and
  the `--json` shape.
- Cross-process: extend `CliEndToEndIT` with a sticky-then-broad-reset scenario (sticky survives
  a plain `reset loggers`, is gone after `--include-sticky`), a `reset handler <name>` /
  retired-`handler <name> reset` pair, and a live-standing-rule partial-reset scenario across a
  real sweep interval.
- `WildFlyContainerIT`: at minimum confirm `reset handlers`/`reset loggers` behave the same
  broadcast way existing broad resets do there.
