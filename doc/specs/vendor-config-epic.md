# Epic: vendor configuration file — shipped logging defaults

Status: **signed off 2026-09-24** — all 21 decisions agreed. Not yet implemented.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.10, §18.11, §18.12 (roadmap
entries), §6.6 (precedence item 3, "static rules file supplied at agent start"), §9.2–§9.5
(policy layering, capabilities, protected categories), §16.6 (vendor-authored, customer-applied).
Members: [#60](https://github.com/ddeuchert/logaperture/issues/60) (the file),
[#61](https://github.com/ddeuchert/logaperture/issues/61) (reset falls back to it),
[#62](https://github.com/ddeuchert/logaperture/issues/62) (export live tuning as a file),
[#92](https://github.com/ddeuchert/logaperture/issues/92) (library-bundled recipes).
Builds on: [`persistence.md`](persistence.md), [`reset-command-surface.md`](reset-command-surface.md),
[`handler-floor-control.md`](handler-floor-control.md),
[`rule-pipeline-foundation.md`](rule-pipeline-foundation.md), [`drop-rule.md`](drop-rule.md),
[`trim-rule.md`](trim-rule.md).

## Functional summary

After this epic, the user will be able to:

- Ship a vendor defaults file with their product and point LogAperture at it on the Java command line, so
  every install starts with the logger levels, handler levels, and `drop`/`trim` rules they chose.
- Override any of those defaults with the usual `logctl set` / `add rule` commands.
- Run `logctl reset` and land back on the shipped defaults, not on the framework's own settings.
- See in `logctl list` which settings came from the vendor defaults file.
- Tune logging live with `logctl`, then run `logctl export vendor-defaults` to write the result out as the
  vendor defaults file for the next release.
- Run `logctl list recipes` to see the logging recipes offered by the libraries in the running
  application (for example an Undertow recipe for watching HTTP session handling), read what one
  turns on with `logctl show recipe`, and switch it on for a while with `logctl apply recipe`.
- Ship their own recipes, in their product's jars or in the vendor defaults file, so their support team
  can say "apply recipe acme:billing" instead of dictating logger names.

## The idea

§6.6 has always listed a third precedence layer, "static rules file supplied at agent start",
between persisted state and the application's own configuration. Nothing implements it. This epic
builds it, for the user §18.10 describes: a vendor bundling LogAperture who wants every install to
start quieter (or louder, for support-critical categories) than the framework's own defaults,
without editing `standalone.xml` or scripting `logctl` after install.

The vendor defaults file is **configuration, not policy**. It says what the starting point is. It never
grants or denies a capability, and never sets a protected category or verbosity ceiling; those
belong to the sealed-at-boot policy file (§9.4), which is a separate, later item.

Proposed stack after this epic, highest first (Decision #2):

1. Runtime changes made this session (`logctl set`, `add rule`).
2. Persisted state from earlier sessions (`for`, `sticky`).
3. **The vendor defaults file.** ← new
4. The application's own logging configuration (native baseline).

## Members and build order

1. **#60 + #61 together** (Decision #1): read the file at startup, apply it as a layer under
   persisted state, and make `reset` land on it.
2. **#62**: `logctl export vendor-defaults`.
3. **Library-bundled recipes** ([#92](https://github.com/ddeuchert/logaperture/issues/92)): a metadata convention for jar/war/ear files,
   plus `logctl list/show/apply/reset recipe`. Decisions #14–#21.

## Decisions

Numbered for citation during sign-off. Each carries a recommendation.

### #1 — Ship #60 and #61 as one slice — **Agreed**

Options: (a) #60 first with defaults applied as ordinary overrides, #61 later reworks `reset`;
(b) one slice that designs the layer and `reset` behaviour together.

**Agreed (b).** Whether `reset` lands on the vendor value is decided by what a vendor default
*is* (Decision #2). If #60 ships alone, `reset` discards vendor defaults until #61 arrives, which
is the exact behaviour #61 was filed to prevent. Under (b), #61 costs little extra: reset already
goes "to baseline", and the baseline now includes the file.

### #2 — A vendor default is part of the baseline, not an override — **Agreed**

Options: (a) baseline layer: effective baseline = native config with the file layered on top;
overrides sit above it exactly as they do now. (b) Pre-applied overrides tagged
`source = vendor-defaults`, living in the override registry.

**Agreed (a)** (2026-09-24): the vendor defaults file is the authority on the baseline logging
configuration. For every setting it names it wins over the application's own configuration,
and it is what `reset` returns to. It matches §6.6's stack and #61's intent ("behaves the way native config
behaves today"). Consequences: `reset logger X` returns to the file's level; an override on X wins
while active; the file never appears as a persisted state-file row.

### #3 — What the file can hold — **Agreed**

| Setting | Agreed |
|---|---|
| Logger levels | Yes |
| Handler levels, including `AUTO` | Yes |
| `drop` and `trim` rules | Yes |
| `DEFAULT_HANDLERS` membership | Yes |
| Capabilities, protected categories, verbosity ceilings | No. Policy file, §9.4 |
| Expiring (`for`) entries | No. A shipped default has no clock to count from |

### #4 — Vendor rules and `reset` — **Agreed**

Levels have a natural "reset to" value. Rules don't: a vendor `drop` rule is additive, and a
support engineer may need to see what it hides.

**Agreed:** vendor rules are part of the baseline. `reset rules` and `reset logger X` leave
them in place and report them, the same way sticky rules are reported. Naming one directly with
`reset rule <id>` refuses unless `--include-vendor-defaults` is given; with it, the rule is suspended
until the JVM restarts (audited), and the file is untouched. Vendor rule ids come from the file
(`vendor:<name>`), so support documentation can cite them and they're stable across restarts.

### #5 — File format — **Agreed**

Options: (a) YAML, hand-parsed, same approach as the state file (no new dependency);
(b) a script of `logctl` command lines; (c) Java properties.

**Agreed (a).** It is §6.3's precedent and §6.6's own example (`/etc/logaperture.yaml`). (b)
reads well but puts the CLI grammar inside the agent, and tier words, previews and `--yes` would
all need "not allowed here" rules. (c) can't express a rule with several matchers cleanly. Sketch:

```yaml
schemaVersion: 1
loggers:
  - name: org.hibernate.SQL
    level: WARN
  - name: com.acme.support
    level: DEBUG
handlers:
  - name: FILE
    level: INFO
  - name: CONSOLE
    level: AUTO
defaultHandlers: [CONSOLE]
rules:
  - id: healthcheck-noise
    action: drop
    logger: com.acme.health
    messageContains: "ping ok"
  - id: autoupdate-trace
    action: trim
    logger: com.destiny.AutoUpdateHelper
    throwable: java.net.ConnectException
    below: FATAL
    frames: 0
```

Field names follow the `logctl` option names (`--message-contains` → `messageContains`), so the
docs for one serve the other.

### #6 — How it's referenced, and what it's called — **Agreed (revised)**

Every existing agent knob is a system property, and the agent ignores its `-javaagent:…=args`
string today. The recommendation was `-Dlogaperture.defaults=<path>`.

**Agreed instead (2026-09-24):** an agent argument, kebab-case like the rest of LogAperture's
user-facing options:

```
-javaagent:/path/to/logaperture-agent.jar=--vendor-defaults=./vendor-defaults.yaml
```

- It is a little more protected than a system property. A system property can also come from
  `JAVA_TOOL_OPTIONS`, a properties file, or container configuration (WildFly's
  `<system-properties>`, settable through the management CLI), and application code can read it.
  An agent argument exists only in the `-javaagent` string, and only the agent sees it.
- This is the first agent argument, so slice 1 adds a small parser for the args string:
  `--name=value` options separated by commas. An unknown option is a startup diagnostic, not a
  failure (fail-open, §9).
- A relative path resolves against the JVM's working directory; diagnostics, `logctl env` and
  `logctl doctor` always show the resolved absolute path.
- §6.6 item 3's `config=` example is updated to `--vendor-defaults=`.
- Signing the file was considered and deferred as overkill for now; §9.4's signing is aimed at
  packs that travel (emailed during a support call), not a file installed alongside the product.

**Name:** "vendor defaults" everywhere a user sees it: the *vendor defaults file*,
`logctl export vendor-defaults`, rule ids `vendor:<name>`, `reset rule … --include-vendor-defaults`,
the `vendor-defaults` origin in `list`, and audit source `vendor-defaults`.

### #7 — Targets are exact names only — **Agreed**

A leading-star target (`*.Name`) is a one-time expansion against loggers that exist at that
moment (`pattern-selection-semantics.md`). At startup almost none exist, so it would silently
match nothing. **Agreed:** exact logger names only; a subtree is covered by naming its root, as
with `set logger`. A default for a logger or handler that doesn't exist yet applies when it
appears (the existing sweep for loggers; handler-name resolution for WildFly handlers).

### #8 — Loading and failure — **Agreed**

**Agreed:**

- Read once at agent install, after native baseline capture and before persisted-state resume.
  No hot reload; a changed file takes effect on restart.
- All or nothing. Any error (syntax, unknown level, a rule that would suppress a protected
  category or FATAL) rejects the whole file: nothing from it is applied, a loud diagnostic names
  the file and line, and the JVM starts normally. A half-applied vendor file is harder to reason
  about than a rejected one.
- A missing file named on the command line is an error in the same way; no property set means no
  vendor defaults file, silently.
- **Writable-file warning** (agreed 2026-09-24): at load, the agent checks whether the file is
  writable by the account the JVM runs as, the same check §9.4 makes for the policy file. If it
  is, the file still loads, but a warning names the file and says anyone who can run code as that
  account can change the baseline; `logctl doctor` repeats it as a finding. A warning, not a
  refusal, because this file is configuration, not policy.
- `logctl doctor` reports a rejected file; `logctl env` reports its path and load result.

### #9 — Persisted user state beats the file — **Agreed**

A customer's `sticky` override made against v1 of the file still wins after the vendor ships v2.
**Agreed:** yes, per §6.6 order, no special handling. Flagging "your sticky override now
shadows a changed vendor default" in `doctor` is deferred as a possible follow-up.

### #10 — Visibility in `list` — **Agreed**

**Agreed:** `list loggers`, `list handlers` and `list rules` gain an origin marker `vendor-defaults`.
`list loggers`'s default view (overrides only) also shows loggers set by the file, since those are
settings the operator didn't make and should be able to see.

### #11 — Capability and audit — **Agreed**

**Agreed:** no capability check at load. The trust boundary is whoever controls the JVM
command line and the file, the same as for every other agent property. Protected categories and
the FATAL limit still apply (enforced as validation, Decision #8). One audit record per applied
entry, `source = vendor-defaults`; a `reset rule --include-vendor-defaults` suspension is audited like any
other reset.

### #12 — Export (`#62`) — **Agreed**

**Agreed:**

- Spelling: `logctl export vendor-defaults`, writing to stdout; `--out <file>` writes a file and refuses
  an existing one unless `--force`.
- Content: the file currently loaded, with sticky overrides and sticky rules layered on top (sticky
  wins). A vendor can load v1, tune live, export, and get v2. Session and `for` settings are left
  out, per #62.
- Protected-category violations can't occur: they were refused when the sticky setting was made.

### #13 — Release placement — **Agreed**

All three issues are on the alpha-3 milestone. **Agreed:** #60 + #61 in alpha-3; #62 in alpha-3
if it's ready, otherwise alpha-4 with no dependency left behind. Recipes: see Decision #21.

## Library-bundled recipes

A library knows best which of its logger categories are worth watching, and for what. Undertow's
maintainers know that watching HTTP session handling means `io.undertow.server.session` at `DEBUG`
(and maybe `io.undertow.request` at `DEBUG` too); an operator today has to dig that out of forum posts. This slice adds a
metadata convention a library, war or ear can use to document and bundle those
recommendations, and `logctl` commands to discover them and switch them on.

This is §16.5's "vendor-supplied named profiles" idea (the level-selection half; capture bundles
stay in M3), widened from "the vendor ships them" to "any library can ship them", and a first step
toward §18.6's rule-pack registry.

Sketch of what a user would type:

```
logctl list recipes
logctl show recipe undertow:sessions
logctl apply recipe undertow:sessions for 30m
logctl reset recipe undertow:sessions
```

And what a library would carry, at `META-INF/logaperture/recipes.yaml`:

```yaml
schemaVersion: 1
namespace: undertow
recipes:
  - name: sessions
    summary: Watch HTTP session creation, expiry and invalidation
    description: |
      Logs each session create/expire/invalidate with its id. Moderate volume
      under load. Session ids are sensitive; don't leave this on.
    loggers:
      - name: io.undertow.server.session
        level: DEBUG
      - name: io.undertow.request
        level: DEBUG
```

### #14 — What to call them — **Agreed**

"Profile" is §16.5's word, but WildFly already has *logging profiles* (per-deployment logging
config, the `logging-profile` subsystem resource), and LogAperture is WildFly-first. `logctl list
profiles` would be misread. **Agreed "recipe"**: a named, documented set of settings for
watching one thing. §16.5 is updated to use the same word.

### #15 — Where recipes come from — **Agreed**

- (a) Inside a jar, war or ear, at `META-INF/logaperture/recipes.yaml`.
- (b) The vendor defaults file's own `recipes:` section: the vendor's product-specific support recipes
  (§16.5's original case).
- (c) A recipes directory (`${logaperture.home}/recipes/`, or `-Dlogaperture.recipes=<dir>`), for
  recipes written about a library that doesn't ship its own. This is the realistic path for
  Undertow until upstream projects adopt the convention.

**Agreed all three, one file format.** A jar-embedded file is the same format as the vendor defaults
file, but only its `recipes:` section is read; anything else in it is ignored with a diagnostic.
Distributing recipe packs (a registry, a download command) stays out of scope (§18.6).

### #16 — How jar-embedded recipes are discovered — **Agreed**

The agent can't list every class loader directly, and on WildFly each deployment and each module
has its own. Options: (a) watch class loading and look for the resource the first time each new
jar/module appears; (b) on demand, when `logctl list recipes` runs: walk the loaded classes
(`Instrumentation.getAllLoadedClasses`), collect distinct class loaders and code sources, and ask
each for the resource, caching results by code source.

**Agreed (b).** It costs nothing until someone asks, and adds no work on the class-loading path.
A consequence: a library's recipes appear once the library has loaded. That's right for
this use case, since you can only watch what's running.

### #17 — What a recipe can contain — **Agreed**

**Agreed, by source:**

- From a jar (a): logger levels plus the summary/description/per-logger notes. No rules and no
  handler levels. A dependency should be able to suggest *more* visibility; it should not be able
  to ship something that hides output.
- From the vendor defaults file or recipes directory (b, c), which the operator or vendor put there
  deliberately: logger levels, handler levels, and `drop`/`trim` rules.

### #18 — Applying and reverting — **Agreed**

**Agreed:**

- `apply recipe <id> [session | for <duration> | sticky]`: default tier and limits are the same as
  `set logger` (`for`, per §6.1). It creates ordinary overrides, one per logger, after the same
  preview + `--yes` confirmation a pattern `set logger` uses, showing the level changes.
- Each override records the recipe it came from (shown in `list loggers`). `reset recipe <id>`
  resets the overrides that still carry that recipe; one the operator has since changed by hand is
  left alone.
- Applying a recipe never touches the vendor-defaults-file layer (#2): it sits in layer 1 or 2 like any
  other `logctl` change. The vendor defaults file stays the only authority on the baseline; a recipe
  can't change what `reset` returns to.

### #19 — Trust and safety — **Agreed**

**Agreed:**

- A recipe is never applied automatically, from any source. Discovery only lists it.
- Applying one needs the same capabilities as making those changes by hand (`level.raise`,
  `persist` for a non-session tier, `rules.apply` for rules), and it's audited with the recipe id
  and its source location.
- Protected categories and verbosity ceilings (§9.5) apply as they would to the equivalent
  commands.
- `show recipe` prints the exact changes and the source jar path before anything is applied.
- A vendor defaults file turning a library recipe on at startup (`activeRecipes: [...]`) is deferred; for
  now the vendor copies the levels into the file. If added later, it would be the vendor defaults file
  adopting the recipe *into the baseline*, so the authority stays with the file.

### #20 — Ids and collisions — **Agreed**

**Agreed:** id = `<namespace>:<name>`, namespace declared in the file. When two sources declare
the same id (two deployments bundling different versions of one library), identical recipes are
merged into one entry; recipes with different content are both listed with their source, and
`apply` refuses until the user picks one with `--from <source>`. A recipe from the vendor defaults file
or recipes directory with the same id as a jar's takes precedence, so a vendor can correct a
library's recipe.

### #21 — Placement — **Agreed**

**Agreed:** separate issue ([#92](https://github.com/ddeuchert/logaperture/issues/92), filed on
beta-1), built as slice 3 after #60–#62 because it reuses the vendor-defaults-file parser. Its own member spec (`doc/specs/recipes.md`) at build time.

What it changes in slice 1, since the design is settled here: the vendor-defaults-file format (#5) is
the recipe format too, so slice 1's parser is written with `recipes:` as a known future top-level
section, and the per-entry shapes for loggers, handlers and rules are shared by both, not
duplicated. Slice 1 still rejects a `recipes:` section (all or nothing, #8) until slice 3 lands.

## Out of scope

- The sealed-at-boot policy file (capabilities, protected lists, ceilings — §9.4/§9.5).
- Signed rule packs and a rule-pack registry (§9.4, §18.6).
- Capture bundles (§16.5's `logctl capture`); only the named-recipe half is pulled in.
- A recipe registry or download command (§18.6).
- `logaperture.rulesReadOnly` / apply-but-not-author mode (§16.6).
- Hot reload of the vendor defaults file.
- Filtering events logged before the container's logging exists (§18.14): the file arms rules at
  the same point persisted sticky rules are armed, no earlier.
