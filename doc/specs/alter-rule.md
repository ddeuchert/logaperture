# Changeable rules: `logctl alter rule` and the vendor rule model (issue #96)

Status: **signed off 2026-09-25** (A1–A13 agreed); **implemented** (see "Settled during
implementation").
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §6.6 "Precedence: configuration
layers" — the canonical terms (native configuration, vendor defaults, baseline, override, native
default) and personas (operator, vendor) used here.
Builds on: [`rule-pipeline-foundation.md`](rule-pipeline-foundation.md) (rule identity, tiers,
persistence), [`drop-rule.md`](drop-rule.md), [`trim-rule.md`](trim-rule.md) (the flags),
[`list-rules-verbose.md`](list-rules-verbose.md) (a rule's definition as text),
[`vendor-defaults.md`](vendor-defaults.md) (vendor rules), [`reset-to-native.md`](reset-to-native.md)
(`--to-native` for loggers and handlers, whose "Rules — out of scope" section this spec fills).
Depends on: issue #95 (`for <duration>` rules never expire at runtime), fixed first (A12).
Feeds: [`vendor-defaults-export.md`](vendor-defaults-export.md) (#62) X4, see "Effect on the export".

## Functional summary

After this feature, the user will be able to:

- Change an existing rule in place with `logctl alter rule <id>`, giving only what changes — for
  example `logctl alter rule r3 --below WARN` or `logctl alter rule r3 --message-contains green` —
  and keep its id.
- Remove an optional part of a rule, e.g. `--no-throwable`, or change only how long the rule
  lasts, e.g. `logctl alter rule r3 sticky`.
- Change a vendor rule the same way; `logctl reset rule vendor:<id>` puts the vendor's definition
  back.
- Switch a vendor rule off until restart with `logctl reset rule vendor:<id> --to-native`, the same
  option loggers and handlers already have.
- As a vendor, change or remove a vendor rule in the next vendor defaults file by altering it
  `sticky` or resetting it `--to-native`, then exporting.

## Why

Today a rule can't be edited. Changing "trim on `org.mylogger`, `--throwable
ConnectionException`, below INFO" to below WARN means `add rule` a replacement and `reset rule` the
old one: the replacement gets a new id, its hit count starts over, and between the two commands
either both rules or neither are in force. Vendor rules are worse: the operator can only switch
one off until restart (`--include-vendor-defaults`), and the vendor persona has no way at all to
change one before exporting the next file.

## Command surface

```
logctl alter rule <id> [changes] [session | for <duration> | sticky] [--reason <text>] [--json]
```

```
logctl alter rule r3 --below WARN
logctl alter rule r3 --message-contains green
logctl alter rule r7 --no-throwable --throwable-message-contains "timed out"
logctl alter rule r3 sticky
logctl alter rule vendor:perfmon-noise --below WARN sticky --reason "too chatty at INFO"
```

Output, one block per rule, the definition in `list rules --verbose` form (A4):

```
rule r3 (trim, org.mylogger) altered
  was: --throwable java.net.ConnectException --below INFO --frames 0
  now: --throwable java.net.ConnectException --below WARN --frames 0
  tier: for 4h (expires 18:40), unchanged
```

`--json` returns the rule's row as `list rules --json` shows it, plus `previousExpression`.

### What can change (A2)

Everything that defines what the rule matches and what it does to a match, plus its lifetime and
reason. The flags are the ones `add rule drop|trim` takes, with the same meaning:

| Part | Set / replace | Clear |
|---|---|---|
| Message matcher | `--message-contains <text>`, `--message-contains-ignore-case <text>` | `--no-message-contains` (either form) |
| Throwable type | `--throwable <class>` | `--no-throwable` |
| Throwable message | `--throwable-message-contains <text>` | `--no-throwable-message-contains` |
| Cause chain | `--any-cause` | `--no-any-cause` |
| Level bound | `--below <LEVEL>` | — (always present) |
| drop: sampling | `--sample-full <duration>` | `--no-sample-full` |
| trim: frames | `--frames <n>` | — (always present) |
| trim: causes | `--collapse-causes` | `--no-collapse-causes` |
| Reason | `--reason <text>` | — |
| Lifetime | `session`, `for <duration>`, `sticky` | — |

Fixed: the **action** (`drop`/`trim`) and the **logger**. Changing either is a different rule —
`add rule` the new one and `reset rule` the old one. (A logger change could also need pattern
expansion, which only `add rule` does.) A flag that belongs to the other action (`--frames` on a
drop) is a usage error.

### Patch semantics (A3)

- Only the parts named change; everything else is kept as it is.
- The two message forms replace each other: `--message-contains-ignore-case green` on a rule with
  `--message-contains blue` leaves one matcher, `green`, ignoring case. Never two.
- Setting and clearing the same part in one command (`--throwable X --no-throwable`), or both
  message forms at once, is a usage error, as it is on `add rule`.
- At least one change, a tier or `--reason` is required; a bare `alter rule r3` is a usage error.

### Validation and no-op (A4)

- The **resulting** rule must pass every check `add rule` makes: a drop must still have a content
  matcher (clearing the last one is refused, naming `reset rule` or `set logger` instead);
  `--frames` must be `>= 0`; `--below FATAL` is accepted and still never suppresses FATAL.
- A refused alter changes nothing.
- If the result equals the current rule (same definition, same lifetime, same reason), the command
  reports `rule r3 — no change` and exits 0; nothing is audited or written.
- Capabilities: `RULES_AUTHOR` and `SUPPRESS`, as for `add rule`; plus `PERSIST` if the resulting
  tier is `for` or `sticky`. An unknown id is an error (exit non-zero), unlike `reset rule`'s
  no-op: there is nothing to alter.

### The swap is atomic

The new definition replaces the old one under the same id in one step: no event is evaluated
against neither or both. An event already mid-evaluation may finish under the old definition.

### Hit count (A5)

The hit count, and a drop's sampling and summary-line state, **start over when the definition
changes** — the old count described a rule that no longer exists. A change to only the lifetime or
the reason keeps them. The count at the moment of change is recorded in the audit record, so it
isn't lost.

### Scope: one id (A11)

One rule per command. Rules from one `*.Foo` expansion each have their own id and are altered one
at a time. Altering several ids at once is deferred until a use for it shows up.

## Operator rules

An operator rule (`r<N>`) is changed in place. There is no baseline to go back to: once altered,
its old definition is gone (it survives only in the audit log), and `reset rule r3` removes the rule
as before.

**Lifetime (A6).** Without a tier, the rule keeps its tier and, for `for`, its expiry time. With a
tier, the new one replaces it and a `for <duration>` clock starts now. So `alter rule r3 sticky`
promotes a session rule to sticky without touching its definition, and `alter rule r3 session`
takes a sticky rule out of the state file. Altering a sticky rule doesn't need `--include-sticky`:
nothing is removed.

A sticky rule's state-file entry is rewritten under the same id.

## Vendor rules

A vendor rule (`vendor:<id>`) now works like a logger the vendor defaults name: the file's
definition is its **baseline**, and `alter rule` puts an **override** on top of it.

| | Vendor rule `vendor:x` | Operator rule `r3` |
|---|---|---|
| Baseline | the rule as the vendor defaults file defines it | none |
| `alter rule <id> …` | an override of the vendor's definition | changed in place (same id) |
| `reset rule <id>` | back to the vendor's definition | removed |
| `reset rule <id> --to-native` | off until restart, or until a plain reset | removed |

**The override is a whole definition (A7).** The override holds the complete altered rule, not a
list of differences — the way a level override replaces the level. If a later vendor defaults file
changes some other part of the rule, a sticky override still wins as a whole until it is reset.

**Lifetime (A7).** An override has a tier like any other: `session`, `for <duration>` or `sticky`.
The first alteration of a vendor rule with no tier uses `set logger`'s default, `for 4h`. Altering
an already-altered vendor rule without a tier keeps the override's lifetime, as for operator rules.
When a `for` override expires, the rule returns to the vendor's definition (not removed).

### Reset (A8)

Mirrors loggers and handlers (`reset-to-native.md`):

- `reset rule vendor:x` removes the override and restores the vendor's definition; a sticky
  override needs `--include-sticky`. On an unaltered vendor rule it is a no-op ("already at its
  baseline"), no longer a refusal.
- `reset rule vendor:x --to-native` switches the rule off until restart (the native configuration
  has no rules). It also removes an override, a sticky one only with `--include-sticky`. The rule
  stays listed.
- A plain `reset rule vendor:x` afterwards switches it back on, with the vendor's definition —
  the "unsuspend" slice 1 never had.
- `reset rules` and `reset logger X` apply the same to every vendor rule they cover, and accept
  `--to-native`, which they pass on to vendor rules. (`reset logger X --to-native` then resets both
  the logger's level and its directly-attached rules to native.)
- On operator rules, `--to-native` is the same as a plain reset: they're removed.
- **`--include-vendor-defaults` is removed** from `reset rule`, `reset rules` and `reset logger`:
  `--to-native` does its job. It is unreleased, so no deprecation.

### Restart (A9)

- A `session` override, a `for` override that expired while stopped, and a `--to-native` reset are
  gone after a restart: the vendor's definition applies.
- A sticky override (or an unexpired `for` one) is saved in the state file under the vendor rule's
  id and applied right after the vendor rules attach.
- If the vendor defaults file no longer has that rule, the saved override is **dropped**: removed
  from the state file, audited as a reversion, and a warning printed at startup. It altered
  something that no longer exists; carrying it on as a stand-alone rule would resurrect a rule the
  vendor removed.

## `logctl list rules` (A10)

- `TIER` for a vendor rule: `vendor-defaults` when unaltered; `vendor-defaults, <tier>` when
  altered, with the override's expiry in `EXPIRES`; `vendor-defaults (off)` when reset to native
  (replacing today's `(suspended)`).
- `--verbose` `EXPRESSION` shows the rule as it is now, altered or not.
- `HITS` counts since the definition last changed (A5).
- JSON: each rule gains `altered` (boolean); `suspended` is renamed `toNative`, matching loggers'
  `resetToNative` (unreleased, so renamed in place).

## Audit

An alter is a `MUTATION` record: previous value and new value are the full definitions in
`list rules --verbose` form, plus the tier, the reason, and the hit count at the moment of change.
Resetting a vendor rule's override is a `REVERSION` with the altered definition as previous value
and the vendor's as new. The `--to-native` reset keeps today's "off until restart" `REVERSION`.

This needs the expression renderer in the agent as well as in `logctl` (`list-rules-verbose.md`
V4 put it in `logctl`): it moves to `logaperture-api`, shared by both.

## Effect on the export (#62)

This settles `vendor-defaults-export.md` X4's rules half:

| Vendor rule, before exporting | Next vendor defaults file |
|---|---|
| nothing, or a `session`/`for` override | the file's definition (unchanged) |
| `alter rule vendor:x … sticky` | the altered definition, under the same id `x` |
| `reset rule vendor:x --to-native` | left out |

Operator `sticky` rules are exported as X3 already says. The export spec is updated to say this
when #62 is implemented.

## Sequencing (A12)

#95 (`for <duration>` rules never expire at runtime) lands first, as its own PR: A6/A7's lifetime
rules, and a `for` vendor override reverting to the vendor definition, depend on the expiry pass
#95 adds.

## Module scope

- `logaperture-api`: the expression renderer (moved from `logaperture-cli`); a rule-change type
  (each part: unchanged / set to a value / cleared); `RuleView` `altered`, `toNative`.
- `logaperture-core`: `RuleService.alterRule` (validate, swap by id, reset evaluation state when
  the definition changed, persist, audit); the rule registry gains replace-by-id; vendor-rule
  overrides (in memory and in the state file), their expiry and restart handling; reset paths take
  `toNative` in place of `includeVendorDefaults`; `StateFileFormat` for a vendor override entry.
- `logaperture-control-jmx`: `alterRule(id, RuleChangeData, tier, expiresIn, reason)`; `RuleData`
  `altered`/`toNative`; reset overloads with `toNative` replace the `includeVendorDefaults` ones
  (unreleased, §11.1 doesn't apply).
- `logaperture-cli`: `alter rule` parsing (the `--no-…` flags, patch conflicts), output, JSON,
  help; `--to-native` on `reset rule`/`reset rules`, `--include-vendor-defaults` removed; `TIER`
  cells.
- Specs: `rule-pipeline-foundation.md` (command surface), `vendor-defaults.md` ("Rules", M4),
  `reset-to-native.md` ("Rules — out of scope" points here), `cli-transport.md` reference,
  `logaperture-spec.md` §6.6 (the pointer to #96), `list-rules-verbose.md` (V4: renderer location).

## Testing

**Unit (cli):** each set/clear flag; message forms replacing each other; set-and-clear conflict;
flag for the wrong action; bare `alter rule`; tier and `--reason` parsing; output and JSON;
`--to-native` on the rule resets; `--include-vendor-defaults` rejected.

**Unit (core):**
- Alter each part of a drop and a trim; untouched parts kept; id kept.
- Clearing a drop's last content matcher refused, nothing changed.
- No-op alter: no audit, no state-file write.
- Hit count and sampling state reset on a definition change, kept on a tier-only change.
- Lifetime: kept without a tier; replaced (clock restarted) with one; sticky ↔ session moves the
  state-file entry.
- Capabilities: `PERSIST` needed only for a `for`/`sticky` result.
- Vendor rule: alter → override; plain reset → vendor definition; `--to-native` → off; plain reset
  after `--to-native` → on; sticky override needs `--include-sticky` to reset; `for` override
  expiry → vendor definition.
- Restart: sticky vendor override re-applied; dropped (with audit) when the file no longer has the
  rule; `session` override and `--to-native` gone.
- Atomic swap: a concurrent evaluator never sees the rule missing.

**Integration (WildFly container):** alter a live drop's `--message-contains` and see the old
message come back and the new one suppressed; alter a vendor rule sticky, restart, still altered.

## Settled during implementation

Details the decisions left open, decided while building it:

- **Altering a vendor rule that is switched off** (`--to-native`) is refused, naming `reset rule
  <id>` to switch it back on first. The rule isn't in force, so there is nothing to override.
- **Restart, A9 refined:** a saved vendor-rule alteration is dropped only when the vendor defaults
  file *loaded* and no longer has a rule with that id, action and logger. When the file didn't
  load (not configured, or rejected), the alteration stays in the state file untouched, so a broken
  file on one start doesn't destroy it. A saved alteration's row uses the vendor rule's own id
  (`vendor:<id>`); the state-file format is otherwise unchanged (no schema bump).
- **Several contexts:** a vendor rule lives in every context, so its alteration is applied in each.
  An operator id found in more than one context is refused as ambiguous rather than altered in a
  guessed one (ids are only unique per context; see `rule-pipeline-foundation.md` "Rule
  identity").
- **A no-change alter** on an unaltered vendor rule with no tier is a no-op even though a first
  alteration would otherwise default to `for 4h`. With an explicit tier it creates the override,
  as `set logger <vendor level> sticky` does for a logger.
- **`--no-sample-full`** keeps the rule's sampling interval, so a later `--sample-full` without a
  new one restores it (`SampleFullPolicy` already round-trips a disabled interval).
- **Reset results:** the bulk reset outcome's `skippedVendorIds` is replaced by `vendorResetIds`:
  vendor rules put back to their definition, switched back on, or switched off. An unaltered vendor
  rule isn't listed, since there was nothing to reset. `reset rule <id> --json` reports `removed`
  (operator rule), `vendorReset`, and `toNative`. Text output: `rule r3 → removed.`, `rule
  vendor:x → back to the vendor definition.`, `rule vendor:x → switched off until the application
  restarts.`, or `rule <id> — nothing to reset.`
- **`reset logger X`** now also puts `X`'s altered vendor rules back (a plain reset), where it used
  to skip and report them.

## Implementation status

Landed together with this spec:

- `logaperture-api`: `RuleChange` (each part unchanged / set / cleared) and `RuleExpression` (the
  renderer, moved from `logaperture-cli`, which now delegates to it); `RuleResetOutcome`
  `vendorResetIds`.
- `logaperture-core`: `RuleService.alterRule`, vendor baselines and alterations, the reset paths
  taking `toNative`, `sweepExpiredRules` returning an expired vendor alteration to its baseline,
  `resumeVendorAlteration`; `RuleRegistry.replaceIfCurrent` (the atomic swap); `RuleAlteration`;
  `RuleView` `toNative`/`altered`; `AggregateLevelControl.alterRule`. Composition roots pass
  whether the vendor defaults file loaded.
- `logaperture-control-jmx`: `alterRule`, `RuleAlterationData`; `RuleData` `toNative`/`altered`;
  the reset operations' third flag is now `toNative`.
- `logaperture-cli`: `alter rule`, the `--no-…` options, `--to-native` on `reset rule`/`reset
  rules`, `--include-vendor-defaults` removed, `TIER` cells, JSON, help.
- Tests: `AlterRuleTest` (core), `VendorRuleDefaultsTest` rewritten, `LevelControlMXBeanImplTest`,
  `AlterRuleCommandsTest`, `VendorDefaultsCommandsTest`; `WildFlyContainerIT
  .alterRule_changesALiveDropsMatcher_inPlace` and `WildFlyVendorDefaultsIT
  .vendorRule_alteredSticky_survivesARestart_andResetsTheWayALoggerDoes` against a real WildFly.

## Decisions (sign-off)

| # | Decision | Status |
|---|---|---|
| A1 | The verb is `alter rule` (not `set rule`: a rule has many parts, a logger one; not `edit rule`, which suggests opening an editor) | **Agreed** |
| A2 | Changeable: content matchers, `--below`, action options, `--reason`, tier. Fixed: action and logger | **Agreed** |
| A3 | Patch semantics: only named parts change; `--no-…` clears optional matchers; message forms replace each other; set-and-clear in one command is a usage error | **Agreed** |
| A4 | The resulting rule passes `add rule`'s checks; a no-change alter exits 0 without audit; unknown id is an error | **Agreed** |
| A5 | Hit count and drop sampling/summary state start over when the definition changes; kept for tier/reason-only changes; old count goes in the audit record | **Agreed** |
| A6 | Operator rule: lifetime kept unless a tier is given; a given tier replaces it and restarts a `for` clock; no `--include-sticky` needed to alter a sticky rule | **Agreed** |
| A7 | Vendor rule: `alter` is an override holding the whole definition; first alteration defaults to `for 4h`, later ones keep the override's lifetime; `for` expiry returns to the vendor definition | **Agreed** |
| A8 | Vendor reset mirrors loggers: plain reset → vendor definition (and undoes `--to-native`); `--to-native` → off until restart; passes through `reset rules`/`reset logger X`; `--include-vendor-defaults` removed | **Agreed** |
| A9 | A saved vendor override whose rule is gone from the vendor defaults file is dropped at startup, with audit and a warning | **Agreed** |
| A10 | `list rules` `TIER`: `vendor-defaults` / `vendor-defaults, <tier>` / `vendor-defaults (off)`; JSON `altered`, `suspended` → `toNative` | **Agreed** |
| A11 | One id per `alter rule` command | **Agreed** |
| A12 | #95 lands first as its own PR | **Agreed** |
| A13 | Export: a sticky-altered vendor rule is exported under its own id with the new definition; a `--to-native` one is left out (settles #62 X4's rules half) | **Agreed** |
