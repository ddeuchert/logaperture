# Storm detection on and off

Status: **signed off 2026-10-06, decisions T1–T15 all agreed.** Nothing is implemented yet.
Issue: [#151](https://github.com/ddeuchert/logaperture/issues/151). Must land by `1.0.0-beta.1`
(Oct 15), the feature freeze (§17.1).
Parent spec: [`doc/specs/storm-detection.md`](storm-detection.md), which this amends: storm
detection stops being always-on.
Also amends: [`doc/specs/overhead-benchmarks.md`](overhead-benchmarks.md) (what the `idle`
scenario contains, and where storm's budget miss sits under Decision #7),
[`doc/specs/vendor-defaults.md`](vendor-defaults.md) "Agent arguments" (a second argument),
[`doc/specs/quieter-output.md`](quieter-output.md) Q3 (the startup banner).
Related: [#150](https://github.com/ddeuchert/logaperture/issues/150) (storm filter still over
budget on concatenated messages), which this moves out of 1.0.

## Functional summary

After this feature, the user will be able to:

- Run an application with the agent and pay almost nothing for storm detection unless they ask
  for it: it starts off.
- Start a JVM with storm detection on, with `-javaagent:logaperture-agent.jar=--storm-detection=on`.
- Turn storm detection on or off in a running JVM with `logctl enable storms` and `logctl
  disable storms`, with a `--reason`, and see the change in the audit log.
- Turn it on or off for a while (`logctl disable storms for 30m`), after which it switches back
  by itself, or for good (`logctl enable storms sticky`), so it stays that
  way across restarts.
- See whether storm detection is on in `logctl storms`, `logctl status` and `logctl doctor`, and
  get a plain "storm detection is disabled" from `logctl storms` instead of an empty report.

## Why

Storm detection is report-only in 1.0; the active response it exists to support (collapse,
§7.1, [#27](https://github.com/ddeuchert/logaperture/issues/27)) is Release 2. Yet it is
installed on every real handler and runs on every record, and the published overhead run
(`doc/overhead.md`, 2026-10-06) measures it at +374 ± 14 ns on a concatenated message against a
308 ns budget, the largest part of the idle agent's +54 %. The remaining fix (#150) needs more
design work than fits before beta.1. An optional diagnostic should not be a cost every JVM pays
by default.

## Scope

**In scope:** one on/off switch for storm detection, its starting state, its runtime toggle, and
how every surface that shows storms reports it. Delivered in two slices on the same branch:

- **Slice 1:** the switch, the agent argument, `logctl enable|disable storms` for this run
  of the JVM, and the reporting.
- **Slice 2:** the `for <duration>` and `sticky` tiers, saved in the state file and resumed on
  restart ("Slice 2: timed and sticky toggles" below).

Slice 1 is implemented and green before slice 2 starts. If slice 2 can't make beta.1, slice 1
ships alone and slice 2 becomes a follow-up.

**Out of scope:**

- Setting it from a vendor defaults file. Possible later as an additive key; not needed now.
- A per-context switch. One switch covers every context (T4).
- Making `top`'s always-on byte counting switchable. It is within budget.
- Any change to detection itself, fingerprints, thresholds or bounded state.

## Behaviour

### Starting state

Storm detection starts **off** (T1). The agent argument `--storm-detection=on` starts it on
(T2):

```
-javaagent:/opt/app/lib/logaperture-agent.jar=--storm-detection=on,--vendor-defaults=/opt/app/conf/vendor-defaults.yaml
```

- Values are `on` and `off`, case-insensitive. `off` is accepted so a launch script can state it
  explicitly.
- Anything else, a missing value, or the argument given twice is a startup warning and is
  ignored, the same fail-open rule as every agent argument (vendor-defaults.md "Agent
  arguments"). A repeated argument keeps the first value.
- The startup banner (quieter-output.md Q3) gains `storm detection on` when it starts on, and
  says nothing when it starts off.

### Turning it on or off at runtime

`logctl enable|disable storms [session | for <duration> | sticky] [--reason <text>]` (T10).
Storm detection is a switch with two positions; neither is a default to go back to. The verb
names the position, and the command sets it whatever the current one is, so running it twice
is the same as running it once. There is no `reset` for it (T13). `enable` and `disable` are new
top-level verbs so later switches can join them (`logctl enable <switch>`).

```
$ logctl enable storms --reason "INC-4411 batch retries"
Storm detection is now enabled (was disabled). Measuring from 2026-10-07T14:05:00Z.

$ logctl disable storms --reason "INC-4411 closed"
Storm detection is now disabled (was enabled). Storms tracked so far are kept as of 2026-10-07T16:40:12Z.

$ logctl enable storms
Storm detection is already enabled. Nothing changed.
```

- This section is slice 1: the `session` tier, which is also the default when no tier is
  given. `for` and `sticky` are slice 2.
- Exit 0 in all three cases. Setting the position and tier it already has changes nothing and
  writes no audit record.
- `--reason` is optional, as on other mutations. `--json` returns `{"enabled": bool, "previous":
  bool, "changedAt": "..."}`.
- The change takes effect at once, in every context (T4). A plain `enable` or `disable` (the
  `session` tier) **never switches back by itself** while the JVM runs; only `for <duration>`
  does (T12). It lasts until the next `enable`/`disable` or until the JVM stops, and a restart
  starts from the agent argument again (T3), the same rule as `set logger` without a tier. So on
  a JVM started with `--storm-detection=on`, a plain `disable storms` is enabled again after a
  restart; `disable storms sticky` keeps it disabled.
- Turning it on requires a new capability, `diagnostics` (T7). Turning it off requires the same.
  `logctl storms` with no argument still needs only `view`.
- One audit record per change (T8): target `storm-detection`, previous and new value `on`/`off`,
  the reason, and the principal, through the existing audit sink.

### What happens to storm data (T6)

- **Turning it off** freezes what is tracked. Nothing is added or removed, `ONGOING` storms stay
  `ONGOING` (the detector can't know whether they have stopped), and the report says the figures
  are as of the moment it was turned off.
- **Turning it on** clears everything tracked and starts a new measurement window
  (`measurementStartedAt` = now). Counts from before a gap of unknown length can't be combined
  with counts after it.
- Turning it on for the first time is the same as any other "on": a new, empty window.

### How it is reported (T9)

**`logctl storms`**, off and never turned on:

```
$ logctl storms
Storm detection is disabled. Enable it with `logctl enable storms`, or start the agent with --storm-detection=on.
```

Off after being on, with storms tracked: the same report as today, with a first line instead of
the empty-report message:

```
Storm detection is disabled since 2026-10-07T16:40:12Z. Figures below are as of then.

[ONGOING]  com.acme.batch.Worker  org.acme.SlotException  "no capacity"
           ...
```

On: unchanged from storm-detection.md.

`--json` gains two fields: `detectionEnabled` (boolean) and `detectionChangedAt` (the last
runtime change, or `null` if it is still in its starting state).

**`logctl status`** gains one line under the vendor-defaults line: `Storm detection: enabled` or
`Storm detection: disabled`, with `(since <time>)` after a runtime change. `status --json` gains a
`stormDetection` object with the same two fields.

**`logctl doctor`**: the existing "N log storms are currently ongoing" pointer is printed only
when detection is on. When it is off, doctor prints "Storm detection is disabled — see
`logctl enable storms`." instead. This is an informational line, not a finding: off is the
default, not a problem.

**Older agents** (alpha.3 and earlier): `logctl enable|disable storms` fails with "this agent
does not support enabling or disabling storm detection; it is always on". The report from an
older agent has no `detectionEnabled` field, and `logctl` shows it as on, which is what it was.

## Design

**One switch per agent (T4).** `core` holds a single storm-detection switch per agent: the
starting state from the agent argument, the current state, and when it last changed. Every
context's `StormService` is given the same switch, so a context installed later (a WildFly
deployment, a reconfiguration) follows the current state. The aggregated MXBean reads and sets
it once, not once per context.

**Installed as today, inert when off (T5).** Nothing about installation changes: the storm
filter goes on every real handler at context install and the sweep keeps it there, exactly as
now, whatever the switch says. Its first step on every event is to read the switch; when the
switch is off it returns the delegate's verdict at once and the detector does no work. Two states,
one code path: no install or uninstall path depends on the switch, so FilterLayering and #145's
fix are untouched. The cost when off is one volatile read and one filter call.

**JMX (additive).** `LevelControlMXBean` gains:

```
StormDetectionData stormDetection();                                        // VIEW
StormDetectionData setStormDetection(boolean enabled, String reason);       // DIAGNOSTICS
```

`StormDetectionData` carries `enabled`, `previous` (for the setter), `changedAt` (nullable) and
`startedEnabled`. `StormReportData` gains `detectionEnabled` and `detectionChangedAt`. All
additions, per the §11.1 MXBean policy.

**Agent argument.** `AgentArguments` gains `--storm-detection` beside `--vendor-defaults`, parsed
by the same rules. The `agentmain` (attach) path reads it the same way.

## Slice 2: timed and sticky toggles

`logctl enable|disable storms` takes the same tier words as `set logger`: `session` (the default),
`for <duration>`, and `sticky`.

```
$ logctl enable storms for 30m --reason "watch the 02:00 batch"
Storm detection is now enabled (was disabled) until 2026-10-08T02:30:00Z, then disabled. Measuring from 2026-10-08T02:00:00Z.

$ logctl enable storms sticky --reason "noisy integration, keep watching"
Storm detection is now enabled (was enabled, until 02:30). Stays enabled across restarts.
```

### Semantics

- **`for <duration>`** sets the position now and **switches to the other position** when the
  time is up (T12). `enable storms for 30m` is enabled now and disabled in 30 minutes,
  whatever the position was before; `disable storms for 30m` is the reverse. The flip comes
  from the existing 30 s expiry sweep, so it lands within about 30 s of the requested time.
  The position after the flip is a `session` setting.
- **`sticky`** sets the position and keeps it across restarts until another toggle replaces it.
- **Every toggle replaces the current setting**, tier included (T13). A `session` toggle after a
  sticky one removes the saved entry, so the next restart starts from the agent argument. There
  is no `reset`: to undo a setting, toggle to the position you want.
- **A saved setting wins over the agent argument** at restart (T14), the way a sticky logger
  override wins over the logging config. The startup banner shows it: `storm detection on
  (sticky)` or `storm detection on until <time>`.
- `for` and `sticky` need the `persist` capability as well as `diagnostics`, the same rule as
  every other tier (persistence.md "Capability and audit").
- No-op rule: a toggle is a no-op only when position and tier both match an existing `session` or
  `sticky` setting. A `for` toggle always sets a new deadline, so it always writes.

### Restart

Resume (persistence.md "Resume on restart") reads the saved storm setting with the rest of the
state file, before any context observes the switch:

- `sticky`: applied, with an audit record, `source = resume`.
- `for`, deadline still ahead: applied, and the sweep flips it at the original deadline.
- `for`, deadline passed while the JVM was down: not applied. A flip audit record is written
  (`source = resume`, reason noting it expired while stopped), the entry is removed, and the
  switch starts from the agent argument. That matches the flip's own rule, since the position
  after a flip is a `session` setting and doesn't survive a restart.

### State file

One optional top-level entry, written only when a `for` or `sticky` setting exists:

```yaml
stormDetection:
  enabled: true
  tier: FOR
  expiresAt: 2026-10-08T02:30:00Z
  reason: "watch the 02:00 batch"
  appliedAt: 2026-10-08T02:00:00Z
  source: jmx
```

Schema version 12. A version-11 file loads unchanged (no entry means no saved setting). The
store's interface gains `loadStormDetection`, `saveStormDetection` and `removeStormDetection`,
with no-op defaults like the other entry kinds.

### Surfaces

- `status` shows the tier: `Storm detection: enabled (until 02:30, then disabled)` or
  `enabled (sticky)`; the `--json` object gains `tier` and `expiresAt`.
- The `storms` report's first line shows the same when a tier other than `session` is in effect.
- The JMX setter becomes `setStormDetection(boolean enabled, String reason, String tier, long
  forSeconds)`, the same tier parameters `setHandlerAuto` takes. Slice 1 ships the two-argument
  form; slice 2 adds the four-argument one. Both are additions.
- `export vendor-defaults` does not carry the storm setting (T15): the vendor defaults file has no
  key for it. The export's header comment says so when a `sticky` storm setting exists.

### Testing (slice 2)

- State file: round trip of each tier; a version-11 file loads; schema 12 written.
- Resume: sticky applied; `for` with time left applied and flipped on schedule; `for` expired
  while stopped writes the flip record, removes the entry and starts from the agent argument;
  a saved setting beats `--storm-detection`.
- Sweep: `enable … for` flips to disabled and `disable … for` flips to enabled, with
  `source = expiry-sweep`; a new setting before the deadline cancels the flip.
- Replacement: `session` after `sticky` removes the saved entry.
- Capability: `for`/`sticky` refused without `persist`.
- CLI: tier parsing, the output above, `status` and `storms` lines.

## Changes to other specs

- **storm-detection.md:** the gate-stage observer is still always installed, but does nothing
  while storm detection is off; "Reconfiguration and lifecycle" and
  `measurementStartedAt` point here for what a toggle does. A header amendment line links this
  spec.
- **overhead-benchmarks.md:** `idle` now means the agent's default, with the storm filter
  installed but off.
  The `rule+storm` layer keeps its budget and stays reported, but a storm-layer miss is no longer
  a 1.0 blocker under Decision #7, which covers the idle agent; it is tracked by #150 (1.1.0).
  The storm-off layer (`rule+storm` with the switch off, minus `rule`) must be within 10 ns.
- **vendor-defaults.md "Agent arguments":** "This slice defines one" becomes a list of two.
- **quieter-output.md Q3:** the banner's `storm detection on` part.
- **logaperture-spec.md:** §7.1 notes that detection is opt-in in 1.0; §9.3 gains the
  `diagnostics` capability; §17.1 records the change and #150's move to 1.1.0.
- **persistence.md:** the state file's storm entry and schema 12 (slice 2), "Resume on restart"
  and "Expiry enforcement" gain the storm setting, and the store's interface list gains its three
  methods.
- **CHANGELOG:** a behaviour change from alpha.3, where storm detection was always on.

## Testing

- `AgentArguments`: `on`, `off`, mixed case, a bad value, a missing value, repeated; alongside
  `--vendor-defaults` in either order.
- `core`: starts off, and the detector records nothing while off; "on" makes every context
  record;
  "on" again clears and restarts the window; a context installed while on gets the filter; a
  no-op toggle writes no audit record; a denied `diagnostics` capability refuses the toggle.
- JUL adapter: the chain is `Rule -> Storm -> base` in both states and stays that shape across
  off/on and sweep ticks (#145's test, with the switch in play); off returns the delegate's verdict.
- CLI: `enable|disable storms` parsing and output; `storms`, `status` and `doctor` in each
  state, text and `--json`; the older-agent message.
- WildFlyContainerIT: start with `--storm-detection=on` and see a storm reported; start without it
  and see "disabled"; enable it at runtime and see one.
- Bench: the `storm-off` scenario.

## Decisions (signed off 2026-10-06)

All fifteen agreed as recommended, after revisions to T3, T5 and T10 during review. Numbering is
stable and matches the review artifact.

| # | Decision | Agreed |
|---|---|---|
| T1 | Default state in 1.0 | **Off.** It's an optional, report-only diagnostic, and off removes its cost from the idle agent. |
| T2 | How the starting state is set | **Agent argument `--storm-detection=on\|off`**, not a system property: one way to configure the agent, through the existing parser. |
| T3 | Does a runtime toggle survive a restart? | **Only when asked.** `session` (the default) doesn't; `sticky` and `for` do (slice 2), through the existing state file, resume and expiry sweep. |
| T4 | Scope of the switch | **One switch for the whole agent**, covering every context, including ones installed later. |
| T5 | Mechanism | **Two states, one code path: the filter is always installed, as today, and checks the switch first.** Off costs one volatile read and one filter call; nothing about installation depends on the switch. |
| T6 | Storm data on a toggle | **Off freezes, on clears and restarts the window.** |
| T7 | Capability for the toggle | **A new `diagnostics` capability**, for turning a diagnostic instrument on or off. Granted by default like every capability today (`allowAll`). |
| T8 | Audit | **One record per actual change**, target `storm-detection`; none for a no-op. A timed flip and a resume are audited too (`source` `expiry-sweep` / `resume`). |
| T9 | Reporting | **`storms`, `status` and `doctor` all show the state**; `doctor` as an informational line, not a finding. |
| T10 | `logctl` command shape | **`logctl enable\|disable storms [session \| for <duration> \| sticky]`**: new `enable`/`disable` verbs that name the position, so the command is idempotent and needs no `on`/`off` argument; later switches join as `logctl enable <switch>`. (Agreed with David 2026-10-06, after `logctl storms on\|off` and `logctl toggle storms on\|off`; "toggle" usually means flip.) |
| T11 | #150 | **Moves to 1.1.0**, with the active storm-response work; storm's budget miss stops blocking 1.0. |
| T12 | What `for <duration>` does at the deadline | **Switches to the other position**, whatever the position was before the toggle; the position after the flip is a `session` setting. (David, 2026-10-06.) |
| T13 | Is there a `reset`? | **No.** A switch has no natural default to go back to. Every toggle replaces the current setting and tier; to undo, toggle to the position you want. (David, 2026-10-06.) |
| T14 | Saved setting vs the agent argument at restart | **The saved setting wins**, as a sticky logger override beats the logging config. With no saved setting, the agent argument decides. |
| T15 | `export vendor-defaults` | **Doesn't carry it**; the vendor defaults file has no storm key. The export's header comment says so. A vendor-file key can come later as an addition. |
