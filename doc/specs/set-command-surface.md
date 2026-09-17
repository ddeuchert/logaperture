# Set command surface: logger/handler namespacing, level-verb retirement

Status: implemented (slice 2 of #42).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.9 (roadmap entry — the
broader command-surface refactor), §6.2 (the phone test), §14.5 ("CLI ergonomics are the
product" — the level-named verbs this slice retires were originally justified by this same
section).
Builds on, unchanged: [`doc/specs/pattern-selection-semantics.md`](pattern-selection-semantics.md)
(issue #49 — a pattern `target`'s preview/confirm/one-time-selection behavior is untouched;
this spec only changes how the command that reaches it is spelled),
[`doc/specs/persistence.md`](persistence.md) (the `SESSION`/`FOR`/`STICKY` tiers and the
duration grammar, reused as-is).
Implementation spec for: [`doc/specs/cli-transport.md`](cli-transport.md)'s "Command surface" —
this slice replaces that document's `set`/level-verb/`handler`-set sections on landing.
Tracks: slice 2 of [issue #42](https://github.com/ddeuchert/logaperture/issues/42).

## Functional summary

After this feature, the user will be able to:

- Set a logger's level with one uniform spelling — `logctl set logger com.acme.batch DEBUG`
  — instead of choosing between the explicit `logctl set com.acme.batch DEBUG` and five
  level-named shortcuts (`debug`/`trace`/`info`/`warn`/`error`) that did the same thing.
- Set a handler's level or put it into `AUTO` mode the same way — `logctl set handler
  CONSOLE TRACE`, `logctl set handler CONSOLE AUTO` — replacing today's bare `logctl handler
  CONSOLE TRACE` / `logctl handler CONSOLE AUTO`.
- Notice that `logctl debug com.acme.batch` (and its `trace`/`info`/`warn`/`error` siblings)
  no longer exist — `logctl set logger com.acme.batch DEBUG` is the one spelling, for every
  level, for every target shape (exact name or pattern).
- Keep every existing tier, duration, `--reason`, and pattern-preview/confirm behavior
  exactly as it works today — this slice changes only the command's noun, not what it does
  once parsed.

## Scope of this slice

Slice 2 of [#42](https://github.com/ddeuchert/logaperture/issues/42)'s broader command-surface
refactor — the `set` half only. `reset` namespacing shipped in slice 1
([`reset-command-surface.md`](reset-command-surface.md)); `list` namespacing (slice 3) is a
separate spec, separate branch, separate PR.

**In scope:**

- Two CLI forms: `set logger <target> <level> [session | for <duration> | sticky]`,
  `set handler <name> <level>|AUTO [session | for <duration> | sticky]`.
- Retiring the level-named verbs (`debug`/`trace`/`info`/`warn`/`error`) entirely — no alias
  kept (David's explicit call: "Drop, set logger only").
- Retiring the bare `set <target> <level>` spelling (no `logger` noun) in favor of `set
  logger <target> <level>`.
- Retiring the bare top-level `handler <name> <level>` / `handler <name> AUTO` spellings in
  favor of `set handler <name> <level>` / `set handler <name> AUTO`. (`handler <name>
  reset` was already retired in slice 1, in favor of `reset handler <name>` — nothing left
  under the bare `handler` verb after this slice, so it's removed from the grammar entirely.)
- MXBean/service method rename: `setLevel` → `setLogger` (Decision #1, below) — the `set
  logger`/`resetLogger` naming parity this issue's whole point is retiring "level" as a
  label now that the CLI never says it.
- Helpful, specific usage errors for every retired spelling, naming its replacement — same
  standard slice 1 held itself to for `reset`/`handler ... reset`.

**Explicitly out of scope:**

- **`list` renaming** (slice 3 of #42) — `logctl levels`/`logctl handlers` are untouched
  here; still exist under their current names until slice 3.
- **The exception-detail/filter follow-on** (`with expand exception on <level>`, `with
  filter '<filter-spec>'`) — not this slice's scope, but its later `with ...` clauses need
  to slot in between `<level>` and the tier token without a breaking reparse. This slice's
  grammar (Decision #2, below) is chosen with that in mind.
- **Pattern-selection mechanics** (#41/#49) — untouched. `set logger <pattern> <level>`
  inherits the existing preview/confirm/one-time-selection behavior unchanged; only the
  command's noun changes, not how a pattern target is resolved or confirmed.
- **`SetLevelOptions`/`SetLevelResult`/`SetLevelResultData`** (and the `Level` enum,
  `LevelOverride`, `LevelControlMXBean`/`LevelControlService` themselves) — not touched by
  Decision #1's rename, same reasoning [`reset-command-surface.md`](reset-command-surface.md)
  used for `ResetOutcome`/`LevelControlMXBean` staying put: these name the *domain concept*
  (a logger's level, and the interface that controls it), not this one operation's verb.

## Design decisions

### Decision #1: `setLevel` → `setLogger`

Same call [`reset-command-surface.md`](reset-command-surface.md)'s Decision #2a already made
for `resetLevel` → `resetLogger`, applied to the other half of the same pair. The CLI now
says `set logger`/`reset logger`, never `set level`/`reset level` — leaving the MXBean
method named `setLevel` while its sibling is `resetLogger` would put the "level" label right
back in the one place #42 is trying to retire it from, and leave the two operations visibly
inconsistent with each other for no reason.

```java
// LevelControlOperations / LevelControlService / AggregateLevelControl / LevelControlMXBean / LevelControlMXBeanImpl
SetLevelResult setLogger(String target, Level level, SetLevelOptions options);          // core, was setLevel
SetLevelResultData setLogger(String target, String level, String reason, String tier,   // MXBean, was setLevel
        long forSeconds, boolean confirmed);
```

Only the method name changes — signature, parameter order, and behavior are untouched.
`Commands.setLevel` (the CLI's own renderer) becomes `Commands.setLogger` to match.

**Not renamed, on purpose** (mirroring `reset-command-surface.md`'s precedent): `Level` (the
enum), `LevelOverride`/`LevelOverrideData`, `SetLevelOptions`, `SetLevelResult`/
`SetLevelResultData`, `LevelControlMXBean`/`LevelControlService`/`LevelControlOperations`
(the interface/service/class names themselves). These name the domain concept a level
override *is*, not the verb that creates one — renaming them is a separate, much larger
surface this slice isn't taking on, same boundary Decision #2a in the reset spec already
drew.

**`setHandlerLevel`/`setHandlerAuto` are unaffected** — "handler" already disambiguates
these from the logger-level operations; there's no "level" label floating free the way
`setLevel` had.

### Decision #2: grammar shape, and where a future `with ...` clause slots in

```
set logger <target> <level> [session | for <duration> | sticky]
set handler <name> <level>|AUTO [session | for <duration> | sticky]
```

`<target>`/`<name>` and `<level>` keep their existing grammars unchanged (exact name or
pattern for `<target>`; `Level` enum, case-insensitive, for `<level>`; `AUTO` case-
insensitive for the handler form). The tier token's position — last, after `<level>` — is
deliberately preserved rather than moved, so that when the exception-detail/filter follow-on
(top-level §18.7's sketch: `set logger <target> <level> [with expand exception on <level>]
[with filter '<filter-spec>'] [tier]`) eventually lands, its `with ...` clauses insert
between `<level>` and the tier token without requiring this slice's grammar to be reparsed
or reordered. Not implemented here — flagged only so slice 2's grammar doesn't have to be
revisited when that follow-on ships.

### Decision #3: retired spellings get a specific usage error, not a generic one

Every retired spelling names its replacement, matching slice 1's standard for `reset`/
`handler ... reset`:

```
$ logctl debug com.acme.batch
logctl: 'debug' no longer exists -- use 'set logger com.acme.batch DEBUG'.

$ logctl set com.acme.batch DEBUG
logctl: 'set' needs 'logger <target> <level>' or 'handler <name> <level>'.

$ logctl handler CONSOLE TRACE
logctl: 'handler' no longer exists -- use 'set handler CONSOLE TRACE'.
```

All exit 2 (usage error), consistent with every other malformed-command case.

## Command surface

Replaces `cli-transport.md`'s "`logctl set <logger> <level>` and the level-named forms" and
"`logctl handler <name> <level>`"/"`logctl handler <name> AUTO`" sections on landing.

### `logctl set logger <target> <level> [tier]`

Calls `setLogger(target, level, options)`. Behavior is byte-for-byte what today's `set`/
level-verb forms already do:

- `<target>` exact name or leading-star pattern (trailing-star rejected, per #49's Decision
  #5); a leading-star pattern previews its current matches and asks to confirm (`--yes`
  skips the prompt), exactly as today.
- `<level>` matched against `Level`, case-insensitive.
- Tier token: omitted → `FOR 4h`; `session`; `for <duration>`; `sticky` — unchanged grammar
  and defaults.
- `--reason <text>` accepted, same as today.
- Confirmation line(s) and `--json` shape unchanged from today's `set`/level-verb output.

### `logctl set handler <name> <level>|AUTO [tier]`

Calls `setHandlerLevel(...)` or `setHandlerAuto(...)` depending on whether the token after
`<name>` is `AUTO` (case-insensitive) or a `Level`. Behavior, output, and the blocking-
handler/squelch warnings are unchanged from today's bare `handler <name> <level>`/`handler
<name> AUTO`.

### Removed

- `logctl debug|trace|info|warn|error <target> [tier]` — usage error naming `set logger
  <target> <LEVEL>`.
- `logctl set <target> <level> [tier]` (bare, no `logger` noun) — usage error naming `set
  logger <target> <level>`.
- `logctl handler <name> <level>|AUTO [tier]` (bare top-level `handler` verb) — usage error
  naming `set handler <name> <level>`/`AUTO`. Nothing remains under a bare `handler` verb
  after this slice (its `reset` form left already, in slice 1).

## Testing

Per top-level §12's cheap-unit-tests-plus-one-shallow-integration-test split, mirroring
`reset-command-surface.md`'s coverage:

**Unit:**

- Parser: `set logger`/`set handler` accept every existing tier/target-shape combination;
  `debug`/`trace`/`info`/`warn`/`error`, bare `set <target> <level>`, and bare `handler
  <name> <level>`/`AUTO` are usage errors naming their replacement.
- `Commands.setLogger`/`Commands.setHandlerLevel`/`Commands.setHandlerAuto` (stubbed MXBean):
  unchanged rendering — this slice doesn't rewrite `Commands`' bodies, only their names and
  the `Parser` call sites reaching them, so existing `CommandsTest` coverage for pattern
  preview/confirm, blocking-handler warnings, and squelch warnings carries over unchanged
  under the new method name.
- `LevelControlMXBeanImplTest`/service-level tests: `setLogger` behaves identically to
  today's `setLevel` under its new name — a rename-only diff, not new behavior to test.

**Cross-process integration (`CliEndToEndIT`, extending the existing suite):**

- `run(["set", "logger", FIXTURE_LOGGER, "DEBUG", "for", "1m"])` behaves exactly as today's
  `run(["debug", FIXTURE_LOGGER, "for", "1m"])` did.
- `run(["debug", FIXTURE_LOGGER])` → exit 2, naming `set logger`.
- `run(["handler", "CONSOLE", "TRACE"])` → exit 2, naming `set handler`.

## Exit criterion

From a plain shell, against a `java -jar` application started with
`-javaagent:logaperture-agent.jar`:

- `logctl set logger com.acme.batch.Worker DEBUG for 30m --reason INC-123` behaves exactly
  as today's `logctl debug com.acme.batch.Worker for 30m --reason INC-123` does.
- `logctl set handler CONSOLE TRACE` and `logctl set handler CONSOLE AUTO` behave exactly as
  today's `logctl handler CONSOLE TRACE`/`AUTO` do.
- `logctl debug com.acme.batch`, `logctl set com.acme.batch DEBUG`, and `logctl handler
  CONSOLE TRACE` (the three retired spellings) are all usage errors naming their
  replacement.
- Every command in `logctl --help` still passes the phone test.

## Sign-off

Open — drafted for review. Decision #1 (`setLevel` → `setLogger`) is the one genuine
judgment call, proposed for parity with slice 1's already-agreed `resetLevel` →
`resetLogger`; Decisions #2 and #3 are close to mechanical, flagged here rather than left
silent. Once agreed, fold any changes back into this file before implementation starts
(CLAUDE.md).
