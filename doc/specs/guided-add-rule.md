# Guided `logctl add rule` (issue #104)

Status: **signed off 2026-09-26** (G1–G12 agreed); **implemented** (see "Settled during
implementation").
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.15 (roadmap entry), §17
("Pulled toward the alpha line: guided `logctl add rule`").
Builds on: [`drop-rule.md`](drop-rule.md) and [`trim-rule.md`](trim-rule.md) (the options and their
defaults), [`pattern-selection-semantics.md`](pattern-selection-semantics.md) (a leading-star
pattern is a one-time selection over currently-known loggers, previewed and confirmed),
[`filtering-epic.md`](filtering-epic.md) "Targeting by pattern is shorthand, not a standing rule".
Related: issue #79 (multi-context attachment for `add rule`); its pattern-target half moved here (G2).

## Functional summary

After this feature, the user will be able to:

- Type `logctl add rule '*.Deployer'` on a terminal and pick the loggers the rule is for from a
  numbered list of the loggers that match, instead of looking up the full name first.
- Be asked, one question at a time, for everything else the rule needs — drop or trim, what to
  match, below which level, how long it lasts — with the default shown for each.
- See the ordinary one-line `logctl add rule …` command the answers add up to before anything is
  applied, and copy it into a script or runbook.
- Give a leading-star pattern to `add rule drop|trim` on the command line as well, previewed and
  confirmed like `set logger`, or applied to every match with `--yes`.
- Keep using complete `add rule` commands in scripts exactly as today: nothing prompts when the
  command is complete, when there is no terminal, or with `--yes`.

## Why

Manual testing of `drop`/`trim` showed that applying a rule costs more than it should:

1. A log line usually prints only the last segment of its logger category
   (`… [Deployer] …`), so the first step is `logctl list logger '*.Deployer'` to find the full name.
2. A conditional rule is then one long command in exact syntax —
   `logctl add rule trim org.jboss.as.server.deployment.Deployer --throwable
   java.net.ConnectException --message-contains "Failed to connect" --below FATAL for 1d`.
   An operator writes one of these a few times a year, usually under pressure while looking at the
   offending line, and won't have that syntax memorized. `--help` lists the options but not which
   ones matter for the case in front of them.

## Command surface

```
logctl add rule [drop | trim] [<target>] [options] [session | for <duration> | sticky] [--reason <text>] [--yes] [--json]
```

Only the shape changes: the rule type and the target become optional **on a terminal**. The options
themselves, their meaning and their defaults are exactly `drop-rule.md`'s and `trim-rule.md`'s.

### When it prompts (G1)

Guided mode starts when **all** of these hold:

- stdin is a terminal (`System.console() != null`, the same test `set logger`'s confirmation
  already uses);
- neither `--yes` nor `--json` is given (G9);
- a required part is missing: the rule type, the target, or — for `drop` — a content matcher
  (`drop-rule.md` requires at least one).

In guided mode it asks for every part **not** given on the command line, required or optional.
Anything given on the command line is used as-is and not asked about. So these all work:

```
logctl add rule                                   # asks for everything
logctl add rule '*.Deployer'                      # picks loggers, then asks the rest
logctl add rule trim '*.Deployer'                 # picks loggers, skips the type question
logctl add rule drop com.acme.Worker --below WARN # asks for a matcher, sampling, lifetime, reason
```

A command that is already complete runs exactly as today, with no questions — except that a
leading-star target is now previewed and confirmed rather than rejected (see "Pattern targets").

Without a terminal, or with `--yes`, a missing part is the same usage error it is today, with one
added line: `Run this in a terminal to be prompted for the missing parts.`

### The rule type is optional (G3)

`add rule` followed by a word other than `drop` or `trim` treats that word as the target:
`logctl add rule '*.Deployer'`. A logger literally named `drop` or `trim` at the root of the
hierarchy is still reachable by naming the type first: `logctl add rule drop drop`.

## The guided flow

A sample session; operator input follows each `>`.

```
$ logctl add rule '*.Deployer'
3 loggers match '*.Deployer':
  1  org.jboss.as.server.deployment.Deployer
  2  org.jboss.as.ejb3.deployment.Deployer
  3  com.acme.batch.Deployer
Which? (e.g. 1,3 or 1-2 or all; Enter to cancel)
> 1

Drop or trim? [drop/trim]
> trim

What should the rule match? Leave a question empty to skip it.
  Message contains
  > Failed to connect
  Ignore case? [y/N]
  >
  Exception class (fully qualified)
  > java.net.ConnectException
  Exception message contains
  >
  Also look for the exception anywhere in the cause chain? [y/N]
  >

Apply to events below which level? [ERROR]
> FATAL
Stack frames to keep [0]
>
Collapse the cause chain too? [y/N]
>
How long should the rule last? session, for <duration> (e.g. for 30m), or sticky [for 4h]
> for 1d
Reason (optional)
> perfmon auto-update noise

This is the command:
  logctl add rule trim org.jboss.as.server.deployment.Deployer --message-contains "Failed to connect" --throwable java.net.ConnectException --below FATAL for 1d --reason "perfmon auto-update noise"
Apply? [Y/n]
>
r12   org.jboss.as.server.deployment.Deployer → trim   (for 1d, expires 2026-09-28 14:02)
```

### Picking the loggers (G4, G5)

- The target, given or typed at the prompt, is looked up the way `logctl list loggers <target>`
  looks it up.
- **A typed answer with no `*` and no `.` is searched as `*.<answer>`** — `Deployer` finds
  `*.Deployer` — because that is what a log line shows. This applies only to an answer typed at
  the prompt, never to a target given on the command line, which keeps its current meaning.
- **One match** is shown and used without a numbered question.
- **No match** says so and asks for another pattern (Enter cancels). An exact name that no logger
  has yet is accepted as today — a rule can be attached ahead of the logger's creation — after a
  `No logger named X exists yet; attach anyway? [y/N]` question.
- **Several matches** are numbered in name order. The answer is a comma-separated list of numbers
  and ranges (`1,3-5`), `all`, or Enter to cancel. An invalid answer is explained and asked again.
- **More than 30 matches** are not listed; the operator is told how many matched and asked for a
  narrower pattern.
- A trailing `.*` is refused, as today (`drop-rule.md`): a bare name already reaches every
  descendant.

### The remaining questions (G6)

Asked in this order, each only if not already given on the command line; a question that depends
on an earlier answer is skipped when that answer makes it meaningless.

| # | Question | Default (Enter) | Asked when |
|---|---|---|---|
| 1 | Drop or trim? | none — must answer | no type given |
| 2 | Message contains | skip | always |
| 3 | Ignore case? | no | 2 was answered |
| 4 | Exception class | skip | always |
| 5 | Exception message contains | skip | always |
| 6 | Also look for the exception anywhere in the cause chain? | no | 4 or 5 was answered |
| 7 | Apply to events below which level? | `ERROR` | always |
| 8 | drop: Keep one full event every… (duration, or `off`) | `5m` | drop |
| 8 | trim: Stack frames to keep | `0` | trim |
| 9 | trim: Collapse the cause chain too? | no | trim |
| 10 | How long should the rule last? | `for 4h` | always |
| 11 | Reason | none | always |

- For `drop`, skipping all of 2, 4 and 5 is refused and the matcher questions are asked again:
  `A drop rule needs at least one of these — otherwise use 'logctl set logger' to change the
  level.`
- Every answer is checked as it is given (a level name, a duration, a number ≥ 0) and asked again
  with the reason if it is invalid. Validation uses the same checks the command line uses, so the
  two can't disagree.
- End of input (Ctrl-D) at any question cancels: `Not applied.`, exit 0 — the same outcome as
  answering `n` to `set logger`'s confirmation.

### The command and confirmation (G7, G8)

After the last question, the equivalent command is printed and confirmed:

- **One line per selected logger**, each with its exact name — not the pattern — so the printed
  commands mean the same thing later, whatever loggers exist then.
- Only options that differ from the default are printed. Values are quoted for a POSIX shell the
  way `list rules --verbose` already quotes them (double quotes, with `"`, `\`, `$` and `` ` ``
  escaped), and the target too when it contains `*`.
- The confirmation is `Apply? [Y/n]` — Enter applies. The operator has just chosen every part of
  the rule, so the extra keystroke protects nothing; `set logger`'s `[y/N]` guards a pattern whose
  matches the operator hasn't seen, which is not the case here.
- The result prints exactly as a non-guided `add rule` does, one line per created rule.

### Several loggers, one fails (G10)

The rules are attached one at a time. If one is refused (for example, a protected category), the
others still go ahead; each refusal is printed on its own line after the successes, and the exit
code is that of the first failure. Nothing already attached is rolled back — each rule is
independent, and `logctl reset rule <id>` removes any of them.

## Pattern targets on the command line (G2)

A leading-star target on a complete, non-guided command stops being rejected:

```
logctl add rule drop '*.Deployer' --message-contains "Rescanning" --below WARN
```

- On a terminal: the matches are shown as a numbered list and picked exactly as in guided mode
  (the "Picking the loggers" rules above, minus the prompt-only `*.<answer>` shorthand).
- Without a terminal: a usage error naming how many loggers match, like `set logger`'s
  (`pattern-selection-semantics.md` Decision #4) — pass `--yes` to attach to all of them.
- With `--yes`: one independent rule per currently-known match, each with its own id, per
  `filtering-epic.md` "Targeting by pattern is shorthand". A logger created later gets nothing.
- The expansion happens in `logctl`: it lists the matches (`listLoggers`) and calls the existing
  `addRuleDrop`/`addRuleTrim` once per chosen exact name. **No agent or MXBean change.**

This takes over the pattern-target half of #79. Its other half — attaching in the context a logger
actually lives in rather than the first registered one — stays in #79, which is retitled to match.

## `--json` and `--yes` (G9)

- `--yes` means "never ask": it turns off guided mode and answers the pattern confirmation.
- `--json` also turns guided mode off, because questions and a JSON document can't share stdout.
  A pattern target with `--json` needs `--yes`, same as without a terminal. The output is a JSON
  array of the created rules, in `list rules --json`'s row shape; a single exact-name target keeps
  today's single object.

## Out of scope (G11, G12)

- **Guided `set logger`, `alter rule`, `reset`.** The same approach could apply; each is its own
  follow-up once this one has been used.
- **Picking the JVM.** When several LogAperture JVMs are running, every command stops with the
  `pass --pid <n>` table. Offering a numbered pick there helps every command, not only `add rule`,
  so it is a separate issue.
- **Arrow-key selection (JLine).** Numbered lists and typed answers need no new dependency.
  Revisit if the numbered list proves clumsy.
- **Turning a pasted log line into a rule** — the AI-assisted later phase in §18.15.
- **Multi-context attachment** — stays in #79.

## Implementation notes

- `Parser` stops rejecting a missing type/target/matcher for `add rule` and instead produces a
  guided command carrying whatever was given; the "is it complete" check moves to run time,
  where `interactive`, `--yes` and `--json` are all known. Non-interactive runs raise the same
  usage errors from there, so their messages don't change.
- One `BufferedReader` over `in` is shared by every question. (`readYesAnswer` today builds a new
  reader per call, which would lose buffered input across several questions.)
- Questions go to `out`, as `set logger`'s preview does today.
- Validation reuses `Parser`'s existing level/duration/number checks rather than copying them.
- The printed command uses `org.logaperture.api.RuleExpression`'s quoting and spelling helpers
  (`quote`, `belowFor`, `duration`) — the ones behind `list rules --verbose` and the audit records
  (`list-rules-verbose.md`, `alter-rule.md`) — but not its full rendering, which spells out every
  value, including defaults.
- `HelpText`: the two `add rule` lines show the type and target as optional, plus one line saying
  a terminal prompts for anything missing.

## Settled during implementation

Small points the decisions above left open, settled while building it:

- **Type-specific options need the type.** `--sample-full`/`--no-sample-full` or
  `--frames`/`--collapse-causes` without `drop`/`trim` is a usage error that says to name the type
  (`add rule drop <target> ...`), rather than guessing the type from the option.
- **`add rule foo bar`** — a first word that isn't `drop`/`trim` followed by something that isn't a
  lifetime — is still the usage error `'add rule' needs 'drop' or 'trim', got 'foo'.`
- **A missing target is asked for first:** `Which logger? A name or pattern, e.g. Deployer or
  *.deployment.* (Enter to cancel)`, with the G4 shorthand.
- **Answer shortcuts:** `d`/`t` for drop/trim, and a bare duration (`30m`) for `for 30m` at the
  lifetime question.
- **The printed command is one line per logger**, not wrapped, so it can be copied as-is.
- **Help:** the `add rule` synopses gain `[--yes]`, and one note under the options says a terminal
  prompts for anything left out.

## Testing

- `MainRunTest`, through the existing `Main.run(…, in, interactive)` seam:
  - the sample session above, end to end, against a fake MXBean — the created rule and the
    printed command both checked;
  - each skip condition in the questions table;
  - invalid answers re-asked (level, duration, frames, selection list);
  - `drop` with every matcher skipped re-asks;
  - EOF at each stage prints `Not applied.` and attaches nothing;
  - the `*.<answer>` shorthand, and that a command-line target doesn't get it;
  - more than 30 matches asks for a narrower pattern.
- Non-interactive: every current `add rule` usage error unchanged apart from the added hint;
  pattern target without `--yes` refused; with `--yes` one rule per match.
- `--json` with a pattern target and `--yes` prints an array.
- The printed command, fed back through `Parser`, produces the same `addRuleDrop`/`addRuleTrim`
  call — a round-trip test over every option.
- Partial failure: the second of three loggers refused; the other two attached, exit code non-zero.
- `CliEndToEndIT`: one pattern-target `add rule … --yes` against the real agent.

## Decisions

| # | Decision | Status |
|---|---|---|
| G1 | Guided mode starts only when a required part is missing, on a terminal, without `--yes`/`--json`; it then asks for every part not given | **Agreed** |
| G2 | Leading-star pattern targets on the command line are accepted (pick list on a terminal, `--yes` for all), expanded in `logctl` with no agent change; this takes over #79's pattern half | **Agreed** |
| G3 | The rule type may be left out; a word other than `drop`/`trim` after `add rule` is the target | **Agreed** |
| G4 | At the prompt, an answer with no `*` and no `.` is searched as `*.<answer>`; not on the command line | **Agreed** |
| G5 | Selection: numbered list, `1,3-5` / `all` / Enter cancels; one match used directly; more than 30 asks for a narrower pattern | **Agreed** |
| G6 | Question order and defaults as in the table; `drop` with no matcher re-asks | **Agreed** |
| G7 | The printed command is one line per selected logger, exact names, non-default options only, shell-quoted | **Agreed** |
| G8 | Confirmation is `Apply? [Y/n]` (Enter applies) | **Agreed** |
| G9 | `--json` and `--yes` both turn guided mode off; a pattern target's `--json` output is an array | **Agreed** |
| G10 | With several loggers, a refused one doesn't stop the others; no rollback; exit code of the first failure | **Agreed** |
| G11 | Guided `set`/`alter`/`reset` and JVM picking are separate follow-ups | **Agreed** |
| G12 | No JLine; numbered lists only | **Agreed** |
