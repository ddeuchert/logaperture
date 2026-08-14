# Feature 1 — Level Control

Status: draft, not yet implemented.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §5 (feature definition), §6.1
(persistence tiers), §9 (capability/audit model), §17 (roadmap).

## Scope of this slice

This spec covers the **first buildable slice** of Feature 1, per the roadmap's Layer 0 /
M1 (§17): enough level control to be genuinely useful, deliberately narrow on containers
and frameworks so the adapter/container SPI boundary (§4.6) gets exercised end-to-end
before it gets wide.

**In scope:**

- Container: `logaperture-container-none` (plain `java -jar`) only — "the baseline, built
  first" per §4.6.
- Framework: Logback only.
- Control surface: JMX only (§8.1 — "the reference implementation; everything else is a
  convenience over it").
- Persistence tier: `--session` only (§6.1). Overrides live until the JVM stops; no timer,
  no restart survival.
- The minimal capability slice needed to make mutation safe from day one: `view`,
  `level.raise`, `level.lower` (§9.3), sealed-at-boot per §9.4, since retrofitting
  authorization later "costs a redesign" per the roadmap's own reasoning (§17).
- A minimal audit trail (§9.7): every mutation and its later reversion is recorded.

**Explicitly out of scope for this slice** (deferred to later feature specs):

- WildFly, Spring Boot, Tomcat, Quarkus containers (§15).
- Log4j 2, JUL, Log4j 1 adapters.
- The `--for` and `--sticky` persistence tiers, the state file (§6.3), reconfiguration
  re-application (§6.5), and the identity problem (§6.4) — all of Feature 2.
- The squelch engine (Feature 3, §7) entirely.
- CLI-over-attach and any UI (§8.3, §8.4) — JMX is the only surface this slice needs;
  `logctl` comes later as a client of the same operations.
- `snapshot()` / `restore(snapshot)` — useful, but not required to prove the core loop.
- Signed rule packs, vendor/customer policy layering beyond a single local policy file.

## Operations

Per top-level §5, the four operations this slice implements:

```
listLoggers(filter) -> List<LoggerInfo>
setLevel(name, level, options) -> LevelOverride
resetLevel(name) -> void
resetAll() -> void
```

### `listLoggers(filter)`

Returns, per matching logger: name, configured level (the framework's own value,
captured at baseline), effective level (post-override, following hierarchy), whether an
override is active, and if so its source and `reason`.

`filter` is a name prefix or glob; `null`/empty returns everything discovered so far.
Only **Live** and **Known** states apply in this slice (§8.5) — inferred/class-scanning
discovery is a later enhancement (§8.6), out of scope here.

### `setLevel(name, level, options)`

`options`:

| Field | Type | Default | Notes |
|---|---|---|---|
| `includeChildren` | boolean | `false` | Mirrors Logback's own hierarchy semantics (§5) — does not change how the level applies, only whether it is also applied to loggers already known to be descendants at call time. |
| `reason` | string | `null` | Propagated to the audit log (§9.7). Not required by this slice's code, but every CLI/JMX caller in later slices should be encouraged to supply one. |

No `expiresIn` in this slice — everything is implicitly `--session`. The field is
reserved on the options type (§6.1) so Feature 2 is additive, not a breaking change.

### `resetLevel(name)`

Reverts one logger to its captured baseline value. No-op, not an error, if no override
is active.

### `resetAll()`

Reverts every active override to baseline. Used for the "get me back to normal" escape
hatch operators need before this tool is trusted with anything sharper.

## Semantics to pin down (per top-level §5)

- **Baseline capture.** On adapter install, walk the existing `LoggerContext` and record
  each known logger's configured level before any override is applied. Without this,
  `resetLevel`/`resetAll` are undefined, and there is no way to distinguish "the operator
  set INFO" from "the app was already INFO". A logger that does not exist yet at install
  time gets its baseline captured lazily, the first time it is either observed or named
  in a `setLevel` call — Logback accepts a level on a logger that hasn't been instantiated
  yet, and this slice must too (§8.5, "Known" state).
- **Hierarchy** follows Logback's own (a level on `com.acme` affects `com.acme.http`
  unless that logger has its own explicit level). No LogAperture-specific hierarchy logic
  — this slice is a thin control layer over `LoggerContext.getLogger().setLevel()`.
- **`reason`** is carried through to the audit record on every mutation, even though
  nothing in this slice enforces that it be non-null. Enforcing it is a policy-layer
  decision for later.
- **Idempotency.** Calling `setLevel` twice on the same logger updates the existing
  override (new value, new `reason`) rather than creating a second one — one override
  per logger name, always.
- **Override application must be re-appliable, not a one-time install.** The `none`
  container has no reconfiguration event to defend against in this slice — confirmed
  empirically, not just assumed: the M0 spike's point 1 (plain JVM + Logback) never
  observed one, because nothing in a bare `java -jar` process re-initializes Logback after
  startup the way a container's own logging system can (see
  [`doc/spikes/m0-adapter-grid.md`](../spikes/m0-adapter-grid.md)). So this slice does
  **not** need to wire up a reset listener. But `core`'s override tracking should still
  model "apply this `LevelOverride` to the current adapter" as an operation that's safe to
  invoke more than once — not folded into a single install step — so that a later
  container slice (Spring Boot, WildFly) can drive re-application from Logback's
  `LoggerContextListener` (§4.3, §15.5, §15.7) without redesigning the override model
  itself. This isn't hypothetical hardening: the M0 spike's point 3 found Spring Boot's
  `LoggingSystem` silently resetting the whole `LoggerContext` — installed levels
  included — shortly after boot, with no exception, exactly the scenario §15.7 already
  named. `none` is safe from it today; the data model shouldn't make it expensive to
  handle later.

## Data model

```
LoggerInfo {
    name: String
    configuredLevel: Level?      // baseline, null if never captured
    effectiveLevel: Level        // post-hierarchy, post-override
    overrideActive: boolean
    overrideSource: String?      // "jmx", later "cli", "http", ...
    overrideReason: String?
}

LevelOverride {
    loggerName: String
    level: Level
    includeChildren: boolean
    reason: String?
    appliedAt: Instant
    source: String
}
```

Lives in `logaperture-api` (§4.6) — the public rule/level model, usable standalone
without the agent. `Level` is LogAperture's own small enum (`TRACE`..`OFF`), not
Logback's, so `logaperture-api` stays framework-independent; the Logback adapter maps
between the two.

`LevelOverride` is pure state, deliberately — it describes an override, it isn't an
install action. The Logback adapter's job is to apply a `LevelOverride` to the current
`LoggerContext` reference on demand; `core` should be able to call that "apply" step
again for the same `LevelOverride` without caring whether it's the first time or a
re-application after the adapter detected a reset. This slice never exercises the
second case (see the re-appliability note above), but the split keeps it cheap to add
later instead of requiring a rework of this type.

## Module scope for this slice

Per the layout in top-level §4.6, only these modules are needed:

```
logaperture-bridge            bootstrap-visible interfaces, no deps
logaperture-api                LoggerInfo, LevelOverride, Level — this spec's data model
logaperture-core                baseline capture, override tracking, capability checks (§9.3 minimal set)
logaperture-agent               premain/agentmain, classloader plumbing, single-context only
logaperture-adapter-logback      LoggerContext binding, level read/write
logaperture-container-none       the baseline container: no server-specific discovery needed
logaperture-control-jmx          MBean surface exposing the four operations
```

`core` must not depend on `agent` (§4.6) — assert this in the build even at this small
scale, since it's the constraint that keeps the module boundary honest as adapters and
containers multiply later.

## Capability and audit (minimal slice of §9)

Full policy layering (§9.2), signed rule packs, and the four modes (§9.11) are out of
scope here — but the capability *check itself* is not, per the roadmap's own argument in
§17 that authorization retrofitted after the operations exist is "a capability check with
holes in it."

- Capabilities checked: `view` (for `listLoggers`), `level.raise` (target level below
  current effective level — i.e., more verbose), `level.lower` (target level at or above
  current effective level) (§9.3).
- Default policy for this slice: all three granted, local-only (no policy file yet) — the
  full `prod`-default posture (§9.11) is a later milestone's concern. This slice exists to
  prove the check exists at the right seam, not to ship a hardened default.
- **Audit record** per mutation and per reversion (`resetLevel`/`resetAll` count as
  reversions), per §9.7 fields: principal (JVM UID, since JMX auth is the JVM's own),
  source (`"jmx"`), logger name, previous value, new value, `reason`. Hash-chaining and
  external mirroring (syslog/Event Log) are deferred; the record shape should already
  match §9.7 so those are additive later.

## Failure handling

- Adapter install failure: log a diagnostic (§4.5), do nothing further, application
  starts normally regardless (§9 "ordinary failure handling").
- Any exception during a `setLevel`/`resetLevel` call is caught, logged to the agent's
  own diagnostic writer, and surfaces to the JMX caller as a failed operation — this is a
  control-plane call, not the logging hot path, so fail-open here means "don't crash the
  JVM," not "silently swallow the request."

## Testing

Per top-level §12: this slice is entirely adapter-behavior testing — in-process, against
a plain JVM with Logback on the classpath. No container integration tests are needed yet
since `none` has no discovery/lifecycle behavior beyond "the JVM that's already running."

Minimum coverage before this slice is done:

- Baseline capture matches Logback's actual configured levels, including loggers with no
  explicit level (inherited).
- `setLevel` / `resetLevel` / `resetAll` round-trip correctly, including on a logger name
  that does not exist yet.
- Hierarchy: `includeChildren` behaves identically to Logback's own child-level
  resolution.
- Capability checks deny appropriately when a capability is withheld (test both grant and
  deny paths even though the default policy grants everything).
- Audit record is written for every mutation and every reversion, with correct
  previous/new values.
- Chaos case: an exception thrown mid-operation leaves the JVM's logging in a defined
  state (either fully applied or fully not — no partial override).

## Exit criterion

`listLoggers`, `setLevel`, `resetLevel`, and `resetAll` work end-to-end over JMX against
a plain `java -jar` process running Logback, with baseline capture, hierarchy, capability
checks, and audit records all in place — matching the "read-only diagnostic release...
without approval anxiety" bar the roadmap sets for M1 (§17), minus the read-only
constraint, since mutation is this feature's entire point.
