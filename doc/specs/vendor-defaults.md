# Vendor defaults file — load and reset (slice 1: issues #60 + #61)

Status: **signed off 2026-09-24** — epic decisions settled; member decisions M1–M9 agreed.
Implemented on `feature/60-vendor-defaults`; see "Settled during implementation" at the end.
Parent spec: [`vendor-config-epic.md`](vendor-config-epic.md) (signed off 2026-09-24; decisions
cited as "epic #N"), [`doc/logaperture-spec.md`](../logaperture-spec.md) §6.6, §9.
Builds on: [`persistence.md`](persistence.md), [`level-control.md`](level-control.md),
[`handler-floor-control.md`](handler-floor-control.md),
[`rule-pipeline-foundation.md`](rule-pipeline-foundation.md), [`drop-rule.md`](drop-rule.md),
[`trim-rule.md`](trim-rule.md), [`reset-command-surface.md`](reset-command-surface.md),
[`list-command-surface.md`](list-command-surface.md), [`doctor.md`](doctor.md),
[`environment-report.md`](environment-report.md).

## Functional summary

After this feature, the user will be able to:

- Start their application with
  `-javaagent:logaperture-agent.jar=--vendor-defaults=/path/vendor-defaults.yaml` and have the
  logger levels, handler levels, default handlers and `drop`/`trim` rules in that file in effect
  from startup.
- Change any of those settings with the usual `logctl` commands, and get back to the vendor's
  value with `logctl reset`.
- See which loggers, handlers and rules have vendor defaults in `logctl list`, and whether the
  file loaded in `logctl env`, `logctl status` and `logctl doctor`.
- Temporarily switch off a vendor rule with `logctl reset rule vendor:<name> --to-native`; it
  comes back with a plain `logctl reset rule vendor:<name>` or on the next restart. (Originally
  `--include-vendor-defaults`; replaced by [`alter-rule.md`](alter-rule.md) (issue #96).)
- Rely on a broken file never stopping the application from starting: it is rejected as a whole,
  with every problem listed.

## Scope

**In scope:** the `--vendor-defaults=` agent argument and the agent-argument parser; the file
format, parser and validation; applying the file as the baseline layer for logger levels,
handler levels (including `AUTO`), `DEFAULT_HANDLERS` membership, and `drop`/`trim` rules; `reset`
landing on it; reconfiguration and verification-sweep re-application; `list`/`status`/`env`/
`doctor`/audit surfaces; the writable-file warning.

**Out of scope:** `logctl export vendor-defaults` (#62, next slice); recipes (#92, epic slice 3 —
a `recipes:` section is rejected by this slice, epic #21); `useParentRules` in the file (M9);
hot reload (epic #8); policy (epic #3).

## Agent arguments

This is LogAperture's first agent argument. `LogApertureAgent.premain`/`agentmain` pass their
`agentArgs` string to `AgentBootstrap.start`, which parses it before container detection.

```
-javaagent:/opt/app/lib/logaperture-agent.jar=--vendor-defaults=/opt/app/conf/vendor-defaults.yaml
```

- Grammar: zero or more `--name=value` options separated by commas. A comma only separates
  options when it is followed by `--`, so a path containing a plain comma works (M8).
- Names are kebab-case. This slice defines one: `--vendor-defaults`.
- An unknown option, a missing `=value`, or a repeated option is a startup warning through
  `Diagnostics`; the rest of the arguments still apply. Arguments never stop the agent or the JVM
  (fail-open, §9).
- A relative path resolves against the JVM's working directory (`user.dir`) at `premain`. Every
  later report (diagnostics, `env`, `status`, `doctor`) shows the resolved absolute path.
- Parsing runs on the `premain` thread, before WildFly's LogManager exists. It must not touch
  `java.util.logging` (the "premain gotcha", `logaperture-spec.md` §15.6); reading the file and
  reporting through `Diagnostics` is safe.

## The file

### Format

A YAML subset, parsed by a hand-written parser in `core` (same approach and reasoning as
`StateFileFormat`, `persistence.md` "File format"; epic #5). Supported: block maps, block lists of
maps, flow lists of scalars (`[A, B]`), plain and double-quoted scalars, `#` comments. Not
supported: anchors/aliases, multiple documents, block scalars, tabs for indentation. Encoding
UTF-8.

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
    below: WARN
    reason: "load balancer health probes"
  - id: autoupdate-trace
    action: trim
    logger: com.destiny.AutoUpdateHelper
    throwable: java.net.ConnectException
    messageContains: "Failed to connect"
    below: FATAL
    frames: 0
```

### Schema

| Key | Shape | Notes |
|---|---|---|
| `schemaVersion` | integer, required | must be `1` |
| `loggers` | list, optional | `name` (exact logger name), `level` (a `Level`), `reason` (optional text) |
| `handlers` | list, optional | `name` (handler name as `logctl` shows it), `level` (a `Level` or `AUTO`), `reason` |
| `defaultHandlers` | flow or block list of handler names, optional | baseline `DEFAULT_HANDLERS` membership |
| `rules` | list, optional | see below |

Rule entry fields, named after the `logctl add rule` options (`--message-contains` →
`messageContains`):

| Field | Applies to | `logctl` option |
|---|---|---|
| `id` (required) | both | — ; `[a-z0-9-]{1,40}`, becomes `vendor:<id>` |
| `action` (required) | both | `drop` or `trim` |
| `logger` (required) | both | the `<logger-target>`, exact name only (epic #7) |
| `below` | both | `--below LEVEL` |
| `messageContains` / `messageContainsIgnoreCase` | both | `--message-contains` / `--message-contains-ignore-case` |
| `throwable` | both | `--throwable <type>` |
| `throwableMessageContains` | both | `--throwable-message-contains` |
| `anyCause` | both | `--any-cause` (boolean) |
| `sampleFull` | drop | `--sample-full <duration>`, or `false` for `--no-sample-full` |
| `frames` | trim | `--frames N` |
| `collapseCauses` | trim | `--collapse-causes` (boolean) |
| `reason` | both | `--reason` |

Defaults for omitted fields are the `add rule` defaults. No tier fields: vendor entries have no
tier or expiry (epic #3).

### Validation — all or nothing (epic #8)

The whole file is validated before anything is applied. Any error rejects the whole file. Errors
are **collected, not stopped at the first** (M6), each with its line number:

- Syntax the subset doesn't support; tabs; `schemaVersion` missing or not `1`.
- An unknown top-level key or an unknown field in an entry (catches typos such as `levle:`).
  `recipes:` gets its own message: supported once library recipes (#92) land.
- A logger or rule `logger` containing `*` (epic #7).
- An unknown level; `AUTO` on a logger.
- Duplicate logger names, duplicate handler names, duplicate rule ids.
- A rule whose fields fail the same validation `add rule` applies: built through the same
  `DropFactories`/`TrimFactories` path as a live rule, so protected categories (§9.5) and the FATAL
  hard limit are enforced identically, not re-implemented.

Handler names cannot be checked at load: a handler may not exist yet (WildFly resolves names
later). An unresolved handler is not an error; see "Handlers" and M7.

A missing or unreadable file named by `--vendor-defaults` is an error of the same kind. No
argument means no vendor defaults, silently.

### Writable-file warning

After a successful load, the agent checks whether the account the JVM runs as can write the file
or its directory (a writable directory lets the file be replaced). If so, the file still applies,
and a warning names it: anyone who can run code as this account can change the baseline. `doctor`
repeats it as a finding. Same check as §9.4's policy-file rule, but a warning, not a refusal: this
file is configuration, not policy.

## The baseline layer

The file is parsed once per JVM into an immutable `VendorDefaults` (core), handed to the container
integration, and applied by each context's `installContext`. On WildFly today that is the one
`system` context; the same values would apply to every context, matching how overrides broadcast.

### Loggers

- **Effective baseline** of a logger = its vendor level if the file names it, else its captured
  native level. `BaselineRegistry` keeps capturing the native value unchanged and gains the vendor
  layer: `get(name)` returns the effective baseline (what every reset, expiry and "undo" path
  already calls), and a new `nativeLevel(name)` returns the captured native value for display.
- **Install order**, per context, inside the existing `installContext` sequence:
  1. Capture native baselines for every known logger (unchanged), then for every logger the file
     names. Capturing before applying is what keeps the native value from being read back as
     the vendor's.
  2. Apply each vendor logger level through the adapter, eagerly, whether or not the logger exists
     yet (M1). One audit record each.
  3. Resume persisted state (unchanged): overrides and rules resumed from the state file sit on
     top.
- `reset logger X` / `reset loggers` / expiry of a `for` override on a logger with a vendor default
  applies the vendor level, not the native one; the audit `newValue` shows it. A logger with a
  vendor default and no override has nothing to reset (unchanged behaviour for "no override").
- An operator who wants the native level back uses `set logger X <level>`; there is no "reset
  past the vendor layer" (epic #2: the file is the authority on the baseline). **Superseded by
  [`reset-to-native.md`](reset-to-native.md) (issue #94):** `reset … --to-native` lands on the
  native level until restart, and a plain reset undoes it.

### Handlers

- Same shape on `HandlerBaselineRegistry`: effective baseline = vendor level if named, else
  captured native.
- A vendor handler level is applied the first time the handler resolves: at install for handlers
  that already resolve; otherwise retried on each verification-sweep tick (default every 30 s,
  `logaperture.sweep.seconds`) until it does. Native capture happens first, as for loggers.
- `reset handler X` / `reset handlers` land on the vendor level.
- **`AUTO`** as a vendor default (M3): the handler's *baseline* is AUTO tracking. With no operator
  override, `recomputeAuto` treats it exactly like an AUTO override (its level tracks the lowest
  active logger override, else its native level). An operator `set handler X <level>` overrides
  it; `reset handler X` returns it to AUTO tracking.

### Default handlers

`DefaultHandlerGroupRegistry` resolution order becomes: explicit membership set with `logctl set
default-handler` → the file's `defaultHandlers` → today's deterministic resolution. `logctl reset
default-handler` clears the explicit membership and so lands on the file's list.

### Rules

**Superseded in part by [`alter-rule.md`](alter-rule.md) (issue #96).** A vendor rule's baseline is its definition in the file;
`alter rule` puts an override on top, a plain `reset rule` returns to the file's definition (no
longer a refusal), and `reset … --to-native` replaces `--include-vendor-defaults` and suspension
(M4) as the way to switch one off until restart. The bullets below describe this slice as shipped.

- Each vendor rule is attached at install through `RuleService`, via the same factories as a live
  rule, with id `vendor:<id>`. It is never written to the state file: the file itself is its
  persistence. The state file's `r<N>` id sequence is unaffected (resume only advances past `r`
  ids already).
- `RuleView` gains `origin` (`"vendor-defaults"` or `null` for operator rules) and `suspended`.
  Vendor rules report no tier and no expiry.
- `reset rules` and `reset logger X` leave vendor rules attached and report them as skipped, the
  way sticky rules are reported without `--include-sticky`.
- `reset rule vendor:<id>` without `--include-vendor-defaults` is a refusal (non-zero exit,
  nothing changed), matching a single sticky target (`reset-command-surface.md`).
- With `--include-vendor-defaults` (on `reset rule`, `reset rules`, `reset logger`), a vendor rule
  is **suspended**: detached from the pipeline for the rest of this JVM's life, still listed and
  marked suspended (M4), back on the next restart. Audited as a reversion. There is no
  "unsuspend" command in this slice; restart restores it.
- `--include-sticky` and `--include-vendor-defaults` are independent flags.

### Reconfiguration and the verification sweep

Anything that re-asserts overrides today re-asserts the vendor layer first:

- The framework reset hook (`reapplyOnReset`): after baseline capture, re-apply vendor logger and
  handler levels for targets with no active override, then overrides as today.
- The verification sweep (§15.5): a vendor-defaulted logger or handler with no override whose
  configured level no longer matches the vendor level is re-applied, audited with source
  `verification-sweep`, same as for an override. The same tick retries unresolved vendor handlers.

## Surfaces

### `logctl list`

- `list loggers`: a new `VENDOR` column, the vendor level or blank; `CONFIGURED` stays the native
  level. The default view (overrides only) also includes loggers with a vendor default (epic
  #10); the empty-result messages say "override or vendor default". (M2)
- `list handlers`: the same `VENDOR` column (`AUTO` possible).
- `list rules`: vendor rules show `vendor-defaults` in the `TIER` column and `suspended` when
  suspended.
- `--json` gains the same fields.

### `logctl status`, `env`, `doctor`

- `status`: one line under the header (M5), e.g.
  `Vendor defaults: /opt/app/conf/vendor-defaults.yaml — 2 loggers, 2 handlers, default handlers, 2 rules`,
  or `… — REJECTED, see logctl doctor`. No line when no file is configured.
- `env`: the file's absolute path and load result (`loaded`, `rejected (N errors)`, or `not
  configured`). `EnvironmentReport` gains `vendorDefaultsPath` and `vendorDefaultsStatus`.
- `doctor` findings:
  - rejected file: one finding listing every error with its line number;
  - writable file or directory (warning);
  - a vendor handler that has never resolved since install (informational; M7).

### Audit

Source `vendor-defaults` for every entry applied at install, one record per entry
(`Action.MUTATION`, principal = the JVM's account). Rejection writes no mutation records; it's
reported through diagnostics and `doctor`. Suspensions and resets are audited as today, with their
usual sources.

### Capabilities

None checked at load (epic #11): the trust boundary is whoever controls the JVM command line and
the file. `reset … --include-vendor-defaults` needs the same capability the reset needs today.

### JMX / MXBean compatibility

Additive only (component versioning policy, `logaperture-spec.md` §11.1): new fields on
`LoggerInfo`, `HandlerInfo`, `RuleView`, `EnvironmentReport`, the reset outcome types; the new
`includeVendorDefaults` flag arrives as new overloads of the rule/logger reset operations, not a
changed signature.

## Failure handling

- Any parse/validation error: nothing from the file applies; `Diagnostics.warn` lists the errors;
  the JVM and every other LogAperture feature start normally.
- Applying a validated entry can still fail at runtime (an adapter throws): that entry is skipped
  with a diagnostic, the rest still apply. Validation is all-or-nothing; application is
  per-entry fail-open, like resume.

## Module scope

- `logaperture-agent`: `AgentArguments` parser; `premain`/`agentmain` pass `agentArgs`;
  `AgentBootstrap` loads `VendorDefaults` and passes it to `ContainerIntegration.activate`.
- `logaperture-core`: `VendorDefaults`, `VendorDefaultsFile` (parser + validation + writable
  check), `BaselineRegistry`/`HandlerBaselineRegistry` vendor layer, `LevelControlService`/
  `HandlerLevelControlService`/`RuleService`/`DefaultHandlerGroupRegistry` changes,
  `DoctorService`, `EnvironmentReportService`; `ContainerIntegration.activate` gains the
  parameter.
- `logaperture-container-none`, `logaperture-container-wildfly`: apply in `installContext` and in
  the reset hook.
- `logaperture-api`: additive fields listed above.
- `logaperture-control-jmx`: new reset overloads.
- `logaperture-cli`: `--include-vendor-defaults`; `VENDOR` columns; `status`/`env` lines; help.
- Top-level spec §6.6 already describes the layer (epic commit); `cli-transport.md`'s command
  reference gains the new flag and columns.

## Testing

**Unit:**

- `AgentArguments`: empty/absent args; one option; comma inside a path; `,--` separation;
  unknown/repeated/malformed options produce warnings, not failures; relative path resolution.
- `VendorDefaultsFile`: the example above parses; each validation rule rejects with the right line
  number; several errors reported together; `recipes:` message; protected-category and FATAL
  violations rejected via the real factories; writable file and writable directory each warn.
- `BaselineRegistry`/`HandlerBaselineRegistry`: `get` returns vendor over native; `nativeLevel`
  unchanged; native captured before vendor apply.
- Services on `FakeLoggingAdapter`: reset/expiry land on the vendor level; no-override reset is a
  no-op; persisted overrides resume on top of vendor levels; reconfiguration and verification
  sweep re-apply vendor levels; unresolved vendor handler applies once it resolves; vendor `AUTO`
  tracks and `reset handler` returns to tracking; `reset default-handler` lands on the file's
  list; vendor rules survive `reset rules`, refuse single reset, suspend with the flag and stay
  listed; ids never collide with `r<N>`.
- CLI: `VENDOR` columns, default-view inclusion, empty-result messages, `status`/`env` lines,
  `--include-vendor-defaults` parsing, JSON fields.

**Integration:**

- `none` container: a JVM started with `--vendor-defaults=` pointing at a test file; levels and a
  `drop` rule in effect at startup; `set` + `reset` round-trip lands on the vendor level; a
  rejected file leaves the JVM running with no vendor settings.
- `WildFlyContainerIT`: the same on a real server, including a named WildFly handler (`FILE`)
  and a `:reload` that re-applies vendor levels.

## Member decisions

Epic decisions are settled; these are the details the epic left to this spec.

- **M1 — Apply logger defaults eagerly at install.** Epic #7 said a default applies "when the
  logger appears". For loggers that's unnecessary: JUL and Logback accept a level for a logger
  that doesn't exist yet. Applying at install is simpler and closes the window before first use.
  Handlers still wait until they resolve. **Agreed.**
- **M2 — `list loggers` presentation.** A separate `VENDOR` column rather than folding the vendor
  level into `CONFIGURED`, so native, vendor and effective levels are all visible. **Agreed.**
- **M3 — Vendor `AUTO` is baseline AUTO tracking**, not a pre-applied AUTO override; `reset
  handler` returns to tracking. **Agreed.**
- **M4 — A suspended vendor rule stays listed, marked `suspended`**, until restart; no
  unsuspend command in this slice. **Agreed.** (Superseded by [`alter-rule.md`](alter-rule.md) (issue #96): `--to-native`,
  listed as `vendor-defaults (off)`, and a plain reset switches it back on.)
- **M5 — `status` gets one vendor-defaults summary line.** Epic #10 covered `list` only; `status`
  is where an operator looks first. **Agreed.**
- **M6 — Report every validation error, not only the first.** A vendor fixing a file shouldn't
  need one restart per typo. **Agreed.**
- **M7 — An unresolved vendor handler is informational in `doctor`**, not an error: WildFly
  resolves names late and a handler may be defined only in some deployments' configs. **Agreed.**
- **M8 — Agent-argument separator is `,` followed by `--`**, so paths with plain commas work
  without quoting. **Agreed.**
- **M9 — No `useParentRules` in the file.** Epic #3 didn't list it; a vendor who needs it asks and
  it becomes a follow-up. **Agreed.**

## Settled during implementation

Details the text above left open, decided while building it; none changes an agreed decision.

- **Group names are rejected.** `ALL_HANDLERS` and `DEFAULT_HANDLERS` are not accepted under
  `handlers:` or in `defaultHandlers` -- name each handler instead. A validation error like any
  other (all or nothing).
- **"No override covers it" includes group overrides.** The vendor layer acts on a handler only
  while neither its own override nor an `ALL_HANDLERS`/`DEFAULT_HANDLERS` override it belongs to
  is active; when that override is reset, the handler returns to its vendor level (or to AUTO
  tracking).
- **Vendor rules' internal tier is `SESSION`.** They are never written to the state file; `list
  rules` shows their origin (`vendor-defaults`) instead of a tier. A suspended rule keeps the hit
  count it had, so `list rules` still shows what it matched before being switched off.
- **The `VENDOR` column appears only when a row has a vendor value**, the same rule the
  `CONTEXT` column follows; output is unchanged for anyone not using a vendor defaults file.
- **`reset logger X` says where it landed**: `X → WARN (vendor default)` instead of
  `(baseline)` when the file names `X`.
- **`DEFAULT_HANDLERS`' catalog row** shows `(vendor: CONSOLE)` when the file's list is in
  effect, alongside the existing `(auto: …)` and explicit forms.
- **`doctor` severities**: rejected file `WARNING` (check `vendor-defaults.file`, every error in
  the detail); loaded file `OK`; writable file or directory `WARNING`
  (`vendor-defaults.writable`); unresolved handler `INFO` (`vendor-defaults.unresolved-handler`).
  Nothing at all when no file is configured.
- **`env` always shows a `Vendor defaults` row** (`not configured` when none), like `State file`.
  `status --json` gains a `vendorDefaults` object (`null` when none).
- **`--include-vendor-defaults` is a usage error** outside `reset rule`, `reset rules` and `reset
  logger`: vendor logger and handler levels need no flag, since they are simply where a reset lands.
- **Protected categories** are checked by the file validator itself (it takes the same
  `ProtectedCategories` a `RuleService` does), so a violation rejects the whole file rather than
  failing one rule at attach time.
- **WildFly integration test** drives drift with a `/subsystem=logging` management change rather
  than `:reload` -- the same verification-sweep path, without restarting the server under the rest
  of the suite. It runs in its own class and container (`WildFlyVendorDefaultsIT`), since a vendor
  file changes the baseline every test in the shared `WildFlyContainerIT` container assumes.
