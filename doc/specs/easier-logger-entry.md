# Easier logger entry in guided `set` (issue #402)

Status: **decisions agreed 2026-11-17** (see "Settled at review"), E4a on 2026-11-25; implemented on this
branch.
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
- Pick from the same paged list when a complete `set logger` with a pattern matches many loggers on
  a terminal, instead of answering one all-or-nothing `Apply?`.
- Keep using complete commands in scripts as today: with an exact logger name, with `--yes`, or
  without a terminal, a command does what it did before.

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
rather than one because `org` and `com` say nothing. It is taken from every logger the JVM knows
(`from 149 loggers`), keeping only the deepest of each family (`com.shop`, not `com` beside it), and
leaving out `ROOT`. It is a hint for what to type: use `list loggers` for the full list.

**E4 — Paging and narrowing.** Matches are listed 50 to a page (was 30, and refused beyond that).
`Page 1 of N` is shown when there is more than one page. A name typed at `Which logger?` is
searched as before (G4: a bare word is `*.<word>`). At the numbered list:

- a selection (`1,3-5`, `all`) picks from the whole list, whichever page it is on. An answer made
  only of digits, commas, dashes and spaces, or `all`, is always meant as a selection: if it isn't
  a valid one it is explained and asked again, as every other selection is, and never searched;
- Enter cancels, on every page, as at every other guided question. `n` shows the next page and `p`
  the previous one; the prompt names the keys that apply on the page being shown;
- anything else is a **narrower name**, and the narrowed matches are listed the same way. A name
  with a `*` in it is used as typed. A name with a `.` is a prefix: `org.hibernate` and
  `org.hibernate.` both become `org.hibernate.*`. A bare word means what it means at `Which logger?`
  (G4): `Worker` is `*.Worker`.

**E4a — Complete commands page too.** A complete `set logger '*.Worker' DEBUG` on a terminal opens
the pick list however many loggers match, paged as in E4 (unless `--yes` or `--json` is given, E6).
Up to 30 matches already did ([`guided-commands.md`](guided-commands.md) #10); the all-or-nothing
`Apply?` preview for more is gone. `all` still sets every match,
on every page. `--yes` and a command with no terminal are unchanged (`--yes` takes every match
without asking; no terminal is the usual usage error). *(Reverses the first draft of this feature,
which kept the preview above 30; agreed 2026-11-25.)*

**E5 — Level letters.** `i`=INFO, `d`=DEBUG, `t`=TRACE, `w`=WARN, `e`=ERROR, `o`=OFF,
case-insensitive, accepted at the `Level?` question of guided `set`. The prompt shows
`Level? (i=INFO, d=DEBUG, t=TRACE, w=WARN, e=ERROR, o=OFF)`, plus `or AUTO` where AUTO is allowed
(handlers). Full names work as before, `ALL` included. There is deliberately no `a`: it could be
meant as AUTO at the handler question, so it is explained and asked again like any unknown answer.

**E6 — Nothing else changes for complete commands.** A complete command with an exact logger name,
with `--yes`, or without a terminal behaves as before, apart from being recorded in the history (E1).
The one change is a pattern on a terminal (E4a). `--json` never asks, even on a terminal
([`cli-transport.md`](cli-transport.md#when-logctl-asks), issue
[#422](https://github.com/ddeuchert/logaperture/issues/422)), so a pattern with `--json` needs `--yes`.
Level letters are a prompt convenience and are not accepted on the command line.

## Scope of the change

The logger picker is shared by guided `set logger` and guided `add rule`, so E2 and E4 apply to
both, and both record to the history (E1), including a complete `add rule <name> …` or
`set logger <name> <level>` run from a terminal or a script. `list`, `reset` and `alter rule` don't
ask `Which logger?` and are unchanged, as are rule ids. E5 applies to the `Level?` questions of
`set logger` and `set handler`; `add rule`'s level options are not asked through them and do not
change.

## Settled at review

- Enter keeps cancelling at `Which logger?`; recent loggers are behind `?` (E2).
- `add rule` records to the same history as `set logger` (E1).
- The history file lives in the operator's home directory. It holds only exact logger names, and
  `-Dlogaperture.home` moves it, as it does the agent's state (E1).
- There is no `a` level letter: it could be meant as ALL or as AUTO (E5).

There are no open questions.

## Tests

- Recording: most-recent-first, de-duplication, the 50-entry cap, an unreadable and an unwritable
  history. Several loggers picked at once are recorded together (the first picked listed first, the
  earlier history kept). Blank lines in the file are ignored; the file is replaced atomically.
- `Which logger?` + Enter cancels. `?` with history (pick one, `1,3`, cancel and asked again) and
  without history (`No history yet.`, packages, asked again).
- Recording from `set logger` and `add rule`, for a picked, a typed and a refused logger.
- Paging: exactly 50, 51 and 120 matches, from guided and from a complete `set logger`; `n` and `p` move between pages, Enter cancels on any; a selection on page 2 picks the right
  names; `all` picks every match.
- Narrowing: a typed name replaces the list; a name with `*` is used as typed; a trailing dot is a prefix; a bare
  word is `*.<word>`. An invalid
  selection (`1,x`, `0`, `99`, `3-1`) is explained and asked again, never searched.
- Level letters: each letter, upper case, an unknown letter (asked again), `AUTO` where allowed.
- A pattern `set logger --json --yes` sends the pattern in one call and prints one object with every
  key. `--json` without `--yes` fails without asking (the cases #422 added).
- The existing tests that assert `too many to list` and `Enter to cancel` are updated for E2/E4.
