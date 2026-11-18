# Say what a package name covers in guided `set` and `add rule` (issue #404)

Status: **draft — not yet signed off.**
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.15 (guided commands).
Builds on: [`guided-add-rule.md`](guided-add-rule.md) "Picking the loggers" (the typed name, G4, and
issue #140: a package that isn't a logger yet), [`guided-commands.md`](guided-commands.md) (#7, the
guided `set logger` flow), [`pattern-level-targeting.md`](pattern-level-targeting.md) (a set ancestor's
level covers everything under it).
Milestone: 1.1.0.

## Functional summary

After this feature, the user will be able to:

- Type a package name such as `org.hibernate` at `Which logger?` in guided `set logger` or
  `add rule` and see how many loggers are under it, before they are asked for a level, so one quick
  answer can't silently change dozens of loggers.
- Name a single logger with nothing under it and be asked nothing extra: the hint appears only when
  there is something to be told.
- Keep using complete commands, scripts, `--yes` and `--json` exactly as today: nothing about a
  command that already names its logger and level changes.

Guide pages changed: [Raise a log level](../../guide/how-to/raise-a-level.md) ("A logger and
everything under it").

## Why

Setting a logger sets every logger under it too, now and later, because they inherit its level. That
is the point of naming a package, and the guide says so. But in the guided flow nothing shows it: a
name that exists goes straight to `Level?`, and one answer can change a whole subtree. The case where
the package isn't a logger itself yet already says how many loggers are under it (#140); a package
that *is* a logger says nothing. Found while capturing real output for #402: typing `org.hibernate`
went straight to the level question.

## Behavior

### The hint (D1)

When the name typed (or given as a short name that resolves to a single exact name) is a known logger
and has other known loggers under it, the guided flow prints one line before the next question:

```
org.hibernate has 14 loggers under it, which inherit its level (e.g. org.hibernate.SQL).
```

`1 logger under it, which inherits its level` for one. The example is the first logger under it, in
name order. The line goes to stdout, like the #140 line next to it, and the flow carries on to
`Level?` (or the rule questions) with no extra question.

### When it does not appear (D2)

- A logger with nothing under it: nothing is printed.
- A name that isn't a logger itself yet but has loggers under it: the existing #140 line, unchanged.
- A name that isn't known at all: the existing "No logger named X exists yet" question, unchanged.
- A pattern (`*.Worker`, `com.acme.*`): the numbered list, unchanged.

### Counting (D3)

The count is of the loggers the JVM knows now, under the name. A logger created later that would also
inherit is not counted, as with any pattern. A logger under it that has a level of its own still
counts: it is under the name, though it keeps its level (`set logger` already reports those after the
change, and `--force` sets them).

### Informs, does not ask (D4)

The hint informs. It never asks for confirmation, however many loggers there are, because the
operator who named the package meant it, and the printed command and `Apply? [Y/n]` that follow (#116
#1) are the place to stop.

### Nothing else changes (D5)

A complete command (`set logger org.hibernate DEBUG`), a script, `--yes` and `--json` do not go
through the guided picker and print nothing new.

## Tests

- A typed name that is a logger with loggers under it prints the hint with the right count and the
  first logger under it, for `add rule` and `set logger`.
- One logger under it reads `1 logger under it, which inherits its level`.
- A leaf logger, a name that is not a logger itself yet, an unknown name and a pattern print nothing
  new.
- A complete command prints no hint.
