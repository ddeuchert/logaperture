# Reset command surface: logger/handler split + `--include-sticky`

Status: implemented (slice 1 of #42).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.9 (roadmap entry this
implements), §6.1 (persistence tiers — `STICKY`'s "known-noisy, I decided about it" framing),
§9 (capability/audit model), §11.1 (component versioning — every MXBean signature change below
is pre-1.0 and non-additive, so this bumps nothing but is called out per that policy anyway).
Builds on, unchanged: [`doc/specs/persistence.md`](persistence.md) (the `SESSION`/`FOR`/`STICKY`
tiers themselves), [`doc/specs/pattern-selection-semantics.md`](pattern-selection-semantics.md)
(issue #49 — a pattern `target` is a one-time selection resolved against currently-known
loggers; this spec changes nothing about that, only what a reset call reports and which tier it
skips).
Implementation spec for: [`doc/specs/cli-transport.md`](cli-transport.md)'s "Command surface" —
this slice replaces that document's `reset` section on landing.
Tracks: slice 1 of [issue #42](https://github.com/ddeuchert/logaperture/issues/42).

## Functional summary

After this feature, the user will be able to:

- Reset one logger — `logctl reset logger <target>` — or every currently-overridden logger at
  once — `logctl reset loggers` — as two separate commands instead of one `reset` that only
  ever did the former, plus a separate `--all` flag that reverted loggers and handlers
  together.
- Do the same for handlers: `logctl reset handler <name>` for one, `logctl reset handlers` for
  every handler override at once — retiring today's oddly-placed `logctl handler <name> reset`
  spelling.
- Use a glob on `logctl reset logger <pattern>`, exactly as `logctl set logger <pattern> <level>`
  already accepts one — reverting every currently-overridden logger the pattern matches, in one
  command.
- Trust that a routine or scripted reset **leaves a `sticky` override alone by default** — sticky
  is a considered, "I decided about this" decision, and a broad reset silently undoing it would
  be a trap. Passing `--include-sticky` opts back in, on every one of the four forms above.
- Get a clear, non-zero-exit refusal — not silent nothing — when naming one specific sticky
  target without `--include-sticky`: `logctl reset logger com.acme.payments` on a
  `sticky`-tier override tells you to add the flag rather than quietly doing nothing.
- Notice `logctl reset --all` no longer exists. `logctl reset loggers` and `logctl reset
  handlers` are the two broad forms; nothing reverts both namespaces in a single command
  anymore.

## Scope of this slice

Slice 1 of [#42](https://github.com/ddeuchert/logaperture/issues/42)'s broader command-surface
refactor — the reset half only. `set`/`list` namespacing (slices 2 and 3) are separate specs,
separate branches, separate PRs; this one touches only the four reset forms, the MXBean
surface under them, and `cli-transport.md`'s reset section.

**In scope:**

- Four CLI forms: `reset logger <target> [--include-sticky]`, `reset loggers
  [--include-sticky]`, `reset handler <name> [--include-sticky]`, `reset handlers
  [--include-sticky]`.
- `--include-sticky`, defaulting to `false` on every form — a behavior change from today's
  shipped `resetLevel`/`resetAll`/`resetHandler`, which revert a `STICKY` override exactly like
  any other tier.
- The single-target-vs-many-targets distinction on what a sticky skip means (Decision #1,
  below).
- MXBean signature changes: `resetLevel` is **renamed to `resetLogger`** and gains
  `includeSticky` (Decision #2a — retires "level" as a label now that the CLI verb is
  `reset logger`, not a generic `reset`); `resetAll()` is removed; `resetHandler` gains
  `includeSticky` and a return value (was `void`); two new bulk operations,
  `resetAllLoggers(includeSticky)` and `resetAllHandlers(includeSticky)`.
- The single-target refusal case (Decision #1) reuses `IllegalArgumentException`, the same
  type `setLevel`'s trailing-wildcard rejection already throws — no new exception type, and
  `Main` already maps it to exit 2 (implementation-time simplification from an earlier draft
  of this spec, which had proposed a dedicated `StickyOverrideException`).
- Updated `ResetOutcomeData` (adds `skippedStickyLoggerNames`) and a new
  `HandlerResetOutcomeData` (mirrors it for handlers).
- Retiring `logctl handler <name> reset` — no alias kept (pre-1.0, per top-level §11.1).
- Retiring `logctl reset <logger>` (bare) and `logctl reset --all` — no alias kept.

**Explicitly out of scope:**

- **`set`/`list` renaming** (slices 2 and 3 of #42) — untouched here. `logctl set logger`,
  `logctl set handler`, `logctl list loggers`, `logctl list handlers` do not exist yet after
  this slice; `set`, `debug`/`trace`/.../`error`, `handler <name> <level>`/`AUTO`, `levels`,
  `handlers` are all unchanged.
- **The scoped-exclusion mechanism** PR #48 built (partial reset of a standing rule). Moot —
  #49 retired standing rules entirely; a pattern `target` has no rule state left to partially
  carve up. Reset on a pattern reverts whatever it currently matches, full stop, same as it does
  today.
- **`status`'s `logctl env` pointer line** — tracked under slice 3 (it's `list`/`status`
  rendering work), not this slice.

## Design decisions

### Decision #1: single target vs. a set, on hitting `STICKY`

A `reset logger <exact-name>` or `reset handler <name>` names **one specific thing** — if it
turns out to be `sticky` and `--include-sticky` wasn't passed, this refuses outright:
`IllegalArgumentException`, surfaced as exit 2, mutates nothing.

```
$ logctl reset logger com.acme.payments
logctl: com.acme.payments is STICKY — reset refused without --include-sticky.
```

A `reset logger <pattern>`, `reset loggers`, or `reset handlers` names **a set, however large**
(possibly empty, possibly everything) — a sticky member of that set is silently left alone and
reported, not an error, the same way "the pattern matched nothing" isn't an error:

```
$ logctl reset loggers
Reverted 3 override(s).
Left 1 sticky override(s) in place (pass --include-sticky to include them): com.acme.payments
```

**Rationale:** naming one thing and having it silently do nothing is a trap (exactly the trap
`--include-sticky`'s default is trying to prevent, one level up) — a specific target deserves a
specific, actionable refusal. A broad reset already has a "here's what actually happened" report
as its normal shape (today's `Reverted N override(s).`); folding a skip into that report costs
nothing new to read and doesn't turn a routine bulk operation into something that can fail
outright over one sticky entry buried in it. (Signed off — see "Sign-off" below.)

### Decision #2: MXBean shape, and retiring "level" from the method name

Two new bulk operations rather than overloading `resetLogger`/`resetHandler` with a
nullable-target special case:

```java
ResetOutcomeData resetLogger(String target, boolean includeSticky);         // exact name or pattern
ResetOutcomeData resetAllLoggers(boolean includeSticky);                    // every overridden logger
HandlerResetOutcomeData resetHandler(String handlerRef, boolean includeSticky);
HandlerResetOutcomeData resetAllHandlers(boolean includeSticky);            // every overridden handler
// resetAll() removed.
```

A nullable `target` meaning "everything" would make `resetLogger(null, false)` a silent special
case buried in one method's contract; two named methods keep each one's job legible from its
signature alone, matching the split the CLI grammar itself makes (`reset logger` vs.
`reset loggers` are different commands, not one command with an optional argument).

**Decision #2a: `resetLevel` → `resetLogger`.** This slice already changes the method's
signature, and #42's whole point is retiring "level" as a confusing synonym now that the
CLI itself says `logger`/`handler`, never `level`, as the noun after `reset` — keeping the
MXBean method's old name while renaming everything around it would leave the "level" label
alive in exactly the one place this refactor is trying to remove it from. `setLevel` is left
as-is in this slice (it's untouched CLI surface here — `set logger` is slice 2's rename) and
picks up the matching `setLogger` rename there, not here; `Level` the enum, `LevelOverrideData`,
and `LevelControlMXBean`/`LevelControlService` (the interface/service names) are **not**
touched by this decision — they name the *domain concept* (a logger's level), not a specific
operation's verb, and renaming those is a separate, much larger surface this slice isn't taking
on.

### Decision #3: `resetHandler`'s three-way report, brought in line with `resetLogger`

Today's `resetHandler` is `void` and always prints `handler X → reset to its previous level.`,
even when nothing was overridden. Since this slice already changes its signature and return
type, it also picks up `resetLogger`'s existing three-way reporting for free: reverted vs.
"nothing was overridden" vs. refused (Decision #1). Small, but free — not a separate piece of
scope creep, just not deliberately leaving the old method's gap in place while rewriting the
method around it.

## Command surface

Replaces `cli-transport.md`'s "`logctl reset <logger>` and `logctl reset --all`" section
entirely on landing.

### `logctl reset logger <target> [--include-sticky]`

`target` is an exact logger name or a pattern, exactly as `set logger` accepts
(`pattern-selection-semantics.md`'s grammar, unchanged here).

**Exact name:** reads `listLoggers(name)` before the call (as today, to learn whether an
override was active and whether the logger survives the reset), calls `resetLogger(name,
includeSticky)`, reads `listLoggers(name)` again. Four outcomes now, not three — Decision #1's
refusal added ahead of the existing three:

- Active override is `STICKY` and `!includeSticky` → refused, exit 2, nothing changes (Decision
  #1).
- Reverted, logger still known → `com.acme.batch.Worker → INFO (baseline)`.
- Reverted, logger not yet instantiated → `com.acme.batch.Worker → baseline (not yet
  instantiated, so no level to show)`.
- Nothing was overridden → `com.acme.batch.Worker — nothing was overridden.`

**Pattern:** calls `resetLogger(pattern, includeSticky)`, renders `ResetOutcomeData` exactly as
today's pattern-reset path does for `revertedLoggerNames`, then — new — a second line if
`skippedStickyLoggerNames` is non-empty:

```
com.acme.batch.Worker → baseline
Left 1 sticky override(s) in place (pass --include-sticky to include them): com.acme.payments
```

`--json` emits `{"revertedLoggerNames": [...], "skippedStickyLoggerNames": [...]}` for both
exact-name and pattern forms (an exact-name success has at most one entry in the first array,
per today's convention of always returning an object, never a bare `null`).

### `logctl reset loggers [--include-sticky]`

Calls `resetAllLoggers(includeSticky)`. No target, no confirmation prompt — same "someone typing
this wants it to just work" reasoning `cli-transport.md` already gives `reset --all` today.

```
Reverted 3 override(s).
Left 1 sticky override(s) in place (pass --include-sticky to include them): com.acme.payments
```

Zero active, non-sticky overrides and `includeSticky=false`: `No overrides to reset.` (even if
sticky ones exist and were left alone — still print the "left in place" line in that case, so
"nothing happened" and "one sticky entry is why nothing happened" don't look identical).

`--json`: `{"revertedLoggerNames": [...], "skippedStickyLoggerNames": [...]}`, same shape as the
pattern form above.

### `logctl reset handler <name> [--include-sticky]`

Exact name only — handlers don't take a pattern (small, enumerable catalog; same reasoning
`cli-transport.md`'s handler sections already give for not needing one). Same four-outcome shape
as the logger exact-name form (Decision #3):

- `STICKY` and `!includeSticky` → refused, exit 2.
- Reverted → `handler console → reset to its previous level.`
- Nothing was overridden → `handler console — nothing was overridden.`

`--json`: `{"handlerRef": "...", "reverted": <bool>, "wasOverridden": <bool>}`.

### `logctl reset handlers [--include-sticky]`

Calls `resetAllHandlers(includeSticky)`. Same shape as `reset loggers`:

```
Reverted 2 handler override(s).
Left 1 sticky handler override(s) in place (pass --include-sticky to include them): file
```

`--json`: `{"revertedHandlerRefs": [...], "skippedStickyHandlerRefs": [...]}`.

### Removed

- `logctl reset <logger>` (bare, no `logger` noun) — usage error naming `reset logger <target>`.
- `logctl reset --all` — usage error naming `reset loggers` and `reset handlers`.
- `logctl handler <name> reset` — usage error naming `reset handler <name>`.

### Exit codes

Adds one row to `cli-transport.md`'s table:

| Exit | Meaning |
|---|---|
| 2 | *(existing usage-error code, reused)* — now also covers: an exact-name `reset logger`/`reset handler` target is `STICKY` and `--include-sticky` wasn't passed. |

This reuses exit 2 rather than minting a new code. It's decided after a round-trip to the server
(the CLI doesn't know a target's tier until it asks), unlike every other exit-2 case today, which
is caught by the parser before any connection is made — but the operator-facing shape is the
same ("you're missing a required flag for what you typed"), and exit 6 (policy refusal) is
reserved for a capability check failing, which this isn't.

## Testing

Per top-level §12's cheap-unit-tests-plus-one-shallow-integration-test split, mirroring
`cli-transport.md`'s existing coverage:

**Unit:**

- Parser: `reset logger`, `reset loggers`, `reset handler`, `reset handlers` accept
  `--include-sticky`; `reset` (bare), `reset --all`, `handler <name> reset` are usage errors
  naming the replacement.
- `Commands` (stubbed MXBean): all four outcome shapes per form (reverted / nothing-overridden /
  sticky-refused / bulk-with-skips), text and `--json`, including the zero-reverted-but-one-
  skipped "left in place" line.
- The sticky-refusal `IllegalArgumentException` → exit 2 with the specific message, driven
  through a stubbed transport, same pattern as the existing `CapabilityDeniedException` →
  exit 6 test.
- Service-level (`logaperture-core`): `resetLogger`/`resetAllLoggers`/`resetHandler`/
  `resetAllHandlers` — sticky skipped by default, included with the flag, exact-name sticky
  throws, pattern/bulk sticky reports instead of throwing.

**Cross-process integration (`CliEndToEndIT`, extending the existing suite):**

- Set two loggers, one `sticky`, one `for 1m`; `reset loggers` with no flag reverts the `for`
  one, leaves the `sticky` one active and reports it; `reset loggers --include-sticky` then
  clears it.
- `reset logger <sticky-name>` with no flag → exit 2, override still active (`status` still
  shows it); with `--include-sticky` → reverted.
- `reset handler <name> reset` (the old spelling) and bare `reset <name>` → exit 2 naming the
  new forms.

## Exit criterion

From a plain shell, against a `java -jar` application started with
`-javaagent:logaperture-agent.jar`:

- `logctl set logger com.acme.payments warn sticky` then `logctl reset logger com.acme.payments`
  refuses with exit 2; `logctl reset logger com.acme.payments --include-sticky` reverts it.
- `logctl reset loggers` reverts every non-sticky logger override and reports any sticky ones
  left in place; `logctl reset handlers` does the same for handlers.
- `logctl reset` (bare), `logctl reset --all`, and `logctl handler <name> reset` are all usage
  errors naming their replacements.
- Every command in `logctl --help` still passes the phone test (`cli-transport.md`'s existing
  check — none of `: = ( ) /` in a synopsis line).

## Sign-off

Signed off 2026-09-16. Decision #1 (single-target refusal vs. set-level skip-and-report)
reflects David's explicit call in review; Decision #2 was revised in review to add #2a
(`resetLevel` → `resetLogger`, retiring "level" as a label on this operation, matching #42's
overall goal); Decision #3 accepted as drafted.
