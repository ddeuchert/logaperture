# Export live tuning as a vendor defaults file (slice 2: issue #62)

Status: **signed off 2026-09-25** (X1–X9 agreed); **implemented** (see "Settled during
implementation"). X4 was revised after #94
([`reset-to-native.md`](reset-to-native.md)); its rule half follows the rules redesign,
[`alter-rule.md`](alter-rule.md) A13 (#96, merged as PR #101).
Parent spec: [`vendor-config-epic.md`](vendor-config-epic.md) (signed off 2026-09-24; decisions
cited as "epic #N", chiefly epic #12), [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.12.
Builds on: [`vendor-defaults.md`](vendor-defaults.md) (slice 1, merged as PR #93 — the file
format this slice writes), [`persistence.md`](persistence.md) (the `sticky` tier),
[`rule-pipeline-foundation.md`](rule-pipeline-foundation.md), [`alter-rule.md`](alter-rule.md) (vendor
rule alterations), [`cli-transport.md`](cli-transport.md).

## Functional summary

After this feature, the user will be able to:

- Tune logging on a running application with the usual `logctl` commands, marking the settings
  they want to keep as `sticky`, then run `logctl export vendor-defaults` to get those settings as
  a vendor defaults file.
- Start from the vendor defaults file the application is already running with: the export
  contains that file's settings plus the sticky changes made on top of it, so the next version of
  the file is one command away.
- Write the result straight to a file with `--out <file>`, without accidentally overwriting an
  existing one (`--force` to overwrite).
- Load the exported file with `--vendor-defaults=` and get the same logging behaviour back.

## Scope

**In scope:** the `logctl export vendor-defaults [--out <file>] [--force]` command; what goes into
the export and how the vendor-file layer and sticky settings combine; generating valid,
re-loadable YAML in the slice 1 format; naming exported operator rules; the capability; tests,
including a round trip through the slice 1 parser.

**Out of scope:** exporting `session` or `for` settings (epic #12); merging into an existing file
on disk (X6); recipes (#92); exporting from a JVM that has no running agent (the command reads live
state, like every other `logctl` command).

## Command

```
logctl export vendor-defaults [--out <file>] [--force]
```

- Without `--out`: the file's text on stdout, nothing else, so `> vendor-defaults.yaml` works.
- With `--out <file>`: written to that path (resolved against `logctl`'s working directory), then
  one line: `Wrote 3 loggers, 1 handler, 2 rules to /opt/app/conf/vendor-defaults.yaml.` An
  existing file is refused (exit 1, nothing written) unless `--force` is given.
- `--force` without `--out` is a usage error; so is `--json` (X7).
- `export` is a new top-level verb with one noun for now, the same noun-first shape as `set`,
  `reset`, `list` and `add` (`cli-transport.md`).

## What goes into the export

The export is the vendor-file layer as it would be after the sticky changes were folded into it
(epic #12). Per kind of setting, where "file" means the vendor defaults file this JVM loaded (none,
if it wasn't started with one):

| Setting | Exported value |
|---|---|
| Logger | its `sticky` override if it has one; otherwise its level from the file, if the file names it and it isn't reset to native (X4). A `session`/`for` override is ignored — the file's value (if any) is exported instead. |
| Handler | its `sticky` override if it has one (a level, or `AUTO` for a sticky `AUTO` override); otherwise the file's entry, unless it's reset to native (X4). |
| `ALL_HANDLERS` / `DEFAULT_HANDLERS` sticky override | expanded: one `handlers:` entry per member handler, at the group's level — the file format has no group entries (slice 1, "Settled during implementation"). A member's own sticky override wins over the group's. |
| Default handlers | the membership set with `logctl set default-handler` if there is one (it is always persisted); otherwise the file's `defaultHandlers`, unless the list is reset to native (X4); otherwise omitted. |
| Vendor rule | its `sticky` alteration if it has one (`alter rule vendor:x … sticky`), under the file's id; otherwise the file's definition. A `session`/`for` alteration is ignored — the file's definition is exported instead (X2). A vendor rule switched off with `reset rule … --to-native` is left out (X4). |
| Operator rule | every `sticky` one; `session`/`for` rules are left out. |

- **Reasons** travel: a sticky override's or rule's `--reason`, or the file entry's `reason:`.
- **Exact names only.** Sticky overrides are already per exact logger name (a pattern `set` expands
  to one override per match), so no pattern ever reaches the file.
- **Order:** loggers and handlers sorted by name; rules in file order first, then operator rules
  in id order. Deterministic, so two exports of the same state are byte-identical and diff cleanly
  in version control.
- **Header:** comment lines naming the tool, time, agent version and the file it started from, e.g.
  `# Exported by logctl export vendor-defaults, 2026-09-25T14:02:11Z, agent 0.1.0-alpha.3`
  `# Started from: /opt/app/conf/vendor-defaults.yaml`. Comments are ignored when the file loads.

### Exported rule ids (X3)

A vendor rule keeps its name (`vendor:healthcheck-noise` exports as `id: healthcheck-noise`). An
operator rule has only an `r<N>` id, which is meaningless outside the JVM that minted it, so the
export derives a name: `<action>-<last segment of the logger, lowercased, non-[a-z0-9] → ->`,
e.g. `drop-healthcheck` for a drop rule on `com.acme.HealthCheck`, with `-2`, `-3`… appended on a
collision. A comment on the entry records where it came from (`# was r7`).

## Where the file is generated (X1)

In the agent. A new read-only MXBean operation, `exportVendorDefaults()`, returns the finished
YAML text; `logctl` only prints or writes it.

- The agent already holds everything the export needs, including facts the JMX read surface
  doesn't carry (a drop rule's `sampleFull` policy, a vendor logger's `reason`).
- The writer lives next to the slice 1 parser in `core` (`VendorDefaultsFile`), so one class owns
  the format in both directions and a round-trip test pins them together.
- Additive MXBean change (logaperture-spec.md §11.1): a new operation, nothing else changes.

Before returning, the agent parses its own output with the slice 1 validator. A failure there is
a bug, not a user error: the operation throws and nothing is written (fail closed — a vendor must
never be handed a file that won't load).

## Multi-context (X8)

Logger and handler overrides are broadcast, so every context holds the same set. Rules are per
context. The export reads the first context in stable-key order, the same scope `add rule` uses
today (`drop-rule.md` "Divergence from prior specs"). On a stock standalone WildFly there is only
the `system` context, so this changes nothing in practice.

## Capability

`VIEW` (X5). The export reads state; it changes nothing in the JVM. Writing `--out` happens in
`logctl`, under the account running it.

## Failure handling

- No agent / no context registered: the same errors every other `logctl` command gives.
- `--out` path exists without `--force`: exit 1, message names the path and `--force`.
- `--out` can't be written (directory missing, permissions): exit 1 with the I/O error; no partial
  file (write to a temp file in the same directory, then rename).
- Nothing to export (no file loaded, no sticky settings): still a valid file — `schemaVersion: 1`
  and the header — and a stderr note `Nothing to export: no vendor defaults file and no sticky
  settings.` Exit 0 (X9).

## Known limitation: restarting with the exported file

The export leaves the sticky settings it read in the state file. Restarting with
`--vendor-defaults=<the exported file>` therefore resumes them too: an operator rule `r7` runs
alongside its exported copy `vendor:<derived-name>` (X3), and a sticky logger/handler override
keeps winning over the identical file entry, hiding later edits to the file. Until this is fixed,
reset the exported sticky settings (`--include-sticky`) before restarting. Deferred to
[#107](https://github.com/ddeuchert/logaperture/issues/107); roadmap write-up and candidate fixes
in logaperture-spec.md §18.16.

## Module scope

- `logaperture-core`: `VendorDefaultsFile.write(...)` (the YAML writer, quoting as the parser
  expects); `VendorDefaultsExporter` building the exported set from `VendorDefaults`, the
  override/handler/default-handler registries and `RuleService`; `AggregateLevelControl`
  delegating to the first context.
- `logaperture-control-jmx`: `String exportVendorDefaults()` on `LevelControlMXBean`.
- `logaperture-cli`: the `export vendor-defaults` verb, `--out`/`--force`, help text.
- `cli-transport.md`: the new command in its reference.

## Testing

**Unit:**

- Writer: every field of every entry kind round-trips through `VendorDefaultsFile.parse`
  unchanged; values that need quoting (`#`, `: `, leading quote, `'` mid-word, backslash,
  newline in a reason) survive; output is byte-identical for equal input.
- Exporter: each row of the "What goes into the export" table, including `session`/`for`
  ignored, sticky beats file, group overrides expanded with member overrides winning, AUTO,
  default handlers from explicit vs file, entries reset to native left out, derived rule names
  and collisions, nothing-to-export.
- CLI: stdout mode; `--out` new file; `--out` existing refused; `--force`; `--force` without
  `--out` and `--json` rejected; temp-file-then-rename on failure.

**Integration:**

- `none` container: start with a vendor file, add a sticky override and a sticky drop rule, export,
  restart a second JVM with the exported file (and an empty state directory), and check the same
  levels and rule are in effect.
- WildFly: export from the existing `WildFlyVendorDefaultsIT` container after a sticky handler
  override, and check the result loads (parsed with the slice 1 validator).

## Settled during implementation

- **Operations surface.** The export is its own core interface, `VendorDefaultsExportOperations`,
  implemented by `AggregateLevelControl`, and `LevelControlMXBeanImpl`/`JmxRegistrar` take it as
  an eighth operations argument, rather than being folded into an unrelated interface.
- **Each service exports its own section:** `LevelControlService.exportLoggers`,
  `HandlerLevelControlService.exportHandlers`/`exportDefaultHandlers`, `RuleService.exportRules`
  (they own the overrides, baselines and reset-to-native state involved). `VendorDefaultsExporter`
  adds the header, renders with `VendorDefaultsFile.write`, and parses the result back.
- **Two sticky group overrides:** `ALL_HANDLERS` is expanded first, then `DEFAULT_HANDLERS` (the
  more specific group wins for a handler in both), then each handler's own sticky override.
- **Explicit default handlers** are written sorted by name; the vendor file's list keeps its order.
- **Derived rule names** are cut to the file's 40-character id limit before a `-2`/`-3` suffix; a
  rule on the root logger (empty name) gets `<action>-root`.
- **A rule with no level bound** (possible through JMX only, never `logctl`) is written without a
  `below:` line, so it reloads with the ERROR keep-floor -- the narrower, safer reading.
- **Settings the file can't hold are left out, not fatal** (code review of PR #102). Each entry is
  checked on its own before the file is assembled; one that wouldn't load -- a rule on the root
  logger (the file has no name for it), an empty matcher, a logger name with whitespace -- is left
  out with a `# Not exported: <what> -- the file can't hold it (<why>)` line after the header, and
  the rest is exported. Free-text reasons are tidied instead: a blank reason is left out, and a
  carriage return becomes a newline.
- **`--out` permissions:** the file is created with the user's umask, like any file they write (not
  the owner-only mode of a temp file), and an overwritten file keeps its own permissions -- the
  application's account must be able to read it.
- **Header, rejected file:** a JVM whose vendor file was rejected at startup exports `# Started
  from: <path> (rejected at startup, so none of it is included)`.
- **CLI:** `--out` is checked for an existing file before the agent is asked for anything. The
  "Nothing to export" note goes to stderr, so a `> file` redirect of stdout still gets just the
  file; `Command` gained an overload carrying stderr for this. The `Wrote …` line counts each
  section of the returned text, e.g. `Wrote 3 loggers, 1 handler, default handlers, 2 rules to …`.

## Implementation status

Landed together with this spec:

- `logaperture-core`: `VendorDefaultsFile.write`, `VendorDefaultsExport`, `VendorDefaultsExporter`,
  `VendorDefaultsExportOperations`; the per-service export methods above; `AggregateLevelControl
  .exportVendorDefaults`.
- `logaperture-control-jmx`: `LevelControlMXBean.exportVendorDefaults`.
- `logaperture-cli`: `export vendor-defaults`, `--out`, `--force`, help.
- Tests: `VendorDefaultsExportTest` (writer round trip including awkward text; every row of "What
  goes into the export"; empty export; `VIEW`), `ExportCommandsTest`,
  `LevelControlEndToEndIT.exportedVendorDefaults_reproduceTheTunedStateInAFreshJvm` (the `none`
  container round trip), `WildFlyVendorDefaultsIT.export_afterAStickyHandlerOverride_carriesTheFileAndTheOverride`.

## Member decisions

- **X1 — Generate the file in the agent**, via a new read-only MXBean operation returning the text,
  not in `logctl` from existing reads. The JMX read surface lacks facts the file needs, and one
  class should own the format both ways. **Agreed.**
- **X2 — A `session`/`for` override over a vendor value exports the vendor value**, not the
  override and not nothing — temporary tuning never leaks into the file, and the file's own
  setting isn't lost because someone happened to be debugging. **Agreed.** One exception, by
  design: a target reset with `--to-native` is left out (X4), though that reset lasts only until
  restart.
- **X3 — Operator rules get derived names** (`<action>-<logger's last segment>`, de-duplicated),
  with a `# was r7` comment. Vendor rules keep theirs. **Agreed.**
- **X4 — An entry reset to native is left out of the export** (revised 2026-09-25, after #94).
  `reset … --to-native` is how the vendor persona removes a logger, handler or default-handler
  list from the next file (spec §6.6, "Reset and `--to-native`"), so the export must honour it; no
  warning is given (the export is a sandbox task). Vendor rules follow the same shape since #96
  ([`alter-rule.md`](alter-rule.md) A13): a rule reset `--to-native` is left out, and a `sticky`
  alteration is exported under the file's id. **Agreed.**
- **X5 — Capability `VIEW`.** Read-only in the JVM. **Agreed.**
- **X6 — No merge into an existing file on disk.** The export already starts from the file the JVM
  loaded; merging with some other file is out of scope. `--out` refuses an existing path unless
  `--force`. **Agreed.**
- **X7 — No `--json`.** The output *is* a structured document; wrapping YAML in JSON helps no one.
  **Agreed.**
- **X8 — First context only**, matching `add rule`'s current scope; revisit with #79's multi-context
  fan-out. **Agreed.**
- **X9 — An empty export is valid, exit 0**, with a stderr note, rather than an error — a script
  exporting on a schedule shouldn't fail because nothing was tuned. **Agreed.**
