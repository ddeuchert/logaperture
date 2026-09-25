# `logctl list rules --verbose` (issue #98)

Status: **signed off 2026-09-25** (V1–V6 agreed).
Parent spec: [`rule-pipeline-foundation.md`](rule-pipeline-foundation.md) "Command surface"
(`logctl list rules`). Builds on: [`drop-rule.md`](drop-rule.md), [`trim-rule.md`](trim-rule.md),
[`vendor-defaults.md`](vendor-defaults.md) (vendor rules), [`list-command-surface.md`](list-command-surface.md).
Related: issue #96 (changeable rules), which needs a way to see a rule's current definition.

## Functional summary

After this feature, the user will be able to:

- Run `logctl list rules --verbose` and see, for every rule, the options that define it, written
  the way they would type them after `logctl add rule drop|trim <logger>`.
- Copy that text to recreate or adapt a rule.

## Command

```
logctl list rules [--verbose] [--json]
```

Without `--verbose`, output is unchanged. With it, an `EXPRESSION` column appears after `LOGGER`:

```
ID  LOGGER             EXPRESSION                                                                                 ACTION  TIER    EXPIRES  HITS
r1  org.myorg.MyClass  --message-contains "Can't connect" --throwable java.net.ConnectionException --below WARN --sample-full 5m  drop    STICKY  —        0
```

## The expression (V2, V3)

The options of `add rule drop|trim` that define the rule's behaviour, in a fixed order, every value
explicit:

1. Content matchers, as given: `--message-contains <text>` or `--message-contains-ignore-case
   <text>`; `--throwable <class>`; `--throwable-message-contains <text>`; `--any-cause`.
2. `--below <LEVEL>` — always shown, including the default `ERROR`, since it is the rule's safety
   bound. Levels in upper case.
3. Action options:
   - drop: `--sample-full <duration>` (e.g. `--sample-full 5m`), or `--no-sample-full`;
   - trim: `--frames <n>` and, if set, `--collapse-causes`.

Not included: the logger and the action (their own columns), the tier (`TIER`/`EXPIRES`), and the
`--reason`.

**Quoting:** a value containing anything other than letters, digits and `. _ - : / , + @ %`
(e.g. a space, an apostrophe, a `$` in an inner-class name, or a `*`) is wrapped in double quotes,
with `"`, `\`, `$` and backquote escaped with a backslash — so the text is safe to paste into a
POSIX shell.

Vendor rules get the same expression, from the definition in the vendor defaults file.

## Where it is built (V4)

In `logctl`, from `list rules`' existing JMX rows: the expression is `logctl`'s command syntax, so
`logctl` owns rendering it. The rows already carry every matcher and the trim options; they lack a
drop rule's sampling setting, so `RuleData` gains `sampleFullEnabled` and `sampleFullEveryMillis`
(additive fields, logaperture-spec.md §11.1). The same fields close the gap noted in
`vendor-defaults-export.md` X1.

## JSON (V5)

`list rules --json` always includes each rule's `expression` string (with or without
`--verbose`), plus the two new sampling fields.

## Module scope

- `logaperture-core` / `logaperture-control-jmx`: `RuleData` (and the `RuleView` → `RuleData`
  mapping) gain the sampling fields.
- `logaperture-cli`: `--verbose` (a usage error on any other command, V1), the `EXPRESSION`
  column, the expression renderer, JSON, help.
- `rule-pipeline-foundation.md`: the `list rules` synopsis gains `--verbose`.

## Testing

**Unit (cli):** the renderer for every option, including each default, both actions, quoting
(space, apostrophe, double quote, backslash, `$`), a vendor rule; the column appears only with
`--verbose`; JSON `expression`; `--verbose` rejected elsewhere. **Unit (jmx/core):** the sampling
fields carried for drop rules, absent for trim rules.

**Round trip:** for a set of rules, feeding the expression back through the parser (`add rule
<action> <logger> <expression>`) yields the same rule definition.

## Member decisions

- **V1 — The flag is `--verbose`**, accepted only by `list rules` for now. **Agreed.**
- **V2 — Every value is explicit**, including defaults (`--below ERROR`, `--sample-full 5m`), so
  the expression recreates the rule exactly even if a default changes later. **Agreed.**
- **V3 — Fixed option order and shell-safe double quoting**, so the same rule always renders the
  same text and it can be pasted. **Agreed.**
- **V4 — `logctl` renders it**, from `RuleData` plus two new sampling fields. **Agreed.**
- **V5 — `--json` always carries `expression`.** **Agreed.**
- **V6 — Not in the expression: logger, action, tier, reason.** They have their own columns, or
  (reason) don't define behaviour. **Agreed.**
