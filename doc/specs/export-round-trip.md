# Restarting with a just-exported vendor defaults file (issue #107)

Status: **signed off 2026-09-26** (S1–S10 agreed as proposed; field name `stateId`); **implemented**
(see "Settled during implementation").
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §18.16 (roadmap write-up), §6.6
(configuration layers).
Builds on: [`vendor-defaults-export.md`](vendor-defaults-export.md) (#62, whose "Known limitation:
restarting with the exported file" this spec removes), [`vendor-defaults.md`](vendor-defaults.md)
(loading the file), [`persistence.md`](persistence.md) (the state file and resume),
[`alter-rule.md`](alter-rule.md) (vendor rule alterations, A9),
[`vendor-config-epic.md`](vendor-config-epic.md) (epic #12: sticky settings fold into the export).

## Functional summary

After this feature, the user will be able to:

- Tune an application with `sticky` settings, run `logctl export vendor-defaults --out <file>`,
  restart with `--vendor-defaults=<file>`, and get exactly the tuned state back: each setting
  active once, coming from the file.
- Edit that file afterwards and have the edit take effect on the next restart. A leftover sticky
  setting no longer hides it.
- See in `logctl list rules` one rule per exported rule, not the old `r7` next to its exported
  copy.
- Read at startup which sticky settings were taken over by the file.

## The problem

The vendor authoring loop is a round trip (§18.16): tune with `sticky` settings, export, restart
with the exported file. Today the state file still holds the sticky settings the file was made
from, so after the restart:

| Setting | What happens today |
|---|---|
| Operator rule `r7` (sticky) | Runs twice: as `r7` from the state file and as `vendor:drop-healthcheck` from the file (export X3). |
| Logger / handler override (sticky) | Keyed by name, so it isn't duplicated. But it equals the file's value and wins over it, so the next edit to that entry in the file has no effect. `list` shows it as an override. |
| `ALL_HANDLERS` / `DEFAULT_HANDLERS` override (sticky) | Same as a handler override, for every member. |
| Default handlers set with `set default-handler` | Always persisted, and wins over the file's `defaultHandlers`, which it was exported into. |
| Sticky alteration of a vendor rule | Exported under the vendor rule's id, and resumed on top of it as an alteration that equals the file. It hides later edits, like a logger override. |

The underlying question at restart is: *was this state entry folded into the file being loaded?*
The name can't answer it. A customer's own sticky `org.hibernate.SQL = WARN` over a vendor file
that also says `WARN` looks exactly like a vendor's leftover one, and the customer's must keep
winning (epic: "a customer's sticky override beats vendor v2"). Only a link recorded at export
time can tell them apart.

## Design

### An id for every persisted setting (S1, S2)

Every setting the state file holds gets an id: a random UUID. That covers
logger overrides, handler overrides (including the `ALL_HANDLERS` and `DEFAULT_HANDLERS` group
overrides), the explicit default-handler membership, operator rules, and vendor rule
alterations. The id is written to the state file with the entry. The operator never types or
sees it: `r<N>`, `vendor:<name>` and names stay the only handles.

**Lifetime (S3).** The state file assigns the id the first time a setting is written to it, and
keeps it for as long as the entry stays there: until a reset, an expiry, or a change to `session`
takes it out. Changing a setting in place keeps its id: `set logger X … sticky` on a logger that
already has a persisted override, `alter rule r7 …`, and `set default-handler` over an existing
membership. The next setting written after the entry was removed gets a new id.

A `session` setting is never written to the state file, so it has no id; promoting it with
`alter rule r3 sticky` (or a sticky `set` on the same target) gives it one. Only persisted
settings can be exported, so this makes no difference to what the export writes.

### The export writes the id into the file (S7)

Each exported entry whose value came from a setting in the state file gets a `stateId:` line
holding that setting's id:

```yaml
loggers:
  - name: org.hibernate.SQL
    level: WARN
    stateId: 3f0c9a4e-2b1d-4a7e-9c55-0d1e8b7f6a21

rules:
  # was r7
  - id: drop-healthcheck
    action: drop
    logger: com.acme.HealthCheck
    messageContains: "GET /health"
    below: WARN
    stateId: 9b2e41d0-77c3-4f0a-a1c8-5e6d2f9b3c47
```

- An entry that came straight from the file the JVM loaded, with no sticky setting over it, has
  no `stateId` (a `stateId` it had in that file is not carried forward: it has already been
  used).
- A group override expanded into one `handlers:` entry per member (export "What goes into the
  export") writes the group override's id on each of those entries. A member's own sticky
  override, which wins over the group's, writes its own id instead.
- `stateId` is optional. A hand-written file never needs it. At load it must be a well-formed
  UUID (otherwise the usual all-or-nothing rejection with a line number). The same id may appear
  more than once, which happens for group expansions.
- The vendor file format is unreleased (it landed after alpha-2), so the field is added under
  `schemaVersion: 1` with no version bump.

### Loading the file takes over the matching state entries (S4, S5)

At startup, the vendor file is applied and then the state file resumes, as today
(`vendor-defaults.md` "Install order"). The change comes first: once per JVM, right after the
state file is opened and before any logging context resumes from it, each state entry's id is
looked up among the file's `stateId`s.

- **Id found:** the entry is not resumed. It is removed from the state file, and the file's entry
  is what applies. The removal is permanent: the setting now lives in the file.
- **Id not found:** resumed exactly as today, with the usual precedence (a sticky override beats
  the vendor value).

This happens only when the file **loaded**. A rejected file, or no `--vendor-defaults`, takes
over nothing: the state file resumes in full, so a broken file never costs the operator their
settings.

**The file wins even if the setting changed after the export (S4).** Example: export, then
`alter rule r7 --below ERROR`, then restart with the older file. `r7` still has the id the file
carries, so the file's definition applies and `r7` is removed. The same holds for the opposite
order, which is the case this feature exists for: the vendor edits the file's entry, and the
file's value applies. The two cases can't be told apart without extra bookkeeping (see S4), and
treating "the file you restarted with" as the authority for everything exported into it gives
one rule that is easy to predict. When the state entry and the file entry differ, the startup
report says so and names the value that was dropped. The audit record keeps it, so it is never
lost.

A group override is taken over when the file carries its id on any entry, or in its
`handlerGroupStateIds` list (see "Settled during implementation"). Default-handler
membership is taken over when the file carries its id on `defaultHandlers` (written as a
`defaultHandlersStateId:` key beside the list, since a flow list has no room for a field).

Multiple contexts: overrides are broadcast and vendor entries apply to every context, so a match
takes over the entry everywhere. A rule's state entry belongs to one context (the export reads
the first one, X8). It is taken over wherever it was, and the vendor rule is attached in every
context as it is today.

### Reporting (S6)

Not silent. One audit record per entry taken over: `Action.REVERSION`, source `vendor-defaults`,
the old value, and a note that the setting now comes from the vendor defaults file. Plus one
startup diagnostic summarising them:

```
[logaperture-state] 4 sticky settings are now in the vendor defaults file and were removed from the state file: logger org.hibernate.SQL, handler FILE, default handlers, rule r7 (now vendor:drop-healthcheck)
```

and, for each entry that differed from the file:

```
[logaperture-state] WARN rule r7 (now vendor:drop-healthcheck) changed after it was exported; the vendor defaults file's version applies (dropped: drop on com.acme.HealthCheck --message-contains "GET /health" --below ERROR …)
```

`logctl status`, `list` and `doctor` don't change. After the restart, the settings show as vendor
defaults, which is accurate.

### State file (S9)

Every record in `overrides:`, `handlerOverrides:` and `rules:` gains a `stateId:` field, and
`defaultHandlerMembers:` gains a sibling `defaultHandlerMembersStateId:`. This is state schema
version 9.

- A record without an id (written by an older agent) is given one when the state file is
  opened, and the file is rewritten once. Such a record can never match a file, because no file could carry an id it
  never had.
- As with every earlier schema bump, an older agent refuses a version-9 file and starts with
  empty state (`persistence.md` "Failure handling"). This is acceptable before 1.0.

### Visibility (S10)

The id appears in the state file and the exported vendor file only. It is not part of `list`,
`--json`, the MXBean surface or the audit record's target: the operator has nothing to do with
it. No MXBean change, so no versioning concern (§11.1).

## Scope

**In scope:** ids on persisted settings; `stateId` in the export and in the file parser; taking
over matching state entries at load; the startup report and audit records; state schema 9; the
doc changes below.

**Out of scope:** stripping `stateId`s from a file before shipping it (S8); an equivalence-based
match for files written by hand (S1, "canonical signature"); changing what the export includes;
hot reload of the vendor file.

## Module scope

- `logaperture-api`: `LevelOverride`, `HandlerLevelOverride`, `PersistedRule` gain `stateId`
  (with a constructor of the old shape, `stateId` `null`, and `withStateId`).
- `logaperture-core`: `StateStore` documents the id contract and gains
  `defaultHandlerMembersStateId()`; `FileStateStore` assigns and keeps ids, and gives old entries
  one when it opens; `StateFileFormat` schema 9; `VendorDefaults` entry records and
  `VendorDefaultsExport` gain `stateId`/`defaultHandlersStateId`; `VendorDefaultsFile` parses,
  validates and writes them; the per-service `export…` methods read each sticky setting's id from
  the state store; `VendorStateTakeover` does the takeover, audit and startup report.
- `logaperture-container-none`, `logaperture-container-wildfly`: run `VendorStateTakeover` once,
  right after opening the state store (fail-open).
- No `logaperture-control-jmx` or `logaperture-cli` change.

**Doc changes, landing with the code:** remove the "Known limitation" section from
`vendor-defaults-export.md` and point to this spec; add `stateId` to the schema table in
`vendor-defaults.md`; mark §18.16 and the §17 pointer as specced/done; CHANGELOG entry.

## Testing

**Unit:**

- `StateFileFormat`/`FileStateStore`: `stateId` round-trips on every record kind; a version-8
  file reads with ids missing, and opening it assigns and persists them.
- `VendorDefaultsFile`: `stateId` and `defaultHandlersStateId` round-trip through write and parse;
  a malformed UUID is rejected with its line number; repeated ids are accepted.
- Id lifetime: saving over an existing entry keeps the id, for every kind; remove then save
  gets a new one.
- Exporter: entries from sticky settings carry their id; file-only entries carry none; a group
  expansion writes the group id and a member's own override writes its own.
- Resume, one test per row of "The problem" table: a matching entry is not resumed, is removed
  from the state file and is audited; a non-matching one (the customer case: same name, same
  value, different id) resumes and still wins; a changed-after-export entry is taken over and
  reported as differing; a rejected file takes over nothing.

**Integration:**

- `none` container: a new `LevelControlEndToEndIT` test next to
  `exportedVendorDefaults_reproduceTheTunedStateInAFreshJvm` does the real round trip. Restart the
  **same** instance (same state directory) with the exported file,
  then check that `list rules` has one rule (the vendor one), that `list loggers` shows the
  exported logger as a vendor default with no override, and that the state file no longer holds
  the exported entries. Then edit the file's level, restart again, and check the edit applies.
- WildFly: a new `WildFlyVendorDefaultsIT` test does the same for a sticky handler override,
  swapping the exported file in for the server's vendor file and restarting the server, then
  putting the original back.

## Decisions

- **S1: Match by an id recorded at export.** Alternatives:
  - *Canonical signature*: drop a state entry equivalent to a file entry. This can't tell a
    customer's own override that happens to equal the vendor value from a leftover, and it
    depends on the comparison keeping up with every rule option.
  - *Clean up at export*: demote exported settings to `session`. This makes the export a write
    (it needs `VIEW` today, X5). It also punishes a vendor who exports but doesn't restart with
    the file: their sticky settings vanish on the next restart.

  **Agreed: id.**
- **S2: Every persisted kind gets an id**, not only rules, even though loggers and handlers have
  a natural key. A name can't answer "was this folded into the file"; see the customer case.
  **Agreed.**
- **S3: The id lives as long as the setting**, and in-place changes keep it (`set` over an
  override, `alter rule`, promotion from `session`). **Agreed.**
- **S4: If the setting and the file differ, the file wins.** Taken over, reported, audited. The
  alternative is a revision counter next to the id, so that "the setting changed after export"
  (the setting wins) can be told apart from "the file was edited" (the file wins). That means
  more state for a case the workflow doesn't produce (you export after your last change). And
  when the setting wins for a rule, `r7` would have to turn into an alteration of
  `vendor:drop-healthcheck` to avoid the duplicate. **Agreed: the file wins.**
- **S5: A taken-over entry is removed from the state file for good**, and only when the file
  loaded. The alternative, shadowing (kept but not resumed while the file carries its id), would
  bring the setting back if you restarted without the file. But it leaves invisible entries in
  the state file that `reset` can't reach. **Agreed: remove.**
- **S6: Reported, not silent.** One audit record per entry (§9.7: changes to what's in effect
  are recorded), plus one startup summary, plus a line per entry that differed. The issue
  proposed silent. **Agreed: reported.**
- **S7: `stateId:` per entry, optional, under `schemaVersion: 1`**, plus
  `defaultHandlersStateId:` beside the list. Only entries sourced from state get one. The
  alternative is one top-level `supersedes: [ids]` list: less noise in the entries, but the link
  between an entry and its id is lost, and deleting an entry wouldn't delete its id.
  **Agreed: per entry.**
- **S8: No option to strip `stateId`s before shipping.** They are random and match nothing in a
  customer's state. A vendor can delete the lines by hand. Add an option only if someone asks.
  **Agreed.**
- **S9: State schema 9.** Old entries get an id at resume, and an older agent starts clean on a
  version-9 file, like earlier bumps. **Agreed.**
- **S10: The id is invisible outside the two files.** Not in `list`, `--json`, the MXBean or
  audit targets. **Agreed.**

## Settled during implementation

Details the text above left open, decided while building it; none changes an agreed decision.

- **The state store assigns the id**, not the services. Services exist once per logging context
  and all share one state file, so an id minted per service would differ between contexts; the
  store is the one place that sees every write. The export reads each sticky setting's id back
  from the store. So a `session` setting has no id until it is persisted (S3 text above).
- **The takeover is one pass before resume**, not a check inside each service's resume
  (`VendorStateTakeover`, run by the container right after the state store opens). It works on
  the state file alone, so it runs once however many contexts install later, and the startup
  report is a single block.
- **"Differs" compares behaviour, not text:** a logger's or handler's level (`AUTO` included), the
  default-handler member set (order ignored), and a rule's action, logger and definition as
  `list rules --verbose` renders it. A different reason alone doesn't count.
- **A group override differs** when any member entry carrying its id has a different level.
- **`handlerGroupStateIds`** (code review of PR #111). A member's more specific sticky setting
  replaces a group's id on that member's entry. When that happens on every member, the group's id
  never reached the file, and the group override stayed in the state file and won over the file
  after the restart: for example sticky `ALL_HANDLERS WARN` under a sticky `DEFAULT_HANDLERS DEBUG`
  that covers every handler came back at `WARN`. A group has no entry of its own, so the export
  also writes the id of each sticky group override it expanded (to at least one member) in a
  top-level list, and the takeover matches it there:

  ```yaml
  handlerGroupStateIds:
    - 5c1e8f02-6a4d-4b19-8e3a-2f7d90c4b1a6
  ```

  Optional, each item a UUID like any `stateId`. It is a separate list rather than a
  per-entry field because there is no group entry to hang it on.
- **Log prefix** is `[logaperture-state]`, the prefix every other state-file message already uses.
- **`defaultHandlersStateId` without a `defaultHandlers` list** is a validation error, like any
  other (all or nothing).
