# Epic: filtering by content — `drop` and `trim` rules

Status: **draft for sign-off** (2026-09-21). Nothing is implemented. This is an *epic* spec: it fixes
the shared design, the sequencing and the cross-cutting decisions. Each member still gets its own
`doc/specs/<feature>.md` and its own sign-off before code, per CLAUDE.md.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §7 (Feature 3 — the squelch engine:
§7.2 matchers and actions, §7.3 evaluation, §7.4 keep-one-in-N), §4.2 (gate and render stages), §9.2,
§9.5, §9.6 (restrictions compose; the suppression floor; suppression is never silent), §6.1
(persistence tiers), §17 (M2, from which `drop` and `trimStackTrace` are pulled forward), §18.8.
Builds on: [`level-control.md`](level-control.md), [`persistence.md`](persistence.md),
[`top.md`](top.md) (its formatter wrap becomes the render seam),
[`pattern-selection-semantics.md`](pattern-selection-semantics.md) (name patterns are one-time
expansion — the general statement of that principle now lives there, cited rather than restated),
[`set-command-surface.md`](set-command-surface.md) / [`reset-command-surface.md`](reset-command-surface.md)
(the noun-first command surface rules follow).
Evidence: [`doc/spikes/rule-pipeline.md`](../spikes/rule-pipeline.md) — validated on plain JBoss
LogManager and on WildFly 26.1.3 and 33.0.0. Findings are cited below as *(spike)*.
Members: [#26](https://github.com/ddeuchert/logaperture/issues/26) storm detection (prerequisite);
the rule foundation and `drop` (issues to be filed);
[#34](https://github.com/ddeuchert/logaperture/issues/34) exception-detail threshold, reshaped as `trim`.
**Not members:** #63 (labelled `obsolete`, see Decision #11); #60/#61/#62 (vendor file, see "Out of scope").

## Functional summary

After this epic, the user will be able to:

- Stop a known-noisy message from being logged, without changing its logger's level:
  drop events from a logger whose message contains some text, below a level they choose.
- Keep recording that an exception happened while no longer paying disk for its stack trace:
  print a one-line summary instead, for exactly the exceptions and messages they name.
- Attach a rule to a logger by name, and have it reach that logger's descendants automatically —
  including ones that don't exist yet — the same way a level does, unless a descendant is told not
  to inherit it.
- Target several loggers in one command with a `*.Name` pattern, as shorthand for running the same
  attach against each one currently known — narrowed by exception type, message text and level.
- Keep a rule for a session, for a duration, or sticky, and see every active rule, its hit count
  and its expiry in `logctl status`.
- Remove a rule by id, or all rules, or every rule attached to one logger.
- Trust that nothing is hidden silently: every drop and trim is counted and visible, protected
  categories and FATAL are never touched, and rules expire by default.

## The idea

Today every control is keyed on a logger's **name** and acts on its **level**. Operators also need to
act on an event's **content**: the same logger emits a benign, repeating message and a real failure.
This epic adds two behaviour-changing actions from M2's squelch engine — `drop` (gate stage) and
`trimStackTrace` (render stage) — as a deliberately small rule model, and nothing else from M2.

Worked example that shaped the design (a real trace from a deployed product):

```
logctl add rule trim '*.AutoUpdateHelper' \
    --throwable java.net.ConnectException \
    --message-contains "Failed to connect to URL" --below FATAL sticky
```

The 29-frame `ConnectException` trace becomes one line plus a marker at ERROR and every level
below FATAL; FATAL, other exceptions and other loggers are untouched. The spelling is a placeholder
the child specs fix; the behaviour is what this epic agrees.

## Members and build order

| Step | Piece | Issue |
|---|---|---|
| 1 | Storm detection supplies the always-on gate observer this epic builds on | #26 (spec signed off) |
| 2 | **Rule foundation:** matcher library, compiled rule plan, safety scaffolding, rule management | new |
| 3 | **`drop`** | [#72](https://github.com/ddeuchert/logaperture/issues/72) — spec signed off: [`drop-rule.md`](drop-rule.md) |
| 4 | **`trim`** — #34 reshaped: a rule with a "below LEVEL" bound, not a knob beside `level` | [#34](https://github.com/ddeuchert/logaperture/issues/34) — implemented: [`trim-rule.md`](trim-rule.md) |

Each gets its own spec, branch and PR. The epic gets a tracking issue listing them. Automatic storm
collapse (#27) comes afterwards and becomes another producer of drop rules.

## What `drop` means

A per-event gate that runs after the level check passes. It is not a level change: other events
from the same logger flow as before. A matching event is discarded before it is formatted or written.

- **Keep-floor default.** A drop rule spares ERROR and above unless the rule states a different
  bound. Example: "drop below ERROR".
- **Separate rule, own lifetime.** A drop rule is its own object with `SESSION` / `FOR` / `STICKY`
  and an expiry that defaults to expiring, like a level override — not a clause on `set logger`.
- **What "contains" matches.** The formatted message, case-sensitive by default, with an explicit
  case-insensitive form. Evaluated only for loggers that have a rule (formatting is cached on the
  record, so the formatter reuses it — *(spike)*).
- **Safety, required in the first version:** drops are counted and shown as a periodic line in the
  log itself (§9.6); the first, and then every Nth, matching event is kept (§7.4 `sampleFull`,
  on by default, explicit opt-out); protected categories refuse drop rules (§9.5); rules expire by
  default.
- **Scope:** every handler. Per-handler dropping is #56 `--to`, out of scope.

## What `trim` means

The event and its message are kept; only the throwable's rendering changes, at the handler's
formatter, after `drop` (a dropped event is never trimmed).

- **Default output:** the exception's one-line summary (`class: message`) plus an always-present
  marker, e.g. `[stack trace trimmed: 47 frames omitted]`, so the log never has an undetectable gap.
- **Graded:** `--frames N` keeps the top N frames; the default is 0. Full trace is the unchanged state.
- **Cause chains:** each `Caused by:` is reduced to its own one-liner; `collapseCauses` can remove
  them.
- **Same shape as drop:** a rule with a "below LEVEL" bound (that bound *is* #34's `exceptionDetail`
  threshold), its own lifetime, its own row in `status`. Default bound is below ERROR; a rule may set
  `--below FATAL` to include ERROR.
- **Same matchers as drop** — see "Matchers".
- **Deferred:** a "keep only frames from package X" mode (most of a typical trace is JDK internals);
  noted as a later addition.

## Matchers (one vocabulary, both actions)

The §7.2 subset, compiled once, shared by `drop`, `trim` and every later caller:

| Matcher | Forms in this epic | Defaults |
|---|---|---|
| `logger` | the logger to attach to: an exact name, or a leading-star pattern `*.Name` as command-line shorthand for attaching to each currently-known match (see "Targeting by pattern is shorthand"). **A trailing `.*` is rejected**, for the same reason `set logger` rejects it (#49 Decision #5): attaching to a bare name already reaches every descendant — see "Logger scope". | Reaches descendants via inheritance — see "Logger scope". |
| `level` | an upper bound (`--below LEVEL`) | below ERROR |
| `message` | `--message-contains` on the *log* message; `--message-contains-ignore-case` | case-sensitive |
| `throwable` | `--throwable <type>`, subclasses included; `--throwable-message-contains`; `--any-cause` to match a cause instead of the top exception | top-level exception only |
| `frame` | out of scope for the first version | — |

All matchers on a rule must hold (AND). No `not`/`any`/`all` composition in the first version.

### Logger scope: a rule is attached like a handler, not layered like a level

A `LogRule` (common interface; `Drop` and `Trim` implement it) is not a per-event pattern test. It is **attached directly to a specific logger**, exactly the
way a `<handler>` is attached to a `<logger>` in `standalone.xml`, and it reaches descendants the same
way handlers do: by inheritance, not by re-matching a pattern on every event.

- **A logger holds 0–n configured `LogRule`s** (`Drop` and `Trim` together, one collection), independent
  of whether it has a configured level. Configuring a level never affects rule scope, and vice versa —
  this was a real footgun in an earlier draft of this design (a level change on a descendant, for an
  unrelated reason, silently losing that descendant's inherited trim protection) and is deliberately
  designed out.
- **A logger also holds a `useParentRules` flag, default `true`** — modelled directly on JBoss
  LogManager's own `use-parent-handlers` (seen live in a real `standalone.xml`: exactly one logger in
  that config sets it, everything else relies on the default). **Effective rules for a logger are its
  own configured rules, plus its ancestors' configured rules accumulated on the way up, stopping at
  the first ancestor (inclusive of that ancestor's own rules) whose `useParentRules` is `false`.**
  Same algorithm as handler fan-out (§4 of the standalone.xml reading), not the single-winner
  nearest-ancestor algorithm levels use. This gives an explicit, discoverable way to cut inheritance
  off, which level-anchored scoping never had.
- **Additive by default, cancellable on purpose.** A rule attached to `org.myorg` and another attached
  to `org.myorg.a` both apply to `org.myorg.a.MyClass`, by default — matching stacks up the tree —
  right up until some logger in between sets `useParentRules=false`, at which point everything above
  that point stops applying there.
- **No trailing `.*`, ever, for the same reason a bare name never needs one for a level or a handler:**
  attaching to `org.myorg` already reaches its whole subtree through inheritance. There is nothing a
  trailing wildcard would add.
- **No re-scan, no cache-invalidation-on-level-change.** Because scope is now ordinary tree
  inheritance rather than a per-event name match, a descendant created later under an attached logger
  picks the rule up immediately and for free — the same way it would pick up an inherited level or an
  inherited handler. This retires the per-event matcher-and-cache design (and its associated
  level-change invalidation) from an earlier draft of this spec.
- **`logctl list rules <id>`** shows the logger it's attached to, and (for a rule attached above a
  point where inheritance was cut) says so explicitly.

### Targeting by pattern is shorthand, not a standing rule

A leading-star target (`*.AutoUpdateHelper`) on `add rule` is the same shorthand `set logger` and
`reset logger` already use (`pattern-selection-semantics.md`, #49): **it expands exactly once, at the
moment the command runs, into one concrete attachment per currently-known matching logger.** It is
not evaluated per event, and it does not cover a logger created afterward with a matching name — only
the tree-inheritance mechanism above does that, and only from the loggers it was actually attached to.

- `add rule trim '*.AutoUpdateHelper' ...` against a JVM with `dave.AutoUpdateHelper` and
  `bill.AutoUpdateHelper` currently known produces **two independent attachments, two ids** — one on
  each logger — not one rule spanning both. A `carl.AutoUpdateHelper` created later gets nothing from
  this command; it would need its own `attach`, or to be a descendant of an already-attached logger.
- **Preview and confirm, exactly like `set logger` on a pattern:** show which currently-known loggers
  will receive the attachment (and the id each will get) before applying, with `--yes` to skip the
  prompt.
- This principle — a leading-star target is a one-time, currently-known-only expansion, never a
  standing rule — is stated once, generally, in `pattern-selection-semantics.md`, and cited from here
  and from `set`/`reset` rather than restated per command.

## Rules as managed objects

- **Each attachment gets its own short id** — one rule attached to one logger. A pattern-target
  `add rule` producing several attachments produces several ids (see "Targeting by pattern is
  shorthand"); there is no group object spanning them afterward, same as a pattern-target `set logger`
  leaves nothing standing to manage as a batch.
- `logctl status` and `logctl list rules` show every rule with the logger it's attached to, action,
  matchers, `useParentRules`, tier, expiry and hit count.
- `logctl reset rule <id>` and `logctl reset rules` remove attachments.
- `logctl reset logger X` also removes every rule attached directly to `X` (not its descendants' own
  attachments, which are separate objects).
- Command spelling (noun-first, matching #42): `logctl add rule drop …`, `logctl add rule trim …`,
  `logctl list rules`, `logctl reset rule <id>`. Exact flags are fixed in the child specs.

## Evaluation

Rules compile into one immutable plan, swapped atomically (§7.3). Order per event: level check
(unchanged) → `drop` rules, first match terminal, several drops OR'd → `trim` rules on survivors;
among several trims, the **most restrictive** wins (fewest frames), except where a floor forbids
trimming (§9.2). No priority or ordering syntax in this slice. Checks run cheapest first: logger,
level, throwable type, message. **Hit counts are per event, not per handler** *(spike, Decision #9)*.
`top` shows the bytes a rule saved.

## Safety limits

- **FATAL is a hard limit:** no drop or trim rule can touch FATAL, even if written to.
- **Suppression floor (§9.5):** protected categories refuse both actions, with an audit record.
- **Verbosity ceiling:** unaffected; neither action raises anything.
- **Never silent (§9.6):** counted, visible in `status`/`top`, marker on every trimmed exception,
  periodic summary line for drops.
- Every rule requires the capability and is audited like a level override.

## Foundation: what the spike established

Design consequences, all from [`doc/spikes/rule-pipeline.md`](../spikes/rule-pipeline.md):

1. **The gate is a handler-level `Filter` on every handler**, not `Logger.setFilter`, which is not
   inherited and so cannot cover loggers created later. "Applies to every handler" means "installed
   on every handler, including ones added later".
2. **Coverage is kept by a periodic re-arm, with a fail-open gap.** WildFly resets handler filters at
   boot completion, on a `filter-spec` write, and on `:reload`. Between a reset and the next tick
   a matching event is not filtered. This is consistent with §9's fail-open discipline and is
   accepted; the interval lives with the existing sweep / `reapplyOnReset` machinery.
3. **Always chain, never replace** an existing filter, and re-detect our wrapper every tick.
4. **Trim copies the record** with `new ExtLogRecord(ExtLogRecord)` reached by reflection (the adapter
   has no compile-time JBoss LogManager dependency). It does not mutate the shared record; if the copy
   cannot be made it **fails open** and prints the untrimmed line.
5. **`top`'s formatter wrap becomes the render seam.** Order: trim inside, byte-counting outside, so
   `top` reports the bytes actually written. This also touches #23 (double rendering of traces).
6. **Cost is measured:** a non-candidate logger costs ~3 ns/event and allocates nothing; a candidate
   pays for formatting once, and the result is cached on the record.

## Decisions

| # | Decision | Status |
|---|---|---|
| 1 | Epic membership: #26, foundation, `drop`, `trim` (#34); umbrella spec plus child specs | **Agreed** |
| 2 | The seam is built inside this epic from #26's observer and `top`'s formatter wrap, not deferred to M2 | **Agreed** |
| 3 | One matcher library, the §7.2 subset above; no second grammar | **Agreed** |
| 4 | Rules are managed objects: ids, `status`/`list rules`, `reset rule`/`rules`, `reset logger X` | **Agreed** |
| 5 | `drop` semantics: keep-floor below ERROR, separate rule, formatted message, required safety set, all handlers | **Agreed** |
| 6 | `trim` semantics: one-liner + always-on marker, `--frames N`, per-cause lines, same matchers, same shape as drop | **Agreed** |
| 7 | Evaluation: immutable plan, drop first and terminal, most-restrictive trim, cheapest-first, no priority syntax | **Agreed** |
| 8 | Command spelling is noun-first: `add rule drop|trim`, `list rules`, `reset rule <id>` | **Agreed** |
| 9 | FATAL is a hard limit; suppression floor applies; hit counts are per event; dry-run deferred | **Agreed** |
| 10 | Structured formatters (JSON/XML): hybrid rendering — synthetic frameless throwable for text formatters (keeps the cause chain), message suffix with a per-cause summary for structured ones (avoids the wrong `exceptionType`) | **Superseded for `trim`'s first slice** — [`trim-rule.md`](trim-rule.md) ships text formatters only; structured formatters are untrimmed, tracked as [#83](https://github.com/ddeuchert/logaperture/issues/83) |
| 11 | #63 is labelled `obsolete`, not closed. Reconsider if drop/trim leave a gap (its one uncovered case: raising a level for whatever emits X) | **Agreed** |
| 12 | Release: #26 + foundation + `drop` + `trim` in alpha-3; #27, #33 and the rest in beta-1. Revisit once the feature set is complete | **Provisional** |
| 13 | Accept the fail-open re-arm gap (foundation point 2) | **Agreed** |
| 14 | A rule is attached to a specific logger and reaches descendants by tree inheritance, modelled on `use-parent-handlers`, not by a per-event pattern test anchored on level configuration (supersedes the original wording of this decision) | **Agreed** |
| 15 | Each logger carries a `useParentRules` flag, default `true`; effective rules accumulate up the tree and stop at the first ancestor with the flag `false` (inclusive) — the handler algorithm, not the level algorithm; rules and levels are fully independent axes | **Agreed** |
| 16 | A leading-star target on `add rule` is one-time expansion into concrete per-logger attachments, each with its own id, previewed and confirmed (or `--yes`) exactly like a pattern target on `set logger`; trailing `.*` is never accepted, on any command that attaches a rule or a level | **Agreed** |

## Deferred and future enhancements

- **`--dry-run`** on drop and trim: count what a rule would do without doing it (deferred by decision).
- **Keep-only-application-frames** mode for `trim`.
- `frame` matcher, `not`/`any`/`all` composition, rule priority or ordering.
- Standing content rules beyond what a per-event matcher already gives, dropping by rate or sample
  (`rateLimit`, `dedupe`, `sample`) — remaining M2.
- Content selector for discovery (`list loggers --emitting …`) — was #63.
- Other backends: Logback (`TurboFilter`, encoder swap) and Log4j 2 need their own adapter work;
  M0 proved the mechanisms, not this rule set.

## Out of scope

- **Vendor configuration trio (#60/#61/#62).** #60's keyword hiding *depends on* `drop`, and #62's
  export would emit these sticky rules; that dependency is recorded on those issues, not made
  membership.
- **Delivery targeting `--to` (#56).**
- **Automatic storm collapse (#27)** — later, as a producer of drop rules.
- **Level-changing content selection (#63)** — see Decision #11.

## Testing

Each child spec owns its tests. The epic requires, in addition:

- The spike's scenario as a real-WildFly integration test (ERROR trimmed, FATAL full, non-matching
  message full, other logger full, INFO drop, ERROR keep) on the WildFly versions the project
  supports, including after `pattern-formatter` change, `filter-spec` write, a new handler and `:reload`.
- A structured-formatter test (Decision #10) and an `AsyncHandler` test on a real server.
- A per-event count test with several handlers attached.
- Logger-scope tests: a rule attached to a logger reaches a descendant created afterward with no
  re-scan; `useParentRules=false` on an intermediate logger cuts off everything above it (that
  logger's own rules still apply); rules attached at nested ancestors are additive by default;
  a level change on any logger never affects rule scope; `add rule` on a leading-star pattern
  produces one attachment and one id per currently-known match, previews them, and does not reach a
  logger created afterward; trailing `.*` is rejected on `add rule` as on `set logger`.
- A floor/FATAL test for each action proving a protected category and FATAL are refused and audited.
- A hot-path check that a logger with no rule costs effectively nothing.
