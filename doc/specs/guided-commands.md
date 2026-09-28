# Guided `list`, `set`, `reset` and `alter rule` (issue #116)

Status: **signed off 2026-09-27** (#1–#20 agreed; #19 revised at sign-off, see below). Slice (a) —
guided `list` and `set` — **implemented** (see "Settled during implementation").
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.15 (guided `add rule`, whose
G11 named these as follow-ups).
Builds on: [`guided-add-rule.md`](guided-add-rule.md) (when guided mode starts, picking loggers from
a numbered list, the printed command, `Apply? [Y/n]`, `--yes`/`--json`),
[`pick-jvm.md`](pick-jvm.md) (the shared one-question-at-a-time prompter),
[`list-command-surface.md`](list-command-surface.md), [`set-command-surface.md`](set-command-surface.md),
[`reset-command-surface.md`](reset-command-surface.md), [`reset-to-native.md`](reset-to-native.md),
[`alter-rule.md`](alter-rule.md), [`pattern-selection-semantics.md`](pattern-selection-semantics.md).

## Functional summary

After this feature, the user will be able to:

- Type `logctl list`, `logctl set` or `logctl reset` on its own on a terminal and be asked what
  they want, one question at a time, instead of getting a usage error.
- Type `logctl set logger Deployer` and pick the logger from a numbered list, see its current
  level, then be asked for the new level and how long it should last.
- Type `logctl reset` and pick, from a numbered list of everything currently changed — loggers,
  handlers and rules together — what to put back.
- Type `logctl alter rule` to pick a rule from a list, or `logctl alter rule r3` to see its current
  settings and change just the parts they pick.
- See the ordinary one-line `logctl …` command the answers add up to before anything changes, and
  copy it into a script or runbook — exactly as guided `add rule` already does.
- Keep using complete commands in scripts exactly as today: nothing prompts when the command is
  complete, when there is no terminal, or with `--yes` or `--json`.

## Why

Guided `add rule` (#104) was the most-liked change in alpha-3 manual testing: the operator types
what they know — often only the short logger name a log line shows — and is asked for the rest.
The other everyday commands have the same problem it solved:

- `set logger` needs the full logger name, so it starts with a `list loggers '*.Deployer'` lookup,
  exactly as `add rule` did.
- `reset` has six forms (`logger`/`loggers`/`handler`/`handlers`/`rule`/`rules`, plus
  `default-handler`) and two options (`--include-sticky`, `--to-native`); an operator who wants
  "undo what I did an hour ago" has to know which form and look up the names or ids first.
- `alter rule <id>` needs the id from `list rules`, then the exact spelling of each option and its
  `--no-` form.
- `list` alone is a usage error, though it has only three answers.

## What stays the same (applies throughout)

These carry over from `guided-add-rule.md` unchanged and are not reopened here:

- **When it prompts** (G1): stdin is a terminal, neither `--yes` nor `--json` is given, and a
  required part is missing. Anything given on the command line is used as-is and not asked about.
- **Without a terminal, or with `--yes`/`--json`**, a missing part is the same usage error as today,
  plus one line: `Run this in a terminal to be prompted for the missing parts.`
- **Picking loggers** (G4, G5): a typed answer with no `*` and no `.` is searched as `*.<answer>`;
  one match is used directly; several are numbered, answered with `1,3-5`, `all` or Enter to
  cancel; more than 30 asks for a narrower pattern.
- **Invalid answers** are explained and asked again, using the command line's own checks.
- **End of input (Ctrl-D)** at any question: `Not applied.`, exit 0.
- **The prompter**: numbered lists and typed answers, no JLine (G12).

## Decisions

### Common to all four

**#1 — Every guided command that changes something prints its command and asks `Apply? [Y/n]`.**
As G7/G8: one line per selected item, exact names and ids (never the pattern), only non-default
options, shell-quoted; Enter applies. The result then prints exactly as the non-guided command's
does. With several items, a refused one doesn't stop the others and the exit code is the first
failure's (G10).

**#2 — Guided `list` prints its command but doesn't ask to confirm.** It changes nothing, so the
command is printed as a one-line hint (`Command: logctl list loggers "*.Deployer" --show-all`)
and the listing follows straight away.

**#3 — Handlers and rules are picked from numbered lists too.** Same answer format as loggers
(`1,3-5`, `all`, Enter to cancel). They are never too many to list, and there is no `*.<answer>`
shorthand — the list is the whole catalog (handlers) or every attached rule (rules).

### `logctl list`

**#4 — `logctl list` alone asks which.**

```
$ logctl list
List loggers, handlers or rules? [loggers/handlers/rules]
> l
Logger name or pattern, e.g. Deployer or org.jboss (Enter for every overridden logger)
> Deployer
Command: logctl list loggers "*.Deployer" --show-all
LOGGER                                     LEVEL  ...
```

`l`/`h`/`r` are accepted, like `d`/`t` for drop/trim.

**#5 — Guided `list loggers` asks for a filter; a typed filter implies `--show-all`.** Someone
typing a name is looking for a logger, not only an overridden one — without `--show-all` the
likely answer is an empty table. An empty answer lists the overridden loggers, as bare
`list loggers` does. `list handlers` and `list rules` ask nothing more: `--show-all` and
`--verbose` stay flags.

### `logctl set`

**#6 — `logctl set` alone asks which:** `Set a logger, a handler, or the default handlers?
[logger/handler/default-handler]` (`l`/`h`/`d`).

**#7 — Guided `set logger`.** Any part missing — target, level — is asked for:

```
$ logctl set logger Deployer
1 logger matches '*.Deployer': org.jboss.as.server.deployment.Deployer (INFO, native)
Level? e.g. DEBUG, TRACE, OFF
> DEBUG
How long? session, for <duration> (e.g. for 30m), or sticky [for 4h]
>
Reason (optional)
> chasing the redeploy loop

This is the command:
  logctl set logger org.jboss.as.server.deployment.Deployer DEBUG --reason "chasing the redeploy loop"
Apply? [Y/n]
>
org.jboss.as.server.deployment.Deployer → DEBUG   (for 4h, expires 2026-09-28 02:14)
```

- Each match shows its current effective level and where it comes from (native, vendor,
  override), so the operator can see what they are changing.
- Level has no default: it must be answered. Lifetime defaults to `for 4h`, as a bare
  `set logger` does. Reason is optional.
- An exact name no logger has yet is accepted after `No logger named X exists yet; set it
  anyway? [y/N]`, as in guided `add rule`.
- A bare `set logger org.jboss DEBUG` (complete) runs as today — no questions.

**#8 — Guided `set handler`.** The handler is picked from the `list handlers` catalog, numbered,
including `ALL_HANDLERS` and `DEFAULT_HANDLERS`, each with its current level. The level question
also accepts `AUTO`. Lifetime and reason as #7.

**#9 — Guided `set default-handler`.** The current membership is shown, then the handlers are
listed and the answer (`1,3`) is the new membership. It's the rarest of the three, but without it
`logctl set` would need to answer "default handlers" with a usage error.

**#10 — A complete `set logger '<pattern>' <level>` on a terminal uses the pick list.** Today it
previews every match and asks `Apply? [y/N]` (all or nothing). Guided `add rule` (G2) settled on
the numbered pick list with `all` for the same situation. This aligns `set logger` with it, so a
pattern behaves the same way in both commands. More than 30 matches keeps today's preview and
`[y/N]`, since there is no narrower pattern to ask for on a complete command. Without a terminal,
and with `--yes`, nothing changes.

### `logctl reset`

**#11 — `logctl reset` alone lists everything currently changed and asks which.** It does not ask
"logger, handler or rule?" first: the operator usually knows *what* they changed, not which
`reset` form applies to it. One numbered list, grouped:

```
$ logctl reset
Currently changed:
  Loggers
    1  org.jboss.as.server.deployment.Deployer  DEBUG   for 4h, 3h 12m left
    2  com.acme.batch                           TRACE   sticky
  Handlers
    3  CONSOLE                                  DEBUG   session
  Rules
    4  r12  trim org.jboss.as.server.deployment.Deployer  for 1d
    5  vendor:quiet-perfmon  drop com.acme.perf  (altered)
  Default handlers
    6  CONSOLE, FILE                                    sticky
Which? (e.g. 1,3 or 1-2 or all; Enter to cancel)
> 1,4
```

The printed command is one line per item (`logctl reset logger <name>`, `logctl reset rule r12`),
never `reset loggers`: the bulk forms would also reach whatever else is changed by the time the
line is reused. Nothing changed: `Nothing is changed; nothing to reset.`, exit 0.

**#12 — `reset logger`, `reset handler` or `reset rule` without a name or id lists only that kind.**
Same list, one group. (`reset loggers`/`handlers`/`rules` are already complete and don't prompt.)
A `reset logger <pattern>` target runs as today.

**#13 — Sticky items are listed, marked, and confirmed once.** A sticky item is listed with
`sticky`. If any picked item is sticky, one question follows: `2 of these are sticky — they are
kept across restarts. Reset them too? [y/N]`. Yes adds `--include-sticky` to those lines only; no
drops them from the selection (and says so). This replaces having to know the option.

**#14 — `--to-native` is asked only when it means something.** When the JVM has a vendor defaults
file and a picked item has a vendor baseline or is a vendor rule, one question:
`Go back to the vendor defaults, or to the application's own configuration until restart?
[vendor/native]` (Enter: vendor). `native` adds `--to-native` to those lines. With no vendor
defaults file it is never asked.

**#15 — Unaltered vendor rules are listed.** `reset rule vendor:<id> --to-native` is how a vendor
rule is switched off, so it belongs in the list even when unchanged. Picking one and answering
`vendor` at #14 leaves it as it is: it is reported as `already the vendor's definition` and left
out of the printed command.

### `logctl alter rule`

**#16 — `alter rule` without an id picks one rule** from a numbered list of every rule, shown the
way `list rules --verbose` shows it (id, logger, action, options). Exactly one — `alter` changes
one rule at a time, so the answer is a single number.

**#17 — `alter rule <id>` with no changes lists the rule's parts and asks which to change.**

```
$ logctl alter rule r12
r12  trim org.jboss.as.server.deployment.Deployer
  1  Message contains          "Failed to connect"
  2  Ignore case               no
  3  Exception class           java.net.ConnectException
  4  Exception message         —
  5  Any cause                 no
  6  Below level               FATAL
  7  Stack frames to keep      0
  8  Collapse cause chain      no
  9  Lifetime                  for 1d, 20h left
 10  Reason                    perfmon auto-update noise
Change which? (e.g. 3,6; Enter to cancel)
> 3,6
Exception class [java.net.ConnectException] ('-' to remove)
> -
Apply to events below which level? [FATAL]
> WARN

This is the command:
  logctl alter rule r12 --no-throwable --below WARN
Apply? [Y/n]
```

The alternative — walking every question with the current value as the default — takes ten
Enters to change one thing. Picking the parts matches `alter`'s own "give only what changes".

- Only the action's own parts are listed (drop: sampling; trim: frames and collapse).
- `-` removes an optional part (the `--no-` option). A required part can't be removed: a drop rule
  left with no matcher is refused and re-asked, with the message the command line gives.
- The action and the logger aren't listed: they can't change (`alter-rule.md`).
- A vendor rule's lifetime follows `alter-rule.md`: if lifetime isn't picked, the command line's
  default for a vendor rule (`for 4h`) applies, and the list says so on its row.

**#18 — An answer that changes nothing applies nothing.** If every picked part is given its
current value: `Nothing changed. Not applied.`, exit 0 — no empty `alter rule r12` call.

### Delivery

**#19 — Part of 1.0.0-beta.1, in three slices; the beta waits for it.** One issue, this one
spec, three PRs: (a) the shared pick lists, guided `list` and `set` (#2–#10); (b) guided `reset`
(#11–#15); (c) guided `alter rule` (#16–#18). All three ship in beta 1. If they aren't done by the
planned Oct 15 date, beta 1 moves out rather than any slice moving to 1.1: the guided commands are
what makes the beta usable for testers who don't already know the syntax. Slice (a) lands first
because it carries #10, the one change to how a complete command behaves, and so gets the most
testing time.

*Revised at sign-off:* the draft proposed that each slice could move to 1.1.0 separately if beta 1
got tight.

**#20 — `guided-add-rule.md` G11 links here**, and §18.15 gets one sentence naming the follow-up.
No new §18 roadmap entry: this is the follow-up §18.15 already anticipated.

## Out of scope

- **Guided `status`, `doctor`, `top`, `storms`, `env`, `export`.** They take no required
  arguments, so there is nothing missing to ask for.
- **Chaining commands** — e.g. `list loggers` offering "set one of these?" afterwards. Each guided
  command ends with its result, as today.
- **Arrow-key selection (JLine)** — as G12.
- **The Windows cmd colour artefact** seen in alpha-3 manual testing — not reproduced, tracked
  separately if it recurs.

## Implementation notes

- **No agent or MXBean change.** Everything the prompts show comes from calls that already exist:
  `listLoggers(filter)` (current levels and sources), `listHandlers()`,
  `listHandlerOverrides()`, `listRules()` (every rule field, `isAltered()`, `getOrigin()`), and
  the default-handler membership from `listHandlers()` (`DEFAULT_HANDLERS`' `getMembersSummary()`).
  That row's summary already tells an explicitly assigned membership (plain names) from the
  automatic pick (`(auto: …)`) and the vendor defaults' list (`(vendor: …)`), which is what #11
  needs to list it as "changed".
- The logger picker, the numbered-selection parser (`1,3-5`, `all`) and the printed-command
  quoting in `AddRuleCommand` move to a shared place used by all five guided commands;
  `AddRuleCommand` keeps its behavior.
- `Commands.readYesAnswer` (a new `BufferedReader` per call) is replaced with `Prompter`, so every
  question in a run reads from the same stream without read-ahead (`pick-jvm.md` J9).
- `Parser` stops rejecting a bare `list`, `set`, `set logger <target>`, `reset`,
  `reset logger|handler|rule` and `alter rule [<id>]`, and produces a guided command instead; the
  completeness check moves to run time, as it did for `add rule`. Non-interactive usage errors
  keep their current text, plus the hint line.
- The printed commands are fed back through `Parser` in tests (round trip), as for `add rule`.
- `HelpText`: the note `On a terminal, 'add rule' asks for anything left out` becomes a general one
  covering all five.

## Settled during implementation

Small points the decisions above left open, settled while building slice (a):

- **A short name on the command line.** `set logger Deployer` with no level looks up `*.Deployer`,
  as the #7 sample shows, because an incomplete command has no meaning of its own to keep. That is
  wider than guided `add rule`'s G4, where only a typed answer gets the shorthand; `add rule` is
  unchanged.
- **One match (#10).** A complete `set logger '<pattern>' <level>` on a terminal whose pattern
  matches exactly one logger shows the match and applies it without a question, as `add rule`'s
  pick list does (G5). Before, it asked `Apply? [y/N]` even for one match. No match asks for
  another pattern, also as `add rule` does.
- **Printed commands quote with double quotes**, like guided `add rule` and `list rules --verbose`
  (`"*.Deployer"`), including the `Command:` hint `list` prints.
- **`list`** ends quietly on Ctrl-D (there is nothing to "not apply"). `--show-all` given with a
  bare `list` is kept for loggers and handlers; answering `rules` with it is `list rules`'s own
  usage error.
- **`set handler`** always shows the numbered list, even with one real handler, since the
  catalog always includes `ALL_HANDLERS` and `DEFAULT_HANDLERS`. A handler present in several
  logging contexts is listed once. A framework whose handlers have no level (Logback) says so and
  asks nothing.
- **`set default-handler`** lists only real handlers: `ALL_HANDLERS` and `DEFAULT_HANDLERS` can't
  be members.
- **Several loggers or handlers:** a blocking-handler warning after `set logger` is printed once
  per handler, however many of the chosen loggers it affects.

## Testing

- `MainRunTest`, through `Main.run(…, in, interactive)`, per command: the sample session end to
  end against a fake MXBean; each skip condition; invalid answers re-asked; EOF at each question
  prints `Not applied.` and changes nothing.
- `reset`: sticky yes/no (#13); vendor/native asked only with a vendor defaults file (#14); an
  unaltered vendor rule answered `vendor` is left out (#15); nothing changed.
- `alter rule`: `-` removal; drop left without a matcher re-asks; no-op answers apply nothing (#18).
- `set logger '<pattern>'` complete command on a terminal: pick list, `all`, subset, more than 30
  (#10); without a terminal and with `--yes`, unchanged.
- Round trip: every printed command parses to the same MXBean call.
- Non-interactive: every current usage error unchanged apart from the hint line.

## Decision table

| # | Decision | Status |
|---|---|---|
| 1 | Guided set/reset/alter print one line per item and ask `Apply? [Y/n]` | **Agreed** |
| 2 | Guided `list` prints its command as a hint, no confirmation | **Agreed** |
| 3 | Handlers and rules are picked from numbered lists, same answer format | **Agreed** |
| 4 | `logctl list` alone asks loggers/handlers/rules | **Agreed** |
| 5 | Guided `list loggers` asks a filter; a typed filter implies `--show-all` | **Agreed** |
| 6 | `logctl set` alone asks logger/handler/default-handler | **Agreed** |
| 7 | Guided `set logger`: pick, show current level, level (no default), `for 4h`, reason | **Agreed** |
| 8 | Guided `set handler`: pick from the catalog incl. ALL/DEFAULT_HANDLERS; `AUTO` accepted | **Agreed** |
| 9 | Guided `set default-handler`: show membership, pick the new one | **Agreed** |
| 10 | A complete `set logger '<pattern>'` on a terminal uses the pick list (>30 keeps today's preview) | **Agreed** |
| 11 | `logctl reset` alone lists everything changed, grouped, multi-pick; one line per item | **Agreed** |
| 12 | `reset logger`, `handler` or `rule` without a name lists only that kind | **Agreed** |
| 13 | Sticky items listed and confirmed once (`--include-sticky` per line) | **Agreed** |
| 14 | `--to-native` asked only with a vendor defaults file and a vendor-baselined pick | **Agreed** |
| 15 | Unaltered vendor rules are listed; `vendor` answer leaves them out | **Agreed** |
| 16 | `alter rule` without an id picks exactly one rule | **Agreed** |
| 17 | `alter rule <id>` lists the parts and asks which to change; `-` removes | **Agreed** |
| 18 | A no-op answer applies nothing | **Agreed** |
| 19 | Beta 1, three slices (list+set, reset, alter); the beta date moves before a slice does | **Agreed** (revised) |
| 20 | Link from G11 and §18.15; no new roadmap entry | **Agreed** |
