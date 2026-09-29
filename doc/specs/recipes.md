# Logging recipes (issue #92)

Status: **signed off 2026-09-28** (#1–#14 agreed, inside the 2026-10-04 cut line of
`logaperture-spec.md` §17.1). **Slice (a) implemented** (discover and read: `list recipes`, `show
recipe`) and **slice (b) implemented** (`apply recipe`, `reset recipe`; decisions B1–B12 in
"Slice (b): apply and reset", agreed 2026-09-28). See also "Settled during implementation".
Parent spec: [`vendor-config-epic.md`](vendor-config-epic.md) slice 3, "Library-bundled recipes" —
epic decisions #14–#21 are agreed and not reopened here; this spec settles what they left open.
Also [`doc/logaperture-spec.md`](../logaperture-spec.md) §16.5 (named recipes), §9.3/§9.5
(capabilities, protected categories), §11.1 (additive-only control surface).
Builds on: [`vendor-defaults.md`](vendor-defaults.md) (the file format and parser),
[`set-command-surface.md`](set-command-surface.md) (tiers, `for 4h` default),
[`reset-command-surface.md`](reset-command-surface.md), [`guided-commands.md`](guided-commands.md)
(pick lists, printed command, `Apply? [Y/n]`), [`persistence.md`](persistence.md) (state file),
[`rule-pipeline-foundation.md`](rule-pipeline-foundation.md), [`drop-rule.md`](drop-rule.md),
[`trim-rule.md`](trim-rule.md).

## Functional summary

After this feature, the user will be able to:

- Run `logctl list recipes` to see the logging recipes available in the running application — from
  the libraries it has loaded, from the vendor defaults file, and from a recipes folder — with a
  one-line summary of each and whether it is switched on.
- Run `logctl show recipe io.undertow:sessions` to read what a recipe is for, what it will change
  (each logger's level now and after), and where it came from, before switching it on.
- Run `logctl apply recipe io.undertow:sessions for 30m` to switch it on — for a while, for this
  session, or `sticky` — after seeing the changes and confirming.
- Run `logctl reset recipe io.undertow:sessions` to switch it off again, leaving anything they have
  since changed by hand alone.
- See in `logctl list loggers` which settings a recipe made.
- Ship recipes with a library, with a product, or as a file dropped in a folder, so a support team
  can say "apply recipe com.acme:billing" instead of dictating logger names.

## Scope

In: the recipe file (three sources, one format), on-demand discovery, `list recipes`, `show recipe`,
`apply recipe`, `reset recipe`, recipe provenance on the changes a recipe makes, guided prompting for
`apply` and `reset recipe`, and the matching MXBean operations.

Out (unchanged from the epic): applying a recipe automatically from any source, including the vendor
defaults file's `activeRecipes` (deferred, epic #19); a recipe registry or download command (§18.6);
capture bundles (§16.5); signed recipe files; the Logback adapter's rule support (rules in a recipe
work where rules work today — JUL / JBoss LogManager).

## Where recipes come from (epic #15, #16 — agreed)

| Source | Location | Trusted to contain |
|---|---|---|
| **Library** | `META-INF/logaperture/recipes.yaml` inside a loaded jar (see #3 for wars and ears) | logger levels only (epic #17) |
| **Vendor defaults file** | its `recipes:` section | logger levels, handler levels, `drop`/`trim` rules |
| **Recipes folder** | `*.yaml` in `${logaperture.home}/recipes/`, or the folder named by `-Dlogaperture.recipes=<dir>` | logger levels, handler levels, `drop`/`trim` rules |

Library recipes are discovered on demand (epic #16): when `list`, `show`, `apply` or `reset recipe`
runs, the agent walks `Instrumentation.getAllLoadedClasses()`, collects the distinct class loaders,
asks each for `getResources("META-INF/logaperture/recipes.yaml")`, and de-duplicates by resource URL.
Parsed results are cached by URL, so a second `list recipes` only parses jars it hasn't seen. The
vendor defaults file's recipes are read with the file at startup. The recipes folder is read on
demand too, so a file dropped in is picked up without a restart.

## The recipe file

Same YAML subset and parser as the vendor defaults file ([`vendor-defaults.md`](vendor-defaults.md)
"Format"), with one addition (#2). A library's file:

```yaml
schemaVersion: 1
namespace: io.undertow
recipes:
  - name: sessions
    summary: Watch HTTP session creation, expiry and invalidation
    description: |
      Logs each session create/expire/invalidate with its id. Moderate volume
      under load. Session ids are sensitive; don't leave this on.
    loggers:
      - name: io.undertow.server.session
        level: DEBUG
        reason: session lifecycle events
      - name: io.undertow.request
        level: DEBUG
```

A vendor defaults file adds the same `namespace:` and `recipes:` keys beside its own (#1); a recipe
there, or in the recipes folder, may also carry `handlers:` and `rules:` entries, exactly as the
vendor defaults file's own top-level `handlers:` and `rules:` sections spell them — minus `stateId`,
and with rule `id` optional.

| Key | Shape | Notes |
|---|---|---|
| `schemaVersion` | integer, required | `1` |
| `namespace` | required in a recipe file; required in a vendor defaults file that has `recipes:` | `[a-z0-9][a-z0-9.-]{0,63}`; reverse-domain recommended (`io.undertow`); `vendor`, `logaperture` and `org.logaperture` reserved (#1) |
| `recipes` | list, required in a recipe file | entries below |
| `recipes[].name` | required | `[a-z0-9-]{1,40}`; the id is `<namespace>:<name>` |
| `recipes[].summary` | required | one line, shown in `list recipes` |
| `recipes[].description` | optional | multi-line text, shown by `show recipe` |
| `recipes[].loggers` | list | `name` (exact logger name), `level`, `reason` (optional) |
| `recipes[].handlers` | list; not in a library file | as the vendor defaults file's `handlers` |
| `recipes[].rules` | list; not in a library file | as the vendor defaults file's `rules`; `id` optional |

A recipe must change at least one thing. Validation is per file and all-or-nothing, as for the vendor
defaults file: a file with an error contributes no recipes, and the error is reported (#11). The one
exception is the vendor defaults file itself: an error in its `recipes:` section rejects the whole
file, as any other error in it does today — it is one file, loaded at startup, with one outcome.

A library file's other top-level keys (`loggers:`, `rules:` …) are ignored with a diagnostic (epic
#15). A library recipe carrying `handlers:` or `rules:` is an error in that file (epic #17).

## Commands

```
logctl list recipes [--verbose] [--json]
logctl show recipe <id> [--from <source>] [--json]
logctl apply recipe <id> [session | for <duration> | sticky] [--from <source>] [--reason <text>] [--yes] [--json]
logctl reset recipe <id> [--include-sticky] [--json]
```

### `logctl list recipes`

```
ID                    SUMMARY                                               SOURCE                          APPLIED
com.acme:billing      Trace invoice generation end to end                   vendor defaults                 -
io.undertow:requests  Log each HTTP request line and status                 recipes/undertow.yaml           -
io.undertow:sessions  Watch HTTP session creation, expiry and invalidation  undertow-core-2.3.10.Final.jar  for, 22m left
```

- `SOURCE` is a short label: `vendor defaults`, `recipes/<file>`, or the jar's file name (for a
  jar inside a war or ear, `<war>!/WEB-INF/lib/<jar>`). `--verbose` shows the full path or URL.
- `APPLIED` is `-`, or the tier of the changes that still carry the recipe (`session`, `sticky`,
  `for, 22m left`), or `partly` when some of its changes have since been changed by hand (#7).
- A recipe shadowed by a same-id vendor or folder recipe (epic #20) is not listed; `--verbose` lists
  it with `shadowed` in `APPLIED`. Two different same-id library recipes are both listed, each with
  its source (epic #20).
- Files that failed validation are listed after the table: `1 recipe file could not be read — see
  logctl list recipes --verbose` (#11).
- No recipes at all: `No recipes found. Libraries' recipes appear once the library has loaded.`

### `logctl show recipe <id>`

```
io.undertow:sessions — Watch HTTP session creation, expiry and invalidation
Source: jar:file:/opt/wildfly/modules/system/layers/base/io/undertow/core/main/undertow-core-2.3.10.Final.jar!/META-INF/logaperture/recipes.yaml

  Logs each session create/expire/invalidate with its id. Moderate volume
  under load. Session ids are sensitive; don't leave this on.

Changes:
  logger io.undertow.server.session   INFO -> DEBUG   session lifecycle events
  logger io.undertow.request          INFO -> DEBUG

Switch it on with: logctl apply recipe io.undertow:sessions for 4h
```

Each change shows the current effective level and the recipe's level. A change that would be refused
right now (protected category, missing capability, a lowering in a library recipe — #4) is shown with
the reason instead of an arrow.

### `logctl apply recipe <id>`

Prints the same `Changes:` block, then `Apply? [Y/n]`; `--yes` skips the question, and without a
terminal `--yes` is required (the usage error names it), as for a pattern `set logger`
([`set-command-surface.md`](set-command-surface.md)). Tier and its default (`for 4h`) are those of `set
logger` (epic #18). The result prints one line per change, like `set logger`, then `Applied recipe
io.undertow:sessions (2 changes, for 4h).`

- Each change is an ordinary override (loggers, handlers) or rule, carrying the recipe id (#6). A
  logger or handler that already has an override is overwritten, exactly as `set` would.
- Rules from a recipe get ordinary ids (`r7`), not `vendor:` ids.
- The reason on each change is the entry's `reason`, else `--reason`, else `recipe <id>` (#8).
- Applying a recipe that is already applied re-applies it: its changes are set again with the new
  tier. Two recipes that set the same logger: the later one wins and carries its id (#7).

### `logctl reset recipe <id>`

Resets the overrides and removes the rules that still carry the recipe id; one the operator has since
changed by hand no longer carries it and is left alone (epic #18, #7). Like `reset loggers`, it skips
sticky changes unless `--include-sticky` is given (#9). It resets to whatever is underneath — the
vendor defaults or the native level — as a plain `reset` does; there is no `--to-native` here (#9).
No confirmation: it only undoes what `apply` did. Prints one line per reset, then `Reset recipe
io.undertow:sessions (2 changes).`, or `Nothing to reset: no changes carry recipe io.undertow:sessions.`
The recipe need not still be discoverable — the id on the changes is enough.

## Decisions

### #1 — The namespace in the vendor defaults file

Epic #20 says "namespace declared in the file", and the epic's own example has a vendor saying "apply
recipe com.acme:billing". The vendor defaults file has no namespace today.

**Agreed:** the vendor defaults file gains an optional top-level `namespace:` key, required when it
has `recipes:`. `vendor`, `logaperture` and `org.logaperture` are reserved namespaces, so a recipe id
can never be mistaken for a `vendor:` rule id or a future built-in.

**Namespaces are reverse-domain by convention, not by rule.** The documentation and every example
recommend a reverse domain the author controls — for a library, its Maven `groupId` (`io.undertow`,
`com.acme`) — which is collision-free the way Java packages are, and lines up with the logger names
a recipe sets (`io.undertow:sessions` → `io.undertow.server.session`). It isn't enforced: the agent
can't check that a namespace belongs to whoever uses it, and a short namespace is reasonable for an
operator's own folder recipes. The format allows dots and up to 64 characters. Alternative considered: fix vendor recipes to
`vendor:<name>` — shorter, but "apply recipe vendor:billing" tells a support engineer nothing about
which product it belongs to, and two vendors bundled in one JVM would collide.

### #2 — Multi-line `description` needs block scalars in the parser

The epic's example uses `description: |`. The YAML subset shared with the vendor defaults file
supports no block scalars today.

**Agreed:** add YAML literal block scalars (`|`, with the default "clip" chomping only — no `|-`,
`|+`, `>` or explicit indentation indicators) to `VendorYaml`. It's the natural way to write a
paragraph, and every recipe author will reach for it. The vendor defaults file accepts it too, for
`reason:`. Alternative: `description` as a double-quoted string with `\n` — no parser change, but
unpleasant to write and review. Also considered: `description` as a block list of lines, which the
parser already reads — no parser change, but not how anyone writes prose in YAML, so authors would try
`|` first and hit an error.

Only plain `|` is accepted; `>`, `|-`, `|+` and explicit indentation indicators stay rejected, with a
message naming `|` as the supported form. The change only widens what the parser accepts, so every
existing vendor defaults file stays valid.

### #3 — Wars and ears

Epic #15 names jar, war and ear. On WildFly, a war's class loader sees `WEB-INF/classes` and
`WEB-INF/lib/*.jar`, not the war's own `META-INF/`; an ear's own `META-INF/` isn't on a class path
at all.

**Agreed:** the location is `META-INF/logaperture/recipes.yaml` *on the class path*. A jar carries
it at its root; a war carries it as `WEB-INF/classes/META-INF/logaperture/recipes.yaml` (the
standard place for a war's own class-path resources); an ear carries it in any jar in its `lib/`.
Documented with an example for each. This needs no WildFly-specific code, and the same rule works
on a plain JVM and, later, Spring Boot and Tomcat.

### #4 — A library recipe may only raise verbosity

Epic #17 limits library recipes to logger levels because "a dependency should be able to suggest
more visibility; it should not be able to ship something that hides output". A logger level set to
`ERROR` hides output as surely as a `drop` rule.

**Agreed:** a library recipe's logger entry is applied only if it makes the logger *more* verbose
than its current effective level; an entry that would lower it is skipped and shown as `skipped: would
lower INFO -> ERROR (library recipes only raise levels)`. The rest of the recipe still applies. This
is checked at `show`/`apply` time against the live level, not at parse time, since "lower" depends
on the running configuration. Vendor and folder recipes are unrestricted (operator-placed, epic #17).
Alternative: reject lowering entries at parse time by level name alone — can't work, since `INFO` is
a raise from `WARN` and a lowering from `DEBUG`.

### #5 — All or nothing when a change is refused

A recipe is several changes. Guided multi-item commands let one refusal not stop the others
([`guided-commands.md`](guided-commands.md) #1).

**Agreed:** a recipe applies all or nothing, except #4's skips. Before changing anything, the agent
checks every change against capabilities, protected categories and verbosity ceilings (epic #19); if
any would be refused, nothing is applied and the error lists each refused change. A half-applied
"watch sessions" recipe watches the wrong thing, and the operator can't tell from a partly-succeeded
command which half they got. #4's skips are different: they are a stated property of library recipes,
shown before confirming.

### #6 — Recording where a change came from

Epic #18 says each override records its recipe and `list loggers` shows it.

**Agreed:** logger overrides, handler overrides and rules gain an optional `recipe` field (the
id, e.g. `io.undertow:sessions`), carried in memory, in the state file, over JMX and in `--json`. The
state file's schema goes from 9 to 10; a version-9 file loads with no recipe on anything (the 1.0
state-file migration test, §17.1, covers it). `list loggers` and `list handlers` gain a `RECIPE`
column, shown only when at least one row has one (so it costs nothing for users who never use
recipes); `list rules` likewise. The existing `source` field (`jmx`, `vendor-defaults`, …) is left
alone: it answers "which control surface", a different question.

### #7 — What "changed by hand since" means

**Agreed:** the recipe id lives on the override or rule itself. Any later change to that logger,
handler or rule — `set`, `alter rule`, or another recipe's `apply` — replaces the record, and the
replacement carries no recipe id (or the other recipe's). So `reset recipe` never needs a history:
it resets exactly the records still tagged with its id. `list recipes` shows `partly` when some of
the recipe's changes are still tagged and some aren't. An expired `for` override is simply gone, as
today.

### #8 — The reason on a recipe's changes

**Agreed:** per entry, the entry's own `reason` if it has one; else `--reason` from the command;
else `recipe <id>`. The audit record carries the recipe id and its source location separately (epic
#19), so the reason stays human text.

### #9 — `reset recipe` options

**Agreed:** `--include-sticky` as on `reset loggers` (a sticky recipe survives a plain `reset recipe`,
consistent with every other broad reset). No `--to-native`: a recipe sits on top of whatever baseline
is there; taking it off should land on that baseline. An operator who wants the native level runs
`reset logger X --to-native` afterwards. Plain `logctl reset loggers` / `reset handlers` / `reset
rules` still reset recipe-made changes like any other.

### #10 — Guided `apply` and `reset recipe`

**Agreed**, following [`guided-commands.md`](guided-commands.md):

- `logctl apply recipe` alone on a terminal lists the recipes (numbered, with summary and source) to
  pick exactly one, then shows `Changes:`, asks how long (`for 4h` default), and prints the complete
  command before `Apply? [Y/n]`.
- `logctl reset recipe` alone lists only recipes that are applied, to pick one or more.
- `logctl reset` alone (guided #11) keeps listing loggers, handlers and rules one per line; a
  recipe-made item shows its recipe id beside it. It does not add recipes as a group.
- `logctl apply` alone asks nothing new: `recipe` is its only noun, so it goes straight to the recipe
  pick list.

### #11 — Broken recipe files

A jar's recipe file is written by a library author and read on demand, not at startup, so there's no
startup log line and `doctor` doesn't see it unless discovery runs.

**Agreed:** a file that fails validation contributes no recipes; `list recipes` prints a count after
the table and `--verbose` lists each file with its errors and line numbers. `doctor` runs discovery
and reports broken recipe files as an informational finding — informational because it's a library's
bug, not the operator's. The vendor defaults file's `recipes:` section is the exception: validated at
startup with the rest of that file, all or nothing (vendor-defaults epic #8).

### #12 — The MXBean surface

**Agreed**, all new operations (additive, §11.1):

- `listRecipes()` → `List<RecipeData>` (id, summary, source label, source URL, applied state, shadowed).
- `showRecipe(String id, String from)` → `RecipeDetailData` (the above plus description and each
  change with current level, recipe level, and a refusal/skip reason if any).
- `applyRecipe(String id, String from, String reason, String tier, long forSeconds)` →
  `RecipeApplyResultData` (the changes made, with ids for rules).
- `resetRecipe(String id, boolean includeSticky)` → `RecipeResetOutcomeData`.

Recipes are resolved and applied in the agent, so the audit trail records the source the agent read,
not whatever the client claims. The confirmation is `logctl`'s: it calls `showRecipe`, prints it,
asks, then calls `applyRecipe` — the same split pattern `set logger` uses. `LevelOverrideData`,
`HandlerLevelOverrideData` and `RuleData` gain an optional `recipe` field (#6).

### #13 — Capabilities and audit

As epic #19, made concrete: `list`/`show recipe` need `VIEW`. `apply` needs, per change, what the
equivalent command needs (`LEVEL_RAISE`/`LEVEL_LOWER`, `HANDLER_RAISE`/`HANDLER_LOWER`, `PERSIST`
for a non-session tier, `RULES_AUTHOR` for rules), all checked before anything changes (#5). Each
change is audited as its own record, source `recipe`, with the recipe id and source location in the
record. `reset recipe` is audited like the resets it performs.

### #14 — Release placement and slices

**Agreed:** 1.0.0-beta.1 if signed off by 2026-10-04 (§17.1), in two slices, each its own PR:

- **(a) Discover and read:** file format and parser changes (#1, #2), the three sources, discovery
  and caching, `list recipes`, `show recipe`, broken-file reporting (#11), `doctor` finding.
- **(b) Apply and reset:** `apply` / `reset recipe`, the `recipe` field and state schema 10 (#6),
  capability pre-check (#5), library raise-only (#4), guided prompting (#10).

Slice (a) is useful alone (operators can read what a library recommends and apply it by hand), so if
time runs short at the Oct 15 freeze, (a) ships and (b) moves to 1.1 — the beta date doesn't move for
it (unlike #116, which was a stated beta dependency).

## Slice (b): apply and reset

Decisions #4–#13 already fix what `apply recipe` and `reset recipe` do. Building them on the slice (a)
code left the points below open. All twelve were agreed as proposed on 2026-09-28.

### B1 — What was shown is what gets applied

`logctl` shows the changes (`showRecipe`), asks, then applies (`applyRecipe`, #12). A folder file can
be edited, or a library redeployed, between the two calls.

**Agreed:** `showRecipe` returns a fingerprint of the recipe's content, and `applyRecipe` takes it:
when the recipe no longer matches, nothing is applied and the error says `recipe io.undertow:sessions
changed since it was shown -- run the command again`. `--yes` calls `applyRecipe` without a
fingerprint, since nothing was shown. This adds a `fingerprint` parameter to #12's `applyRecipe`.

### B2 — A failure part-way through

#5 checks every change before making any. A change can still fail after the check passes: an
adapter fault, or a handler that disappears in a WildFly reload.

**Agreed:** apply in order: loggers, then handlers, then rules. On the first failure, stop, leave
what was already applied in place (it carries the recipe id, so `reset recipe` undoes it), and
report both lists: `Applied 2 of 4 changes before this failed: ...`. No rollback, the same as a
`set logger` broadcast across WildFly contexts, where a mid-broadcast fault is left for the
verification sweep (`AggregateLevelControl.setLogger`). A rollback could fail too, and would then
leave a state that is harder to explain.

### B3 — Applying a recipe that is already applied

#7 says re-applying sets its changes again with the new tier. Loggers and handlers are simply
overwritten, but a rule is a new object each time it is added.

**Agreed:** the recipe's rules that still carry its id are removed, then the recipe's rules are
added again, with new `rN` ids. Applying twice never doubles a rule, and a rule changed by hand (so
no longer tagged, #7) is left alone. The output shows the new ids. Alternative: alter the tagged
rules in place and keep their ids. More code, and it breaks down when the recipe's rule list itself
changed between the two applies.

### B4 — The `recipe` field, as stored

#6 adds a `recipe` field to logger overrides, handler overrides and rules.

**Agreed:** one `recipe:` line per record in the state file (schema 10), written only when set; a
schema 9 file reads with no recipe on anything. The field sits in `api`'s `LevelOverride` and
`HandlerLevelOverride`, in `core`'s `PersistedRule` and `RuleView`, and in the matching MXBean data
types as a new optional attribute. A context that appears after a redeploy gets the re-broadcast
overrides with their recipe id intact. `export vendor-defaults` writes a sticky recipe-made change
like any other sticky change; the file has no `recipe` field, so the tag isn't exported.

### B5 — What the audit record carries

#13 says each change is audited with source `recipe`, the recipe id and its source location.
`AuditRecord` has no field for either.

**Agreed:** `AuditRecord` gains one optional field, `origin`, e.g. `recipe io.undertow:sessions from
jar:file:/.../undertow-core.jar!/META-INF/logaperture/recipes.yaml`, printed by the stderr audit log as
`origin=...`. Its `source` is `recipe`. Every other audit record leaves `origin` empty.

### B6 — The `APPLIED` column

#7 has `list recipes` show `partly` when some of a recipe's changes were changed by hand. Knowing
that needs the list of changes the apply made, which #7 deliberately avoids storing: #4's skips mean
it isn't simply the recipe's entries.

**Agreed:** show a count instead: `APPLIED` is `-` when nothing carries the recipe id, otherwise
the tier and how many of the recipe's entries still carry it, e.g. `for, 22m left (2 of 3)`. The
tier is the one that ends soonest when they differ. A skipped entry counts as not applied, which is
what it is. This replaces #7's `partly` wording; the rule itself (a change made by hand drops the
tag) is unchanged.

### B7 — Applied, but no longer on offer

A library's recipe disappears from `list recipes` when the library is undeployed, and a folder file
can be deleted, while changes it made are still in force.

**Agreed:** `list recipes` also lists every recipe id that live changes carry but no source offers,
with `SUMMARY` `(no longer offered)` and its `APPLIED` count, so it can still be found and reset.
`reset recipe` works by id alone (#9), so it needs nothing more. `show` and `apply` refuse it.

### B8 — Library raise-only across contexts

#4 compares against the live level. On WildFly with several logging contexts, one logger can have
a different level in each.

**Agreed:** an entry is skipped if it would lower the level in any context, the same way the
capability pre-check already judges raise-versus-lower per context (`AggregateLevelControl`).

### B9 — Handler entries that can't be resolved yet

On WildFly, handler names resolve once the server's logging model is up; before that, a named
handler isn't known.

**Agreed:** an unknown handler is a refusal in the pre-check (#5), so the whole recipe is refused
with `handler FILE is not known yet`. Consistent with #5, and a vendor or folder recipe naming
handlers is operator-placed and can be retried.

### B10 — `apply recipe` output and `--json`

**Agreed:** one line per change, like `set logger` / `set handler` / `add rule`, then `Applied
recipe io.undertow:sessions (2 changes, for 4h).` Skipped entries are listed after it. `--json`
prints one object: the recipe id, tier, expiry, and the changes applied and skipped, each change
shaped as `show recipe --json` shapes it, plus the new rule ids. `--json` never prompts, so it
needs `--yes`, as a pattern `set logger` without a terminal does.

### B11 — `reset recipe` output

**Agreed:** one `logctl reset ...`-style line per change put back, then `Reset recipe
io.undertow:sessions (2 changes).` Sticky changes left in place are listed with `(sticky, kept -- add
--include-sticky)`. Nothing tagged: `Nothing to reset: no changes carry recipe io.undertow:sessions.`,
exit 0. `--json`: the ids and names reset and kept.

### B12 — Guided prompting, in detail

#10 fixes the behaviour. **Agreed** additions, following `guided-commands.md`:

- The recipe pick list shows `ID  SUMMARY  SOURCE`, the same columns as `list recipes`. An
  ambiguous id (epic #20) is listed once per source, so picking one also picks `--from`.
- The tier question is the same one guided `set logger` asks (`for 4h` default).
- The printed command includes `--from` only when the id is ambiguous.
- Guided `reset recipe` picks one or more from the applied recipes (B6's rows with a count, B7's
  included), asks about sticky changes once as guided `reset` does (guided-commands.md #13), then
  prints one `logctl reset recipe` line each.

## Settled during implementation

Slice (a):

- **Discovery on WildFly works through the deployment's class loader.** Checked on WildFly 26 by
  `WildFlyContainerIT`: a war's recipes are found both in `WEB-INF/classes` and in a `WEB-INF/lib`
  jar that has no classes of its own, with sources labelled `probe.war!/WEB-INF/classes` and
  `probe.war!/WEB-INF/lib/probe-recipes.jar`. No WildFly-specific code (#3 holds).
- **`list recipes` columns.** No `APPLIED` column yet: nothing can be applied until slice (b), which
  adds it. A recipe offered by several sources shows the first one's label and `(+N more)`.
  `--verbose` shows the full location and a `NOTE` column (`shadowed`, `needs --from`, `also
  <labels>`) in place of `APPLIED`, then each unreadable file's errors. When recipes share an id but
  differ, a line after the table says to pick one with `show recipe <id> --from <source>`.
- **`show recipe`** leaves out the "Switch it on with" line until `apply recipe` exists (slice
  (b)). A logger or handler not known yet shows `—` as its current level. The notes shown now are
  the library raise-only skip (#4) and a protected category; capability refusals come with slice
  (b)'s pre-check (#5).
- **`--from`** matches a source's label or its full location.
- **Block text** (#2) is stored without its final line break, since every field is shown inline:
  the value `|-` would give, though only `|` is accepted.
- **Rule entries** in a recipe may leave out `id`; the entry is then labelled `rule-<n>`, the lowest
  `n` no other rule in the recipe uses (only a label -- applying it will give an ordinary `rN` id;
  but export writes it back, so it must not repeat an explicit id).
- **Recipe folder**: only `*.yaml` files are read, in name order. Library files are cached by URL
  and last-modified time (a `jar:`/`file:` URL uses the file's own time, so checking never opens a
  jar); a library no longer loaded is forgotten.
- **MXBean** (#12): `listRecipes()` returns `RecipeListData` -- the recipes plus the unreadable
  files (#11) -- rather than a bare list. `showRecipe(id, from)` returns `RecipeDetailData`.
- **`doctor`** adds the `recipe-files` check beside the per-context checks: an `OK` row when every
  recipe file reads cleanly, an `INFO` finding per unreadable one, nothing when there are none.
- **Guided `list`** offers `recipes` as a fourth answer. A one-letter answer now counts only when no
  other choice starts with it, so `r` (rules or recipes) is asked again.
- **The vendor defaults file** counts its recipes in `status`/`env` (`… , 2 recipes`), and
  `logctl export vendor-defaults` carries its `namespace:` and `recipes:` over unchanged -- without
  that, exporting would silently drop them.

Slice (b):

- **Carrying the id.** A small `api` type, `RecipeTag` (the recipe id and where it came from), rides
  on `SetLevelOptions`, `SetHandlerLevelOptions` and `RuleAttachOptions`; each keeps its old
  constructor, so nothing else changes. Logger and handler overrides store the id in a new `recipe`
  component. A rule's id lives in a map inside `RuleService` (rule id to recipe id), persisted on
  `PersistedRule`, rather than on `LogRule`, which no rule type needs to know about. `alter rule`
  drops it (#7); an `AUTO` handler's recompute, which follows the logger floor, keeps it.
- **Audit (B5).** `AuditRecord` gained `origin`; a recipe's change is audited with source `recipe`,
  and the stderr audit log appends `origin=recipe <id> from <location>`.
- **The pre-check (#5)** applies what the equivalent commands check. A logger raise needs
  `LEVEL_RAISE`, a lower `LEVEL_LOWER`, judged against every context's level; a logger not known
  yet counts as a raise. A handler let through more (or set to `AUTO`) needs `HANDLER_LOWER`, else
  `HANDLER_RAISE`. A rule needs `RULES_AUTHOR` and `SUPPRESS`. Any non-`session` tier needs
  `PERSIST`. A protected category refuses a logger or rule entry. `show recipe` shows the same
  refusals, less `PERSIST`, since it has no tier.
- **Fingerprint (B1):** the first 8 bytes of a SHA-256 of the recipe's content, as hex. The same
  recipe from any source has the same fingerprint.
- **`list recipes`** always has the `APPLIED` column now. A recipe no longer offered (B7) shows `—`
  as its source and counts changes rather than entries: `sticky (2 changes)`.
- **`RECIPE` column** (#6) in `list loggers`, `list handlers` and `list rules`, shown when a row has
  one. `LoggerInfoData`, `HandlerInfoData` and `RuleData` gained the attribute (plus `recipe` in
  their `--json`); the override types `status` uses did not, since `status` doesn't show it.
- **Output (B10, B11):** `apply` prints `logger <name> → <level>`, `handler <name> → <level>` and
  `r7 rule trim <logger> <options>` lines, `Skipped ... -- <why>` lines, then `Applied recipe <id>
  (N changes, <tier as set logger shows it>).`; `reset` prints `logger <name> reset.`, `rule r7
  removed.`, `<kind> <name> (sticky, kept -- add --include-sticky)`, then `Reset recipe <id> (N
  changes).`
- **Guided (B12):** guided `apply` asks only how long, as #10 says; `--reason` on the command line is
  used and printed. Guided `reset recipe` asks about sticky changes when a picked recipe's
  soonest-ending change is sticky; a sticky change in a recipe with a shorter-lived one too is then
  kept, and the output says so with the `--include-sticky` hint.
- **WildFly test fix (#69).** The IT's probe servlet now keeps a static reference to its logger. An
  unreferenced, unconfigured logger can be garbage-collected out of JBoss LogManager's names, so
  `list loggers` intermittently missed it after a deploy -- the symptom #69 tracks. Two of three runs
  of the recipe ITs failed that way before the change; three of three passed after.

## Testing

- Parser: recipe files, namespace rules, reserved namespaces, block scalars, library files carrying
  `handlers:`/`rules:` (error), other top-level keys in a library file (ignored + diagnostic).
- Discovery: a test agent with recipe resources on two class loaders, the same URL seen through two
  loaders (listed once), a jar loaded after the first `list recipes`, a broken file.
- Collisions (epic #20): identical same-id library recipes merged; differing ones both listed and
  `apply` refusing without `--from`; a folder recipe shadowing a library one.
- Apply/reset: tiers, overwrite of an existing override, hand-change then `reset recipe` (left alone),
  two recipes on one logger, sticky skip and `--include-sticky`, state file round trip at schema 10,
  schema-9 file loads.
- Refusals: library lowering skipped (#4); protected category refuses the whole recipe (#5).
- `WildFlyContainerIT`: a recipe in a deployed war's `WEB-INF/classes/META-INF/logaperture/` is listed,
  applied and reset.

## Decision table

| # | Decision | Status |
|---|---|---|
| 1 | Vendor defaults file gains `namespace:`, required with `recipes:`; reverse-domain recommended, not enforced; `vendor`, `logaperture`, `org.logaperture` reserved | **Agreed** |
| 2 | Parser gains literal block scalars (`\|`, clip chomping only) | **Agreed** |
| 3 | Location is `META-INF/logaperture/recipes.yaml` on the class path (war: `WEB-INF/classes/…`) | **Agreed** |
| 4 | Library recipes only raise levels; a lowering entry is skipped at apply time and shown | **Agreed** |
| 5 | A recipe applies all or nothing (except #4 skips); every change checked first | **Agreed** |
| 6 | `recipe` field on overrides and rules; state schema 10; `RECIPE` column shown when used | **Agreed** |
| 7 | "Changed by hand" = the record no longer carries the recipe id; `partly` in `list recipes` | **Agreed** |
| 8 | Reason: entry `reason`, else `--reason`, else `recipe <id>` | **Agreed** |
| 9 | `reset recipe` has `--include-sticky`, no `--to-native`, no confirmation | **Agreed** |
| 10 | Guided `apply recipe` picks one; guided `reset recipe` picks applied ones | **Agreed** |
| 11 | Broken files: counted in `list recipes`, detailed with `--verbose`, `doctor` info finding | **Agreed** |
| 12 | Four new MXBean operations; recipes resolved and applied agent-side | **Agreed** |
| 13 | Per-change capability checks; audit source `recipe` with id and location | **Agreed** |
| 14 | Beta 1, two slices (read, then apply/reset); (b) moves to 1.1 before the beta date moves | **Agreed** |
| B1 | `applyRecipe` takes the fingerprint `showRecipe` returned; a changed recipe applies nothing | **Agreed** |
| B2 | Apply loggers, handlers, rules in order; stop at a failure, keep what applied, report both | **Agreed** |
| B3 | Re-apply removes the recipe's still-tagged rules and adds them again (new ids) | **Agreed** |
| B4 | `recipe:` line per record, state schema 10; kept across redeploy; not exported | **Agreed** |
| B5 | `AuditRecord` gains an optional `origin` field | **Agreed** |
| B6 | `APPLIED` shows tier and `N of M` entries still tagged, replacing #7's `partly` | **Agreed** |
| B7 | Recipes no longer offered but still applied are listed as `(no longer offered)` | **Agreed** |
| B8 | A library entry is skipped if it would lower the level in any context | **Agreed** |
| B9 | An unknown handler refuses the whole recipe in the pre-check | **Agreed** |
| B10 | `apply` prints a line per change and a summary; `--json` needs `--yes` | **Agreed** |
| B11 | `reset recipe` prints what it put back and any sticky ones kept | **Agreed** |
| B12 | Guided pick list, tier question, `--from` only when ambiguous; guided reset picks applied ones | **Agreed** |
