# `add rule` in every context — multi-context attachment

Status: **signed off 2026-11-16** (decisions M1–M10 below, M5 and M7 as revised in review: old rows are
JVM-wide, a rule is one row whose `context` is always `"system"`). Tracked as
[#79](https://github.com/ddeuchert/logaperture/issues/79), milestone 1.1.0.
Parent: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18 (roadmap). Related:
[`drop-rule.md`](drop-rule.md) and [`trim-rule.md`](trim-rule.md) (where this was deferred),
[`rule-pipeline-foundation.md`](rule-pipeline-foundation.md) (rule identity, persistence),
[`guided-add-rule.md`](guided-add-rule.md) (took the pattern-target half of #79),
[`log4j2-adapter.md`](log4j2-adapter.md) ("Setup hand checks (2026-11-16)": the evidence).

## Functional summary

After this feature, the user will be able to:

- Run `logctl add rule drop` or `logctl add rule trim` on a server that hosts several web
  applications (Tomcat, WildFly with Log4j 2 deployments) and have the rule take effect in
  **all of them**, not only in the first.
- See each rule once in `logctl list rules`, as one rule with one hit count that adds up what it
  did in every application, exactly as on a server with a single logging context.
- Run `logctl reset rule <id>` or `logctl alter rule <id>` and have it reach every application the
  rule is in.
- Deploy or redeploy a web application after adding a rule and find the rule already working in it.
- Stop worrying that a server's own logging and a deployment's Log4j 2 are competing for a rule:
  a rule on a logger reaches both.

Guide pages this changes: [`install-tomcat.md`](../../guide/get-started/install-tomcat.md) and
[`install-wildfly.md`](../../guide/get-started/install-wildfly.md) (the "one context only" caveat
goes), [`concepts/rules.md`](../../guide/concepts/rules.md) (where a rule applies),
[`reference/json-output.md`](../../guide/reference/json-output.md) (the `context` field's wording, M7).

## The problem

`set logger` and `set handler` reach every logging context in the JVM. `add rule drop` and
`add rule trim` reach one: `AggregateLevelControl.addRuleDrop` and `addRuleTrim` attach to
`sortedByKey().get(0)`, the first context by key. That was a scope reduction taken while the rule
pipeline was built (`drop-rule.md`, `trim-rule.md`), tracked as #79, and it is silent. The hand checks
of #302 (2026-11-16) showed the effect on Tomcat 11.0.27 and WildFly 26.1.3: with several web
applications each running its own Log4j, the rule worked in one and the others kept logging every
line and whole stack traces, with nothing to say so. Contexts are ordered by key, so
`log4j2:/…` comes before `system`: on a WildFly server with a Log4j-excluding deployment, a rule on a
logger that lives in the server's own logging is attached to the Log4j context and never fires.

The guide says a change "goes to every context that has that logger" (Tomcat page) and `list rules`
shows one row. The user has no way to see, or to fix, the gap.

## Scope

In: which contexts an added rule reaches; how its id is chosen and persisted; contexts that appear
after the rule was added; resume; `list`, `reset` and `alter` over a rule that is in several contexts;
the drop summary; recipes; the cost.

Out:

- **Aiming a rule at one context** (a `--context` option). Nothing in `logctl` aims at one context
  today (`set logger` has no such option) and the Tomcat page says so. If a need shows up it is a
  separate feature across every command, not this one (M2).
- **A per-context breakdown of a rule's hits.** Hidden by design (M7); if wanted later it is a new optional field.
- **Pattern targets.** Expanded in `logctl` and already shipped (`guided-add-rule.md`, G2). Each
  expanded logger is one `add rule` call and so now reaches every context.
- **Vendor rules** (`vendor:<name>`). They already live in every context with the same id; the
  mechanism below is that one, extended to operator rules.
- **The leading-star `reset rule` / `reset rules` semantics.** Unchanged.

## Behavior

### Which contexts (M1, M2)

An added rule is attached in **every registered context**, whether or not that context has seen the
logger, the same as `set logger`. A rule on a logger name that never appears in a context matches
nothing there; it costs the gate's non-matching-rule check on that context's events (M10).

Both `add` calls keep their all-or-nothing capability and name checks, made once before any context is
touched (the checks do not depend on the context). The attach itself then runs context by context. If
attaching fails in one context, the contexts already attached are rolled back, the error is reported,
and nothing is persisted (M6).

### One rule, one id (M3)

An operator rule has **one id across all contexts**, minted by the aggregate (`r<N>`, one sequence for
the JVM), and every context's `RuleService` adopts it with that id, the way an override is adopted
(`adoptOverride`). Today each `RuleService` mints its own sequence, so `r1` in two contexts is two
different rules. That worked only while one context held every operator rule, and the state file
(keyed by rule id alone, `StateStore.saveRule`) would overwrite one with the other the moment a second
context held one.

The aggregate's sequence starts past every id a context resumed (`advanceIdSequencePast` today, per
context), so a resumed `r7` is never reissued.

### Contexts that appear later (M4)

A context registered after the rule was added (a web application deployed, redeployed or reloaded)
receives every still-live rule, the way `addContext` re-broadcasts overrides: same id, same tier, same
absolute expiry for `FOR`, hit count starting at 0 in the new context. A `FOR` rule whose deadline has
passed is not re-broadcast. A `SESSION` rule lasts as long as the JVM, so it reaches a context deployed
tomorrow.

Registering a context, adding a rule, `reset`, `alter` and the expiry sweep all take one lock, so a context
that registers while a rule is being added, reset or altered ends up with the rule as it is after that
operation, never a stale copy and never none. `register` (the initial-discovery path) carries the live rules
over the same way `addContext` does.

A context that goes away takes its copy with it (its hit count is lost); the rule stays in the others
and in the state file.

### Persistence and resume (M5)

A rule persisted at `FOR` or `STICKY` is **one row, with no context**, applied to every context at
resume, as a level override is. That row's id is the shared id.

A row written by 1.0.x carries a `context`. That tag was never a user choice: `logctl` has no way to
name a context, and the tag is only the key of whichever context was first when the rule was added
(`rule-pipeline-foundation.md`, "`PersistedRule` gained a `context` field", a fix that stopped resume
copying every row into every context). A rule is JVM-wide to the user, and to the guide. Resume
therefore **ignores the tag** on an operator rule: the row applies in every context, the same as a new
row. A context the old rule never reached now gets it, which is what the user meant when they typed it.
Rows are rewritten without the tag the next time they are saved. The 1.1.0 changelog says so.

### `list rules` (M7)

A rule is one rule, to the user, whatever number of contexts carries it. `list rules`, `status`,
`add rule` and `alter rule` show **one row per rule id**: the aggregate rolls the per-context rows
together before anything leaves the agent, with `hitCount` the **sum** across contexts. No `CONTEXT`
column appears for rules, and the user is never told how many contexts hold one. This is what 1.0
showed, because every rule was in one context. Vendor rules (already in every context) are rolled up
the same way, so a multi-context JVM shows each vendor rule once as well.

The other fields (tier, expiry, expression, `altered`, `toNative`) are the same in every context,
because `alter` and `reset` reach every holder (M8); if they differ for a moment during a change, the
row is the first context's, by key.

**What `context` reports in `--json` and on `RuleData`.** The field is documented
(`reference/json-output.md`, `"context": "system"`) and `DEVELOPMENT.md` makes narrowing or retyping a
documented field a major-version change, so it is not turned into `null`. A rule is system-wide, so the
only value it can have is `"system"` (`ContextHandle.SYSTEM`), always, which is also what 1.0 reported
for a rule on a server whose logging is the `system` context. It stays so until a release lets a rule be
aimed at one context, when a rule that is aimed gets that context's key. No `logctl` text shows it for a
rule; the guide describes it as "`system`: the rule applies across the whole JVM". A per-context
breakdown of hits, if ever wanted, is a new optional field (additive), not this feature.

### `reset rule` and `alter rule` (M8)

- **`reset rule <id>`** removes the rule from every context that holds it, and the state row once
  (today it already tries every context; with a shared id it reaches all of them instead of the first
  match). A `STICKY` rule still needs `--include-sticky`; if one context refuses, none is removed.
- **One audit record per action.** `reset rule`, `reset rules`, `alter rule` and the expiry sweep each write one
  record per rule, not one per context: the aggregate shares one "already audited" set across the contexts of
  one operation.
- **`alter rule <id>`** applies the same patch to every holder. It throws today when more than one
  context holds an id (ids could collide); an operator id is now one rule everywhere, so that check goes,
  as it already does for a vendor id.
- **`reset logger <name>`'s rule cleanup** is already per context and is unchanged.

### The drop summary and recipes (M9)

The periodic drop summary (`quieter-output.md`, Q4) reports a rule once: counts from every context are
merged by rule id before the line is written, so a rule in three contexts is one entry with the summed
count, not three entries with one id. `DropSummary.line` already merges by rule id (vendor rules were always in every
context), so this needs a test, not code.

`apply recipe` creates rules through the same `add` path and so reaches every context, with no recipe
change. The recipe's own record (rule id to recipe id) is kept once, at the aggregate.

### Audit

One `MUTATION` record per `add`, as today, with the one id. The per-context attach is not a separate
record. An `add` in a context that registers later is audited as the re-broadcast the overrides use.

## Cost (M10)

A context with no rules keeps the gate's level fast path. A context that now holds a rule it never sees
events for pays the non-matching-rule check on its events: the "gate with a non-matching rule" case of
the benchmark (`overhead-benchmarks.md`), whose budget the Log4j 2 adapter already meets. The
verification step for this feature is that case, measured with a rule in two contexts, not a new
benchmark.

## Decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| M1 | Which contexts get the rule | (a) Every registered context, like `set logger`. (b) Only contexts where the logger is currently known. (c) The context a named logger "lives in", resolved at add time. | **(a).** (b) and (c) miss a logger that appears later (a lazily created logger, a late deploy) and need a per-context existence query the adapters do not all offer (Log4j holds loggers weakly from 2.24.3, so a known-logger list can miss a live one). (a) needs none and matches what the guide already says. |
| M2 | A way to aim at one context | (a) None. (b) `--context <name>`. | **(a).** No command has one; adding it for rules alone would be inconsistent, and the want is not shown. |
| M3 | Rule ids across contexts | (a) One id for the JVM, minted by the aggregate, adopted by each context. (b) Keep per-context ids; key the state file by (context, id). (c) Keep per-context ids and make `reset`/`alter` ask which. | **(a).** One user-visible rule is one id. (b) and (c) make `reset rule r1` ambiguous and `list rules` show two different `r1`s, and (c) is what 1.0 does and fails on. It is also the model vendor rules and level overrides already use. |
| M4 | A context that registers after the rule | (a) Re-broadcast every live rule onto it. (b) Only contexts present at add time. | **(a).** A redeploy is the common case on Tomcat and WildFly; (b) loses the rule on the first one. It is `addContext`'s existing behavior for overrides. |
| M5 | Persisted rules and 1.0.x rows | (a) Ignore the stored context tag: every row applies in every context. (b) Keep a 1.0.x row in the context its tag names. (c) Drop old rows. | **(a).** The tag is the first context's key, never a choice (no command names a context), so (b) would preserve an accident and leave two kinds of rule. (c) loses a sticky rule. The user typed a JVM-wide rule; resume makes it one. |
| M6 | A failure while attaching in one context | (a) Roll back the contexts already attached, persist nothing, report. (b) Keep the contexts attached, warn. (c) Skip the failing context. | **(a).** The same all-or-nothing as the checks; a rule half in place is the problem this feature exists to remove. The capability and name checks run first, so what is left to fail is an adapter fault. |
| M7 | `list rules` shape | (a) One row per rule with hits summed across contexts, no `CONTEXT` column; `context` is always `"system"`. (b) One row per rule per context, as the first cut proposed. (c) As (a), but `context` becomes `null`. | **(a)**, revised after review: a rule is one rule to the user, as in 1.0. Rolled up in the aggregate, so the shape of `RuleData` and `--json` is unchanged. (c) is the honest value but a major-version change under the versioning policy; (b) makes the user learn contexts to read a list. |
| M8 | `reset rule` / `alter rule` over a shared id | (a) Act on every holder. (b) Require the user to name a context. | **(a).** The user named a rule, not a context. |
| M9 | The drop summary | (a) Merge counts by rule id across contexts. (b) One entry per context. | **(a).** One rule, one line; (b) prints one id twice. |
| M10 | Cost check | (a) Measure the existing "gate with a non-matching rule" case with a rule in two contexts. (b) A new benchmark. | **(a).** The path is the one already budgeted. |

## Known limits

- **`alter rule` is not atomic across contexts.** A validation error in the first context stops the call before
  anything changes; a failure in a later one leaves the earlier ones altered, and each context takes its own
  `now` for a `FOR` deadline. Not a regression (a vendor rule's `alter` always worked so).
  [#399](https://github.com/ddeuchert/logaperture/issues/399), 1.x.
- **A vendor rule's saved alteration** is written once per context under one id, so after a restart only the last
  writer's context resumes it. This is the one row kind that still carries a context key (M5 leaves vendor
  alterations with the context that wrote them, which is the intent; the overwrite is the defect).
  [#400](https://github.com/ddeuchert/logaperture/issues/400), 1.x.

## Acceptance

- Two contexts, an exact-name and a hierarchy `drop` and `trim` rule: each context's events are
  dropped and trimmed, with no event counted twice, on the fake adapters (`AggregateLevelControl`
  tests) and in a real process with two Logback or Log4j contexts.
- The Tomcat and WildFly hand checks of #302 repeated with the rules: `drop` and `trim` act in every
  web application, with the Log4j versions 2.20.0 and 2.25.5 among them.
- A rule on a JBoss LogManager logger, with a Log4j deployment present on WildFly, fires in `system`.
- A context registered after the rule: the rule is there, with its expiry, before the next event.
- A `FOR` rule that expired while the context was away is not re-broadcast.
- `reset rule`, `reset rules`, `alter rule`, `reset logger <name>` and `apply recipe` over several
  contexts, including `STICKY` refusal in one context (none removed).
- Resume: a row reaches every context, whether or not it carries a 1.0.x context tag.
- The state file never holds two rules with one id.
- A rule added while a context registers is never missed by it (the two share one lock); a refused add does not use
  up an id; the new context takes the union of every context's live rules, not one arbitrary context's.
- `reset rule` refuses before any context removes the rule when a holder is `STICKY`; `reset`, `alter` and an
  expiry write one audit record per rule.
- `list rules`, `status`, `add rule` and `alter rule` show one row per rule with summed hits, in a JVM with several contexts; `--json` and MXBean `RuleData` unchanged in shape (the `JsonOutputTest` and MXBean contract tests), `context` always `"system"`.
- The guide: the Tomcat and WildFly caveats removed, `concepts/rules.md` states where a rule applies.

## Not established

- ~~Whether a 1.0.x state file can hold two rows with one id.~~ It cannot: the state file is keyed by rule id,
  so when 1.0 minted `r1` in a second context it replaced the first's row on save. Resume never sees a pair
  to merge, only the surviving row. (The earlier rule was lost then, in 1.0; nothing here brings it back.)
- The Spring Boot case is one Log4j context plus `system`; the fan-out puts the rule in both. That is
  the intended result, not yet exercised by hand.

## Implementation notes (for the PR, not decisions)

`AggregateLevelControl.addRuleDrop`/`addRuleTrim` stop using `get(0)` and call a new
`RuleService.adopt(rule, …)` in each context after minting the id once; `addContext` gains the
rule re-broadcast beside the override one; `RuleService.idSequence` moves to the aggregate;
`resumeFromStateStore` stops filtering an operator row by its `context` tag and registers it in every
context (the tag stays for vendor alteration rows, which have their own resume path).
