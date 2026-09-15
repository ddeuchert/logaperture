# Reset command surface: logger/handler split + `--include-sticky` (issue #42)

Status: draft — under review, open decisions below.
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
- Keep using `logctl reset --all` as the "get me back to normal, everything, both namespaces"
  escape hatch (pending sign-off — see Decision #1).

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
logctl reset --all [--include-sticky]          # pending Decision #1
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
ResetOutcomeData resetAll(boolean includeSticky);                     // new overload — Decision #1
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

**`ResetOutcomeData` gains a skipped-sticky field**, reused across all five operations:

```java
List<String> revertedNames;      // loggers, or handlers, reverted by this call
boolean patternRuleRetired;      // unchanged — meaningless (false) for a non-pattern target
List<String> skippedStickyNames; // reverted-eligible but left alone because STICKY and !includeSticky
```

`skippedStickyNames` is empty whenever `includeSticky = true`, or the target had no sticky entries
to begin with — a plain, no-`--include-sticky` reset with nothing sticky in scope looks exactly as
it does today. For `resetLevel` on a **pattern** target, a sticky-tier `PatternRule` itself (not
just the individual overrides it produced) is what gets skipped-or-retired — see Decision #2.

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
already-shipped pattern reset does.

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

## Versioning

Adding `includeSticky` as a trailing boolean parameter on five operations is additive by
top-level §11.1's letter (new optional parameter, new overload) but not by its spirit — the
*default behavior* of every existing zero-arg reset call changes underneath any caller that
doesn't pass the new argument. Pre-1.0 this is moot (§11.1's own words: "no skew guarantee
before 1.0"), but it's the same category of change §18.7 already called out explicitly for
`includeChildren`'s retirement, so it gets the same explicit callout here rather than being
buried in a diff.

## Open decisions

**#1 — Does `logctl reset --all` survive as the both-namespaces umbrella?**
Leaning **yes, keep it** (per the issue: "'get me back to normal' shouldn't require remembering
two commands"). If kept, it needs its own `includeSticky` overload (`resetAll(boolean)`, in
Operations above) for the same reason every other form does — otherwise `--all` would be the one
reset command that can never leave a sticky override alone, which would be a strange asymmetry.
Recommendation: keep `--all`, give it `--include-sticky` too, and define it as exactly
`resetAllLoggers(includeSticky)` + `resetAllHandlers(includeSticky)` composed into one call and one
`ResetOutcomeData` (concatenated `revertedNames`, unioned `skippedStickyNames`) — not a third,
independently-implemented code path.

**#2 — `--include-sticky` on a pattern target: does it also un-protect the standing rule itself?**
A standing `PatternRule` is persisted per its own tier, exactly like a `LevelOverride`
(`pattern-level-targeting.md`: "persisted per the tier it's set at"). That symmetry gives a clean
answer: a `STICKY`-tier `PatternRule` is itself a sticky-tier entity, so the *same* filter that
protects a sticky logger/handler override from a plain reset protects a sticky standing rule from
retirement too — no special-casing needed, `--include-sticky` uncovers both in one flag.
Recommendation: yes, uniform — a `STICKY` pattern rule needs `--include-sticky` to retire, exactly
like a `STICKY` single-logger override needs it to revert; nothing pattern-specific to design.

**#3 — Confirmation/preview parity with #41's `error <pattern>` apply-side prompt.**
Recommendation: **no**, none of the broad forms should prompt, including `reset logger
<pattern>`. This isn't a new call — `pattern-level-targeting.md` already made this exact call for
pattern reset specifically ("no confirmation either way... reverting a bounded, current state is
the opposite risk shape from applying an unbounded, future-reaching one"), and `reset --all`
already doesn't prompt today for the same "someone typing this at 3am wants it to just work"
reason (`cli-transport.md`). `reset loggers`/`reset handlers` revert exactly what's active right
now — bounded, not amplifying — so the same reasoning extends to them without needing a new
argument.

**#4 — Does bare `logctl reset <name>` (no `logger`/`handler` keyword) survive as shorthand?**
Not raised explicitly in the issue text, but the command-shape table there shows only the four
namespaced forms — worth confirming rather than assuming. Recommendation: **no**, retire it
alongside `logctl handler <name> reset` — keeping a keyword-less logger-only shorthand around
would mean two spellings for the same thing (`reset <name>` and `reset logger <name>`) forever,
undercutting the whole point of the namespace split being uniform. A clear error on `logctl reset
<bare-name>` naming the new form is cheap and keeps the surface single-spelling.

**#5 — Does a single, explicitly-named sticky target (`reset logger <exact-sticky-name>`) get
skipped too, or does naming it directly count as consent?**
The issue's own wording ("reset skips `--sticky`-tier overrides" on "each form above") reads as
applying even to a one-name, fully-explicit target — but that's a real behavior change from
today (`logctl reset com.acme.payments` on a sticky override currently just works), and "I typed
the exact name" is arguably itself a considered decision, similar in spirit to why sticky exists
at all. Recommendation: **skip it too, uniformly** — the alternative (bare-name resets bypass the
protection, only pattern/broad resets honor it) is a second, harder-to-remember rule rather than
one flag with one meaning; consistency here is worth the small extra friction of occasionally
needing `--include-sticky com.acme.payments`. Flagging for explicit sign-off since it's the
decision most likely to surprise an existing script.

## Cross-reference updates (once signed off)

- `cli-transport.md`: replace the `logctl reset <logger>` / `logctl reset --all` section with
  the four-form grammar; move its current content into `reset logger`/`reset --all`
  subsections; retire the `#42` "deferred" bullet in Scope.
- `handler-floor-control.md`: land the rename in the "handler resets are always spelled..."
  passage and resolve its own "Superseded (planned)" note (line ~235).
- `persistence.md`: resolve its "Superseded (planned)" note on sticky removal (line ~432) —
  the exit-criterion sentence it points at stays as written (it accurately described that
  slice's behavior at the time); this spec's landing is what makes the forward note obsolete,
  not a rewrite of the original sentence.
- Top-level §18.9: update Status once implemented, matching §18.7's "shipped" treatment.

## Testing (sketch, to expand once decisions are resolved)

- Unit: each new/changed operation, sticky-skip on/off, against a hand-built override registry
  fixture (mirrors `LevelControlServiceTest` patterns already in place for `resetLevel`).
- CLI: `ParserTest` for the new subcommand grammar and the retired forms' error messages;
  `MainRunTest`/`Json` tests for the new summary line and `--json` shape.
- Cross-process: extend `CliEndToEndIT` with a sticky-then-broad-reset scenario (sticky survives
  a plain `reset loggers`, is gone after `--include-sticky`) and a `reset handler <name>` /
  retired-`handler <name> reset` pair.
- `WildFlyContainerIT`: at minimum confirm `reset handlers`/`reset loggers` behave the same
  broadcast way existing broad resets do there.
