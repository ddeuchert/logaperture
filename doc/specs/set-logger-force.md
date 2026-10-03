# `set logger --force` — reach descendants that have their own level

Status: **signed off 2026-10-03** (all decisions F1–F11 agreed as recommended). Tracked as [#142](https://github.com/ddeuchert/logaperture/issues/142).
Parent: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.17. Related:
[`pattern-selection-semantics.md`](pattern-selection-semantics.md) (Decision #5),
[`level-control.md`](level-control.md), [`recipes.md`](recipes.md) (#7, the tagging precedent),
[`guided-commands.md`](guided-commands.md).

## Functional summary

After this feature, the user will be able to:

- Run `logctl set logger com.mycompany TRACE --force` to put `com.mycompany` **and everything under
  it** at TRACE, including loggers such as `com.mycompany.other` that the server's own logging
  config sets to a level of their own.
- See, after a plain `logctl set logger com.mycompany TRACE`, which loggers under it kept their own
  level and so did not follow, with the command to bring them along.
- Be asked, in guided `logctl set logger`, whether to bring those loggers along too.
- Run `logctl reset logger com.mycompany` to put everything back the way it was found, without
  undoing anything changed by hand in the meantime.

## The problem

Every framework LogAperture targets gives a logger's level to its descendants **only if they have
no level of their own** (§4.3). `com.mycompany.other`, set to `INFO` in `standalone.xml`, stays at
`INFO` after `set logger com.mycompany TRACE`. LogAperture is doing nothing wrong, but the command
doesn't do what it reads as, and today nothing says so. Decision #5 of
`pattern-selection-semantics.md` refuses `set logger com.mycompany.*` on the grounds that a bare
name "already reaches every descendant"; that is true only of descendants without their own level.
This feature closes that gap without bringing back the standing wildcard Decision #5 removed.

## Scope

In: the `--force` flag on `set logger`; the note after a plain `set logger`; the guided question;
how forced loggers are tracked, expire and reset; the JMX and `--json` surface; audit; state file.

Out:

- **Handlers.** `set handler` has no hierarchy; group refs (`ALL_HANDLERS`) already reach every member.
- **Rules.** A rule on `com.mycompany` already reaches every descendant (filtering-epic "Logger
  scope") — a descendant's own level doesn't block a rule.
- **Refreshing a baseline when the server's own config changes a logger later** (F9). That affects
  every override, not just forced ones; it is a separate issue,
  [#144](https://github.com/ddeuchert/logaperture/issues/144).

## Behavior

### Which loggers get forced (F2, F3)

A **forced descendant** is a currently-known logger under the target (`com.mycompany.*`, not the
target itself) whose **own** level — the native config's or the vendor defaults file's — is set and
differs from the requested level. Those are exactly the loggers that would not follow.

- A descendant with **no level of its own** isn't forced: it already inherits, and keeps inheriting
  from whichever forced logger is nearer to it.
- A descendant whose own level **equals** the requested level isn't forced: nothing would change.
- A descendant that **already carries a LogAperture override** is left alone and reported as kept
  (F3). It's a deliberate operator choice, more specific than the parent — the same "most specific
  wins" rule as handler overrides (#135).
- Only loggers known **now** (F4). A descendant configured later (a reload adds
  `com.mycompany.new` at `WARN`) is not caught; this is a one-time action, not a standing wildcard.

### Command line

```
$ logctl set logger com.mycompany TRACE --force
com.mycompany → TRACE   (FOR, reverts 18:02 local — in 3h 59m)
com.mycompany.db.Pool → TRACE   (FOR, reverts 18:02 local — in 3h 59m, forced)
com.mycompany.other → TRACE   (FOR, reverts 18:02 local — in 3h 59m, forced)
Kept: com.mycompany.batch (DEBUG, set by you)
```

- `--force` is the confirmation: no extra question on a terminal (F7).
- The capability check covers the target and every forced descendant, all or nothing, as a pattern
  target is checked today. If any would be refused, nothing changes.
- A pattern target (`set logger '*.mycompany' TRACE --force`) forces under each exact logger picked.
- `--force` on a target with no such descendants is not an error; it just sets the target.
- A trailing `.*` stays refused (Decision #5). Its message gains: `(add --force to also set loggers
  under it that have their own level)`.

### After a plain `set logger` (F6)

When the target has loggers under it that would have been forced, one note follows the result:

```
$ logctl set logger com.mycompany TRACE
com.mycompany → TRACE   (FOR, reverts 18:02 local — in 3h 59m)
NOTE: 2 loggers under com.mycompany keep their own level and won't follow TRACE: com.mycompany.db.Pool (WARN), com.mycompany.other (INFO).
  To set them too: logctl set logger com.mycompany TRACE --force
```

A logger an earlier force tied to the target, now at a different level, is named as
`com.mycompany.other (TRACE, forced earlier)`.

More than 5 are summarized as `com.mycompany.other (INFO), … and 9 more`. Nothing is printed when
there are none.

### Guided `set logger` (F7)

After the level and lifetime questions, before the printed command:

```
2 loggers under com.mycompany have their own level and won't follow TRACE:
  com.mycompany.other    INFO
  com.mycompany.db.Pool  WARN
Set them to TRACE too? [Y/n]
```

Yes adds `--force` to the printed command. Over 30 are not listed (`14 loggers under com.mycompany
have their own level …`), as the picker's limit. Default **Yes**: the user named the parent to reach
everything under it, and the printed command plus `Apply?` still follow.

## Lifetime and reset (F1, F5, F8)

The goal: **reset puts things back the way they were found, but never undoes a change made since.**

Each forced descendant gets an ordinary logger override at the requested level, with the parent's
tier, expiry and reason, **tagged with the parent's name** (`forcedBy: com.mycompany`). This is the
mechanism `recipes.md` #7 already uses for "changed by hand since":

- **`reset logger com.mycompany`** resets the parent and every override **still tagged**
  `forcedBy: com.mycompany`. Each goes back to what it had before LogAperture touched it — its
  native or vendor level, exactly as any reset does.
- **A change made since is never undone.** A later `set logger com.mycompany.other DEBUG` replaces
  that override with an untagged one, so the parent's reset leaves `com.mycompany.other` at `DEBUG`.
  A recipe applied over it replaces the tag with the recipe's, likewise.
- **Resetting one forced descendant** (`reset logger com.mycompany.other`) resets just that one, to
  its own original level. The parent stays.
- **Expiry.** A `for 30m` force expires the parent and its forced descendants in the same sweep
  (same `expiresAt`). A sticky force resumes as a whole after restart.
- **Setting the parent again** (F5): `set logger com.mycompany DEBUG --force` re-forces the
  still-tagged descendants (and any new ones) at `DEBUG`. Without `--force`, the tagged descendants
  stay where they are, still tied to the parent for reset, and the note says so.
- **`reset loggers`** resets everything, as today.

**Known limit, shared by every override (F9).** If the server's own config changes a forced logger
afterwards (`com.mycompany.other` edited to `WARN` and reloaded), the verification sweep puts it back
at `TRACE` while the override lasts, and reset returns it to the `INFO` captured first, not the newer
`WARN`. That's how every logger and handler override behaves today: the original level is captured
once, the first time LogAperture touches a logger. Fixing it means refreshing that captured level
when the sweep sees the native config change. Filed as [#144](https://github.com/ddeuchert/logaperture/issues/144), covering all overrides,
not built here.

## Surfaces

- **JMX.** An additive `setLogger` overload with a `force` flag. `SetLevelResultData` gains `forced`
  (each forced logger with its previous level) and `kept` (descendants left alone, with why).
  Additive within the major (§11.1), so it must land before the contract review.
- **`--json`.** The same `forced` and `kept` lists on `set logger`'s output.
- **`list loggers` / `status`** show a forced override as `forced by com.mycompany` in the reason
  column when it has no reason of its own; otherwise they're ordinary overrides (F10).
- **Audit.** One `MUTATION` record per forced descendant, `origin=forced by com.mycompany`; one
  `REVERSION` per descendant reset by the parent's reset.
- **State file.** A `forcedBy:` line on a forced override, written only when set; schema 10 → 11, a
  version-10 file loads with no tag on anything (covered by the 1.0 state-file migration test).
- **`export vendor-defaults`** writes a sticky forced descendant as a plain logger entry, without
  the tag, as it does a recipe's change.
- **Multi-context (WildFly).** Each context finds its own forced descendants; a context added later
  gets the re-broadcast overrides with their tag.

## Decisions (all agreed 2026-10-03)

| # | Question | Agreed |
|---|---|---|
| F1 | What forcing does to a descendant: override it to the requested level, or clear its own level so it inherits? | **Override**, tagged with the parent. Clearing needs a new "no level" override kind and gives the same result while the parent is set. |
| F2 | Which descendants: every one with its own level, or only those stricter than the request? | **Every one whose own level differs.** Quieting (`ERROR --force`) needs the noisier ones too. |
| F3 | A descendant that already has a LogAperture override | **Kept**, and reported. The operator set it on purpose; most specific wins (#135). |
| F4 | One-time, or also catch descendants configured later? | **One-time**, loggers known now. Decision #5's case against standing wildcards. |
| F5 | Setting the parent again | With `--force`: re-force the tagged ones. Without: leave them tied, note it. |
| F6 | A note after a plain `set logger` | **Yes**, at most 5 names, with the `--force` command. |
| F7 | Confirmation and guided default | `--force` needs no extra question. Guided asks `[Y/n]`. |
| F8 | How reset avoids undoing later changes | The **`forcedBy` tag** on each forced override; a later change replaces it (recipes #7). |
| F9 | Native config changing a forced logger later | **Out of scope**: same as every override today; separate issue ([#144](https://github.com/ddeuchert/logaperture/issues/144)) to refresh the captured level. |
| F10 | How forced overrides show in `list loggers` / `status` | **`forced by <parent>`** in the reason column when there's no reason; no new column. |
| F11 | Release | **1.0** if signed off by Oct 8 (leaves the freeze week for build and test); else 1.1. |

## Testing

- Core: force sets each differing own-level descendant; skips inheriting, equal and already-overridden
  ones; parent reset reverts only still-tagged ones; a hand-set descendant survives the parent's
  reset; expiry together; sticky resume keeps tags; re-force updates tagged ones; capability
  all-or-nothing; multi-context.
- State file: `forcedBy` round trip; version-10 file loads.
- CLI: `--force` output and `--json`; the note (none, ≤5, >5); guided question, yes/no, >30;
  trailing-`.*` message mentions `--force`.
- WildFly IT: `com.mycompany.other` configured in `standalone.xml`, forced, reset — back to the
  configured level.

## Settled during implementation

Details the text above left open; none changes an agreed decision.

- **The output lines** keep `set logger`'s existing shape (`name → LEVEL   (lifetime)`), with
  `, forced` added inside the parentheses for a forced logger, rather than the `INFO -> TRACE`
  sketch the draft showed.
- **`ROOT --force`** treats every other known logger as under `ROOT`.
- **A vendor-named logger not created yet** counts as a descendant like any other, by the level the
  vendor defaults file gives it.
- **The guided question** reads what `list loggers` reports (each row's own level, and who forced
  its override), so it can be shown before anything is set; the agent still decides what is
  actually forced.
- **`--force` on the command line** is shared with `export vendor-defaults --out`, where it already
  meant "overwrite". Anywhere else it is a usage error naming both.
- **`--json`** adds `forcedBy` to each override (and to each `list loggers` row) and a
  `descendants` array (`loggerName`, `level`, `kind`) to `set logger`'s result.
- **The registry's order isn't stable**, so forced descendants are reset and reported in name
  order.

- **A force under an earlier force takes it over** (code review). A descendant tied to an
  *ancestor's* force (`com.acme.db.Pool`, forced by `com.acme`) is re-forced and re-tagged by
  `set logger com.acme.db DEBUG --force`, not kept as the operator's: nobody set it by hand. It is
  then reset with `com.acme.db`. One forced by a logger beside or below the target stays kept.
- **A plain re-set with a different lifetime is noted too** (code review): with the same level but
  a new tier, or any `for`, the tied descendants keep their own lifetime, so the note names them.
- **A forced descendant shares the parent's `appliedAt`**, so a `for` force has one deadline.
- **`reset logger` shows what it put back**: each forced descendant reset with the target, marked
  `(forced by <target>)`, and any sticky one left in place; `--json` carries both as
  `forcedRevertedLoggerNames` and `forcedSkippedStickyLoggerNames`.
- **`logctl` calls the new JMX operation only for `--force`**, so a plain `set logger` keeps working
  against an agent from before this feature.
- **A pattern with `--force`**: a match under another match is one of the command's own targets,
  neither forced nor reported as kept; the capability check covers every forced descendant, as for
  an exact target.
