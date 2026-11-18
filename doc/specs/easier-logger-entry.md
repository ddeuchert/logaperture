# Easier logger entry in guided `set` (issue #TBD)

Status: **draft — not yet signed off.**
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.15 (guided commands).
Builds on: [`guided-add-rule.md`](guided-add-rule.md) "Picking the loggers" (the numbered list, G4/G5,
the `Which logger?` question), [`guided-commands.md`](guided-commands.md) (#3, #7: the guided
`set logger` flow and the `Level?` question), [`set-command-surface.md`](set-command-surface.md).
Milestone: 1.1.0.

## Functional summary

After this feature, the user will be able to:

- Type `?` at `Which logger?` to pick from the loggers they set or added rules to recently, instead
  of typing the name again.
- When there is no history yet, type `?` to see the top-level packages the JVM has loggers in, to
  know what to type.
- Page through a long list of matching loggers, and type a shorter name to narrow it, instead of
  being told to try a narrower pattern.
- Answer the `Level?` question with one letter: `i`, `d`, `t`, `w`, `e` or `o`.
- Keep using complete commands in scripts exactly as today: nothing about a command that already
  names its logger and level changes.

Guide pages changed: [Raise a log level](../../guide/how-to/raise-a-level.md) ("Let `logctl` ask"),
[Find a logger](../../guide/how-to/find-a-logger.md) ("Let `logctl` pick").

## Why

Guided `set logger` (#116) removed the need to know a logger's full name, but each use still costs
more typing than it should for the case it exists for: an operator changing the same few loggers
repeatedly while chasing a problem.

1. They retype the same name each time. Nothing remembers what they set a minute ago.
2. When a name matches too many loggers, the answer is "too many to list; try a narrower pattern".
   The operator has to guess a pattern and start the question again, with nothing on screen to guess
   from.
3. With no idea what to type, the only options were to cancel and run `logctl list loggers`.
4. The level has to be typed in full (`DEBUG`) every time.

## Decisions

**E1 — Recent loggers.** A successful `set logger` or `add rule` records each logger it changed,
most recent first, without duplicates, keeping the last 50. A logger a command was refused for is
not recorded, and neither is a pattern (`'*.Worker'`): only the exact loggers it applied to. The history belongs to the operator running `logctl`, not to the JVM, so it is the
same whichever JVM they attach to. It is kept under the same home as the agent's state
(`-Dlogaperture.home`, default `~/.logaperture`). A history that can't be read or written never
fails a command: it reads as empty and a failed write is ignored.

**E2 — `?` at `Which logger?` shows what to type.** The question becomes
`Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)`.
`?` lists the recent loggers (E1), numbered, answered with the usual `1,3-5` / `all` / Enter to
cancel; cancelling that list asks `Which logger?` again. With no history, `?` says
`No history yet.` and shows the top-level packages (E3) instead, then asks again. Enter still
cancels, as at every other guided question. The hint is always shown, so the prompt doesn't change
with what happens to be in the history. A bare `?` is not a logger name or pattern, so nothing that
worked before is taken over.

**E3 — Top-level packages.** The first two name segments (`org.hibernate`, `com.acme`) of the
loggers the JVM knows, sorted, with a note of how many loggers they were taken from. Two segments
rather than one because `org` and `com` say nothing. It looks at no more than the first 200 loggers
so it stays quick, and says so (`from first 200 loggers`) — it is a hint for what to type, not a
complete list.

**E4 — Paging and narrowing.** Matches are listed 50 to a page (was 30, and refused beyond that).
`Page 1 of N` is shown when there is more than one page. A name typed at `Which logger?` is
searched as before (G4: a bare word is `*.<word>`). At the numbered list:

- a selection (`1,3-5`, `all`) picks from the whole list, whichever page it is on. An answer made
  only of digits, commas, dashes and spaces, or `all`, is always meant as a selection: if it isn't
  a valid one it is explained and asked again, as every other selection is, and never searched;
- Enter shows the next page, or cancels on the last;
- anything else is a **narrower name**, searched as a prefix: `org.hibernate` becomes
  `org.hibernate.*`, and the narrowed matches are listed the same way. A name with a `*` in it is
  used as typed.

**E5 — Level letters.** `i`=INFO, `d`=DEBUG, `t`=TRACE, `w`=WARN, `e`=ERROR, `o`=OFF, `a`=ALL,
case-insensitive, accepted at the `Level?` question of guided `set`. The prompt shows
`Level? (i=INFO, d=DEBUG, t=TRACE, w=WARN, e=ERROR, o=OFF)`, plus `or AUTO` where AUTO is allowed
(handlers). Full names work as before. `a` is ALL, never AUTO, and is not listed in the prompt.

**E6 — Nothing changes for complete commands.** `logctl set logger <name> <level>` and every
non-terminal, `--yes` or `--json` use are untouched (G1). Level letters are a prompt convenience and
are not accepted on the command line.

## Scope of the change

The logger picker is shared by guided `set logger` and guided `add rule`, so E2 and E4 apply to
both, and both record to the history (E1), including a complete `add rule <name> …` or
`set logger <name> <level>` run from a terminal or a script. `list`, `reset` and `alter rule` don't
ask `Which logger?` and are unchanged, as are rule ids. E5 applies to the `Level?` questions of
`set logger` and `set handler`; `add rule`'s level options are not asked through them and do not
change.

## Open questions

1. Should `a` mean ALL, given that AUTO is also a level answer at the handler question? It is
   accepted but not shown in the prompt, which makes it easy to hit by mistake.
2. Is a history file in the operator's home directory acceptable where `logctl` is run from a shared
   or jump host account? (It holds only logger names.)

## Tests

- Recording: most-recent-first, de-duplication, the 50-entry cap, an unreadable and an unwritable
  history.
- `Which logger?` + Enter cancels. `?` with history (pick one, `1,3`, cancel and asked again) and
  without history (`No history yet.`, packages, asked again).
- Recording from `set logger` and `add rule`, for a picked, a typed and a refused logger.
- Paging: exactly 50, 51 and 120 matches; Enter advances; a selection on page 2 picks the right
  names; `all` picks every match.
- Narrowing: a typed name replaces the list; a name with `*` is used as typed. An invalid
  selection (`1,x`, `0`, `99`, `3-1`) is explained and asked again, never searched.
- Level letters: each letter, upper case, an unknown letter (asked again), `AUTO` where allowed.
- The existing tests that assert `too many to list` and `Enter to cancel` are updated for E2/E4.
