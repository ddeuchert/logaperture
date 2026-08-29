# WildFly Support — JBoss LogManager backend, multi-context core

Status: Slices 1 & 2 merged. Slice 3 implemented; its real-WildFly Testcontainers IT is
written and gated (self-skips without Docker) but has not yet had a shakeout run against a
real Docker daemon.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.3 (per-framework
mechanism map), §4.4 (classloader model), §4.6 (module layout), §15 (containers) — §15.1
(the two orthogonal axes), §15.2 (the `ContainerIntegration` SPI), §15.4 (one JVM, several
frameworks), §15.5 (assume the hooks will be discarded), §15.6 (WildFly), §15.10 (never
mutate the container's configuration), §17 (roadmap).
Builds on: [`doc/specs/level-control.md`](level-control.md) (Feature 1),
[`doc/specs/persistence.md`](persistence.md) (Feature 2), and
[`doc/spikes/m0-adapter-grid.md`](../spikes/m0-adapter-grid.md) point 2 (WildFly + JBoss
LogManager), which already answered "does this work at all" — this spec is how it gets
built for real.

## Functional summary

After this feature, the user will be able to:

- Attach the agent to a **standalone WildFly** server (a line in `standalone.conf`) and
  control its logging the same way they already can for a plain `java -jar` app —
  `logctl levels`, `logctl debug <logger> for 30m`, `logctl status`, `logctl reset`.
- Raise a WildFly-internal logger (`org.jboss.as.server`, `org.hibernate.SQL`, …) to see
  boot and subsystem detail that the server's configured level normally hides, and have
  it revert on a timer without ever editing `standalone.xml`.
- Control loggers **belonging to a deployed application** — their own WAR/EAR's loggers —
  not just the server's. A level override is applied across every logging context the
  server has (the common case is a single shared context); scoping an override to one
  named deployment is a possible later addition, not part of this release.
- Redeploy the application repeatedly and keep a `--sticky` (or still-live `--for`)
  override alive: it re-applies itself each time a deployment's logging context comes
  back, without being re-issued.
- See, when a logger and its output sink disagree, a warning that the level was set but a
  handler floor (a `CONSOLE` handler fixed at `INFO`, say) will still swallow it — rather
  than a silently ineffective change.
- Trust that none of this writes to `standalone.xml`, `logging.properties`, or any file
  the server or the application owns — the overrides live only in the agent's own store
  and expire on their own.

## Delivered in three slices

One spec, three PRs. The first is an enabling refactor with no new user-facing behaviour;
it exists because the multi-context and SPI changes it makes are the foundation the other
two stand on, and doing them in isolation against the fast `none` test path is far safer
than doing them tangled up with a new adapter and a real app server.

| Slice | Module(s) touched | What it delivers | Depends on |
|---|---|---|---|
| **1 — Container SPI + multi-context core** | `logaperture-core`, `logaperture-container-none`, `logaperture-agent`, `logaperture-api`, `logaperture-control-jmx` | The `ContainerIntegration` SPI (§15.2); `core` broadcasts level control across every logging context instead of assuming one; `none` re-expressed as a `ContainerIntegration`; agent bootstrap becomes detect-then-install. No new capability, no changed signature — `none` behaves exactly as today. | Features 1, 2 |
| **2 — JBoss LogManager adapter** | new `logaperture-adapter-jboss-logmanager` | A `LoggingAdapter` over `org.jboss.logmanager` / `java.util.logging`: per-`LogContext` level read/write, baseline capture, the LogAperture↔JUL level mapping, and detection of the handler-floor trap. Tested in-process against JBoss LogManager on the classpath — no WildFly. | Slice 1 |
| **3 — WildFly container integration** | new `logaperture-container-wildfly` + `logaperture-it`, `logaperture-agent`, `logaperture-core`, `logaperture-cli` | The WildFly `ContainerIntegration`: premain-safe detection, binding the system `LogContext`, the verification sweep (`LevelControlService.verifyAndReapply` + a `LogManager` config-change hook), the display-only CONTEXT column, and a gated real-WildFly Testcontainers IT. | Slices 1, 2 |

The rest of this document specifies Slice 1 at implementation depth; Slices 2 and 3 at
design depth — enough to start, with the decisions that constrain Slice 1 pinned now so it
doesn't have to be reworked.

---

# Slice 1 — Container SPI and multi-context core

## Scope

**In scope:**

- A `ContainerIntegration` SPI in `logaperture-core.spi`, matching §15.2, plus a
  `ContextHandle` type it produces.
- `LevelControlService` stays single-context and untouched; a new aggregator over N of
  them becomes the thing the control surfaces talk to.
- `LoggerInfo` gains a read-only `context` field (the owning context's id). No change to
  `SetLevelOptions`, to persisted `LevelOverride`s, or to the state file format.
- Broadcast semantics for `setLevel` / `resetLevel`: with no scoping (there is none in
  this release) an override applies to the named logger in every registered context.
- `logaperture-container-none` re-expressed as a `NoneContainerIntegration` — one context,
  no lifecycle events — proving the SPI's optional methods really are optional.
- `logaperture-agent` bootstrap: iterate available `ContainerIntegration`s, pick the first
  whose `detect()` is true (`none` is the always-true fallback, tried last), hand off to
  it. The Logback readiness poll (`LogbackLoadDetector`) moves behind the `none`
  integration.
- JMX surface: `LoggerInfoData` gains a `context` field. `LevelControlMXBean.setLevel` is
  **unchanged** — no new parameter.

**Explicitly out of scope for Slice 1:**

- Any real second container or adapter — Slices 2 and 3.
- Scoping an override to a named context (`MyApp1.war:org.acme.Foo`) — a possible later
  addition (see "Broadcast semantics" below). Nothing in this release resolves, selects,
  or persists a per-override context, so `SetLevelOptions`, the MXBean signature, and the
  state schema stay exactly as Feature 2 left them.
- The CONTEXT column in `logctl levels` / `status` output — Slice 3, and display-only
  (shown only when more than one context exists). Slice 1 carries the
  `LoggerInfo.context` field the column will read; it renders nothing.
- The reconfiguration **event** wiring for containers other than what already exists
  (Logback's own reset, via `none`). The periodic verification sweep (§15.5) that Slice 3
  needs is designed here but only `none`'s trivial case is exercised.
- `top`, `doctor`, storm detection — still deferred (see "Roadmap impact").

## The `ContainerIntegration` SPI

Lives in `org.logaperture.core.spi`, alongside `LoggingAdapter` and `StateStore`. §15.2
sketched three methods — `discoverContexts` plus `onContextAdded` / `onContextRemoved`
callbacks — on the assumption that a generic agent-side composition root would drive the
per-context install and the aggregate would be wired to the integration's lifecycle
events. Implementation moved that the other way: **each integration owns its own
composition root** (the spec's own "the composition root, per container"), because the
per-context wiring for `none` legitimately uses `logaperture-bridge` (`Diagnostics`) and
class-load-time instrumentation, neither of which belongs in `core`. So the SPI collapses
to one `activate` call that hands back the finished surface; the "a context appeared / went
away" events are internal to each integration and expressed against `AggregateLevelControl`
(`addContext` / `removeContext`), not on this interface.

```java
public interface ContainerIntegration {

    /** Stable id, e.g. "none", "wildfly". Used in diagnostics and, later, doctor output. */
    String id();

    /**
     * Is this container present? Resource- and class-presence probing only —
     * never speculative class loading (§15.2, §15.6's premain gotcha). Cheap
     * and side-effect-free: called on every registered integration at startup.
     */
    boolean detect();

    /**
     * Bring this container's logging under control. Returns promptly with the
     * (initially empty) AggregateLevelControl; discovery and the per-context
     * install complete asynchronously, once the backend is safe to touch. For
     * `none` that is "wait for SLF4J to bind to Logback, then install the one
     * system context"; for WildFly, "wait for org.jboss.logmanager.LogManager
     * to be installed, then install one context per registered LogContext, and
     * keep reconciling that set". `onFirstContextReady` is handed the aggregate
     * once the first context is installed — the agent uses it to register the
     * JMX surface and publish its discovery marker only when there is really
     * something to control. Passing the aggregate (rather than a bare
     * Runnable) keeps the agent from having to race `activate`'s return value
     * against the async install.
     */
    AggregateLevelControl activate(
            Instrumentation inst, CapabilityPolicy policy, AuditLog auditLog,
            Consumer<AggregateLevelControl> onFirstContextReady);

    /** Where the -javaagent flag goes, for diagnostics and help. Default InstallGuidance.NONE. */
    default InstallGuidance guidance() { return InstallGuidance.NONE; }
}
```

`ContextHandle` carries what `core` needs to run level control against one context and to
identify that context stably across a redeploy:

```java
public interface ContextHandle {

    /** stableKey for the "system" context — the server/app itself. */
    String SYSTEM = "system";

    /**
     * Stable identity for this context (§15.2's stableKey). Survives
     * classloader replacement: SYSTEM for the server/app itself, the
     * deployment name ("myapp.war") for a deployment context. Used to
     * recognise a context across a redeploy and to label rows in output.
     * This release does not key persisted overrides on it — overrides are
     * broadcast, not scoped.
     */
    String stableKey();

    /** Human-readable, for `logctl levels`' CONTEXT column. Often the same as stableKey(). */
    String displayName();

    /** The bound adapter for this context. One LoggingAdapter instance per context (§15.4). */
    LoggingAdapter adapter();

    /** A trivial immutable handle. */
    static ContextHandle of(String stableKey, String displayName, LoggingAdapter adapter) { … }
}
```

`stableKey` still earns its place (§15.2): on a redeploy the aggregate uses it to
recognise that the freshly-registered `LogContext` is "the same" context as the one that
went away, so it can re-broadcast the still-active overrides onto it. It is not, in this
release, a persistence key — see "Broadcast semantics".

`InstallGuidance` is a two-field carrier (`String summary`, `List<String> steps`) with a
`NONE` constant. Slice 1 defines it and has `none` return `NONE`; nothing renders it yet.

## Multi-context aggregation in `core`

`LevelControlService` today holds one `LoggingAdapter`, one `BaselineRegistry`, one
`OverrideRegistry`, and implements `LevelControlOperations`. That code is well-tested and
correct for one context; Slice 1 does **not** rewrite it. Instead:

- **`LevelControlService` stays single-context**, one per `ContextHandle`. Its constructor
  and every existing method are untouched; it gains three small additive methods for the
  broadcast path — `activeOverrides()` (a snapshot of what it currently tracks),
  `adoptOverride(LevelOverride)` (apply an override another context already holds, as a
  `"resume"`-flavoured mutation: adapter + registry + audit, no capability check, no
  state-store write since the originating context already persisted it), and
  `checkSetLevelPermitted(name, level, opts)` (the capability pre-flight `setLevel` already
  runs, exposed so the aggregate can run it against every context before mutating any).
- **A new `AggregateLevelControl implements LevelControlOperations`** holds a live
  `Map<String stableKey, ContextControl>` where `ContextControl` is a `record(ContextHandle
  handle, LevelControlService service)`. This is what `JmxRegistrar.register` receives now,
  instead of a bare `LevelControlService`. It also owns `addContext` / `removeContext`
  (the lifecycle events §15.2 put on the SPI) and a `sweepExpiredOverrides(Instant)` that
  fans the expiry sweep across every context.
- Each container's composition root builds one `LevelControlService` per discovered
  context and registers it with the aggregate (`register` for a context found during
  initial discovery, `addContext` for one that appears later — the latter also
  re-broadcasts the still-live overrides onto it, skipping any `--for` whose deadline has
  already passed but the sweep has not yet reached); `removeContext` drops one on undeploy,
  its persisted overrides left in the store (an undeploy is not a reset).

For `none` the composition root is `NoneContainer` (refactored: it now owns the aggregate,
the shared `StateStore`, and the single expiry-sweep thread, and installs level control per
context via `installContext(ContextHandle)`). `NoneContainerIntegration` is the thin SPI
implementation over it. The `LogbackLoadDetector` readiness gate moves from
`logaperture-agent` into `logaperture-container-none` alongside them.

This keeps §15.4's "adapters are instantiated per `(framework, context)` pair, never
globally" literally true, and keeps the aggregation logic — the genuinely new part — in
one new class rather than smeared through the existing service.

### `AggregateLevelControl` operation semantics

- **`listLoggers(filter)`** — fan out to every registered context, tag each `LoggerInfo`
  with its context's `stableKey`, concatenate. Sort by context then name. A logger name
  that exists in two contexts produces two rows.
- **`setLevel(name, level, options)`** — **broadcast**, "all pass or all fail": first call
  `checkSetLevelPermitted` on *every* context (raise-vs-lower is judged against each
  context's own effective level, so a capability can be granted in one context and denied
  in another — the pre-flight has to run everywhere before any mutation), then call
  `setLevel` on every context. A context that does not yet know the logger creates it (the
  side-effecting observe the SPI already documents; pre-set works in JBoss LogManager,
  confirmed by the M0 spike). Returns the `LevelOverride` from the `"system"` context. A
  mid-broadcast `applyLevel` *adapter* fault can still leave earlier contexts changed; the
  verification sweep (Slice 3) reconciles that.
- **`resetLevel(name)`** — broadcast the reset to every context. **`resetAll()`** — fan
  out `resetAll` to every context.
- There is no per-call context selector. A future scoping feature would add one here and
  turn the broadcast into a filtered fan-out; nothing else in the design would move.

### Broadcast semantics

There is no per-override context in this release. `setLevel("org.acme.Foo", DEBUG)` is a
**blanket** override: it is applied to `org.acme.Foo` in every registered logging context,
and `resetLevel` / expiry revert it in every context. A logger that exists in only some
contexts is created (pre-set) in the others.

For a plain `java -jar` app, and for a stock standalone WildFly with no
`use-deployment-logging-config` and no `<logging-profile>` (the only configuration this
release supports — see Slice 3), there is exactly **one** context (`"system"`), so
"broadcast to every context" and "set it" are the same thing. The fan-out only becomes
observable if a deployment configuration produces a second `LogContext`, and even then the
semantics are deliberately blanket.

**Deferred — override scoping.** A later release *may* add syntax to limit an override to
one named context (`MyApp1.war:org.acme.Foo`). That would reintroduce a context selector
on `SetLevelOptions` / the MXBean / `logctl`, a per-override `context` key in the state
file (with the `schemaVersion` 1→2 bump that implies), and a rule for what an unscoped
override means in its presence. It is explicitly a nice-to-have, not scoped here, and the
broadcast model is designed to be the unscoped default it slots under — not something it
replaces.

## Data model changes

```
LoggerInfo {                                   — logaperture-api, extended
    ... existing fields ...
    context: String                             new — the owning context's stableKey
}
```

That is the whole data-model change for Slice 1. `LoggerInfo.context` is a read-only
field, never null; for `none` every row reads `"system"`. `SetLevelOptions`,
`LevelOverride`, and the persisted state file are **unchanged** — a broadcast override
carries no context, so nothing needs to store or select one.

### State file — no change

The persisted state file keeps `schemaVersion: 1` and its Feature 2 layout. A broadcast
override is written once and re-applied to every context on load, so there is no
per-context entry to add. (Override scoping, if it is ever built, is what bends
`schemaVersion` to `2` — see "Broadcast semantics".)

### JMX surface

- `LoggerInfoData` gains a `context` field (String, never null), mapped from
  `LoggerInfo.context`. This is an additive field on a returned composite type; a caller
  that reads fields by name is unaffected.
- `LevelControlMXBean.setLevel` is **unchanged** — no new parameter. The broadcast happens
  inside `AggregateLevelControl`, below the MXBean, so the wire signature Feature 2
  shipped stays put.

## The `none` integration

`logaperture-container-none` gains `NoneContainerIntegration implements
ContainerIntegration` alongside the refactored `NoneContainer` composition root:

- `id()` → `"none"`.
- `detect()` → always `true`. `none` is the fallback the agent uses when no real container
  is detected; it is tried last.
- `activate(inst, policy, auditLog, onFirstContextReady)` → new up a `NoneContainer`, kick
  off the `LogbackLoadDetector` readiness poll (wait for SLF4J to bind to a Logback
  `LoggerContext`), and return `NoneContainer.operations()` right away. When the poll
  fires, `NoneContainer.installContext` runs for the one `ContextHandle` — `stableKey`
  `"system"`, `displayName` `"none"`, `adapter` `LogbackAdapterFactory.forCurrentContext()`
  — and then `onFirstContextReady` runs.
- No lifecycle: a plain `java -jar` has one classloader, one context, nothing that appears
  or goes away (§15.2), so `NoneContainer` never calls `AggregateLevelControl.addContext` /
  `removeContext`.
- Logback's own reset event (`scan="true"`, `JMXConfigurator`, `context.reset()`) is
  wired by `installContext` exactly as `NoneContainer.install` did before — a *framework*
  reset, not a *container* context event, and it stays the adapter's `onReset` concern.

`NoneContainer`'s former `install` body — baseline capture, state resume, reset wiring,
sweep start — is now `installContext(ContextHandle)` plus the constructor's sweep
scheduling, driven once for the single `none` context. The `AutoCloseable` teardown
contract (stop the sweeper, clear each context's reset listener, release the state-store
lock) is preserved on `NoneContainer` itself.

## Agent bootstrap: detect-then-install

`AgentBootstrap` today hard-wires `LogbackLoadDetector.awaitLogbackAndThen(inst, () ->
NoneContainer.install(...))`. It becomes:

1. Build the ordered list of available `ContainerIntegration`s. Slice 1: `[none]`. Slice 3
   prepends `wildfly`. Order is "most specific first, `none` last".
2. Pick the first whose `detect()` returns true.
3. Call `activate(inst, policy, auditLog, onFirstContextReady)`. The integration owns
   "wait until safe, discover contexts, install per context, wire lifecycle".
4. In `onFirstContextReady` (runs once the first context is installed): register the
   returned `AggregateLevelControl` with `JmxRegistrar`, then set the `logaperture.version`
   marker (from the CLI slice). Registering the MXBean only now means "MBean present"
   implies "there is a context to control" — the CLI polls for the MBean, then calls it.

`detect()` for `none` being unconditionally true means step 2 always resolves; there is no
"no container" failure mode.

## Out of scope for Slice 1 — restated

No WildFly. No JBoss LogManager. No `logctl` changes. No new IT. The exit criterion is
entirely "the existing behaviour, refactored, still passes."

## Testing

Per top-level §12, all in-process against the fast `none` path:

- Every existing `logaperture-core`, `logaperture-container-none`, and
  `logaperture-control-jmx` test passes unchanged in behaviour (some will need a
  mechanical update for the new `LoggerInfo.context` / `LoggerInfoData.context` field).
- `AggregateLevelControl` with a **single** fake context behaves identically to calling
  the underlying `LevelControlService` directly — `listLoggers` / `setLevel` / `resetLevel`
  / `resetAll` round-trip, audit records written, capability checks enforced.
- `AggregateLevelControl` with **two** fake contexts (this is the new logic, exercised with
  fakes even though `none` never produces two) — `AggregateLevelControlTest`:
  - `listLoggers` returns rows from both, each tagged with its context; a name present in
    both produces two rows.
  - `setLevel` broadcasts: the level lands in **both** contexts, including one where the
    logger did not previously exist (created / pre-set).
  - `setLevel` denied in one context (raise there, lower in the other, under a no-raise
    policy) mutates **neither** — the "all pass or all fail" pre-flight.
  - `resetLevel`, `resetAll`, and the expiry sweep revert the override in **both** contexts.
  - `addContext` for a later context re-broadcasts the active overrides onto it — a level
    set before that context existed is present on it immediately after it registers; an
    already-elapsed `--for` override is **not** re-applied.
  - `removeContext` drops a context from the aggregate but leaves the persisted overrides
    in the `StateStore` (an undeploy is not a reset).
  - `setLevel` with no context registered throws.
- `LevelControlService.adoptOverride` applies to adapter + registry and records a `"resume"`
  audit entry, without writing to the (shared) state store; `activeOverrides()` returns
  every tracked override — `LevelControlServiceTest`.
- State file: the existing `StateFileFormatTest` / `FileStateStoreTest` pass unchanged — no
  `context` field, `schemaVersion` stays `1`.
- The `none` composition root (`NoneContainerTest`, driving `NoneContainer` directly with a
  hand-built `"system"` `ContextHandle`): baseline capture, resume across a simulated
  restart, expired-`FOR` not reappearing, the expiry sweep, Logback-reset re-application,
  and the closed-root-listener-does-not-fire regression — all still pass, now through the
  aggregate. The full detect→`activate`→install→JMX path is covered end to end by the
  unchanged cross-process `LevelControlEndToEndIT` and `CliEndToEndIT`.

## Exit criterion — Slice 1

`logaperture-agent` installs level control through a `ContainerIntegration` +
`AggregateLevelControl` rather than a hard-wired `NoneContainer.install`, and every
Feature 1 and Feature 2 behaviour — baseline, hierarchy, the three tiers, expiry, resume,
Logback reset re-application, capability checks, audit — is unchanged for a plain `java
-jar` app running Logback, now with a `context` of `"system"` visible on every logger row.
`SetLevelOptions`, `LevelControlMXBean.setLevel`, and the state file are untouched. Full
reactor `mvn verify` green, including the existing cross-process IT.

---

# Slice 2 — The JBoss LogManager adapter

## Scope

A new `logaperture-adapter-jboss-logmanager` module implementing `LoggingAdapter` against
`org.jboss.logmanager` (which WildFly installs as the `java.util.logging.LogManager`, and
routes SLF4J / Log4j / commons-logging / JUL into — §15.6). Tested entirely in-process
with JBoss LogManager on the test classpath; **no WildFly** (that's Slice 3).

**In scope:** per-`LogContext` `knownLoggerNames` / `configuredLevel` / `effectiveLevel` /
`applyLevel`; the LogAperture↔JUL level mapping; the handler-floor detection the M0 spike
flagged; a `LogContext`→adapter factory Slice 3's container integration will call once per
context.

**Out of scope:** gate-stage filters and render-stage formatter wrapping (those are the
squelch engine and `trimStackTrace`, later milestones — level control only here);
`ExtLogRecord`'s richer matcher surface (§15.6 — a rule-model concern, not level control);
WildFly-specific `LogContextSelector` wiring (Slice 3).

## Level mapping

LogAperture's `Level` enum (`ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF`) against
`java.util.logging.Level` (and JBoss LogManager's identical-in-practice extension):

| LogAperture | java.util.logging | Notes |
|---|---|---|
| `ALL` | `ALL` | |
| `TRACE` | `FINEST` | `FINER` also reads back as `TRACE` (below) |
| `DEBUG` | `FINE` | |
| `INFO` | `INFO` | `CONFIG` reads back as `INFO` (below) |
| `WARN` | `WARNING` | |
| `ERROR` | `SEVERE` | |
| `OFF` | `OFF` | |

Writing is exact (the left column maps to exactly one JUL level). Reading is
lossy-but-defined: JUL has two levels LogAperture doesn't model —

- `FINER` (between `FINE` and `FINEST`) reads back as `TRACE`. A logger a human left at
  `FINER` in `standalone.xml` shows as `TRACE`; `setLevel(TRACE)` then writes `FINEST`,
  which is *more* verbose than what was there. This is a real, small lossiness; it is
  documented, and `resetLevel` restores the captured original `java.util.logging.Level`
  object, not LogAperture's round-tripped approximation — so a reset is always exact even
  when the display wasn't.
- `CONFIG` (between `INFO` and `FINE`, conventionally "configuration messages") reads back
  as `INFO`. Same treatment: display approximates, baseline capture stores the real
  `Level`, reset is exact.

The mapper lives in the adapter module (mirroring `logaperture-adapter-logback`'s
`LevelMapper`), and `configuredLevel` / `effectiveLevel` return `Optional<Level>` / `Level`
in LogAperture's enum. Read-back is resolved by `java.util.logging.Level.intValue()` against
the canonical thresholds, so it is total over any level (JBoss LogManager's own
`TRACE`/`DEBUG`/… aliases share intValues with JUL levels and fall out correctly).

For exact reset the adapter keeps, per logger, the real `java.util.logging.Level` it first
observed (`Optional.empty()` = "observed, no explicit level"). `applyLevel(name, L)`
restores that captured object instead of `toJul(L)` **when `L` equals what the captured
level reads back as** — so `resetLevel` on a `FINER`/`CONFIG` baseline lands on `FINER`/
`CONFIG` exactly, even though the display only ever said `TRACE`/`INFO`. The one corner:
a *deliberate* `setLevel(TRACE)` on a `FINER`-baselined logger also lands on `FINER`, not
`FINEST` — a one-notch difference on an already-rare config, and the displays are identical
either way.

## Adapter operations

Against a single `org.jboss.logmanager.LogContext`:

- **`knownLoggerNames()`** → `logContext.getLoggerNames()` (an `Enumeration`), materialised
  to a `List`. Per-context; the system context and each deployment context enumerate
  separately.
- **`configuredLevel(name)`** → `logContext.getLogger(name).getLevel()` mapped through the
  table; `null` (JUL's "inherit") → `Optional.empty()`, matching Logback's semantics and
  `LoggingAdapter`'s contract. Like Logback, asking by name **creates** the logger if
  absent — the side-effecting "observe" the SPI already documents.
- **`effectiveLevel(name)`** → walk parents until a non-null `getLevel()`, map it (falling
  back to `getEffectiveLevel()`'s already-resolved int if no ancestor carries one — a real
  WildFly root always does; this is the defensive path). This is the *logger's* effective
  level, **not** accounting for handler floors — see below.
- **`applyLevel(name, level)`** → `logger.setLevel(mapped)`; `null` → `logger.setLevel(null)`
  (back to inherited). Safely re-invokable (the SPI's requirement) — `setLevel` is
  idempotent.
- **`onReset` / `clearResetListener`** → the SPI defaults (no-op). JBoss LogManager has no
  reconfiguration-notification hook (§4.3: "none — poll"). Re-application after a WildFly
  logging-subsystem change is Slice 3's job, driven by the container's periodic
  verification sweep (§15.5), not by an adapter event.

Three details the adapter handles that the Logback one doesn't have to:

- **Root logger name.** JBoss LogManager (like JUL) names its root logger `""`, which
  `core`/`NameFilter` would read as "match every logger". The adapter surfaces it — and
  accepts it — as `"ROOT"`, matching the Logback adapter's convention, so `logctl levels
  ROOT` means the same thing on both backends.
- **Pinned loggers.** JBoss LogManager holds `Logger` facades through weak/phantom
  references by default; a logger nothing else strongly references can be reaped and lose
  its applied level. The adapter keeps a strong reference to every `Logger` it touches
  (which pins the node chain up to the root), so an override stays applied without
  depending on Slice 3's sweep.
- **Create-on-observe applies to all read methods**, not just `configuredLevel` — same as
  the Logback adapter, and `core` creates the logger during baseline capture first anyway.
  `handlerFloorsBelow(name, null)` (a "back to inherited", not a raise) returns an empty
  list rather than probing.

## Handler-level thresholds — the second gate

The M0 spike's headline WildFly finding (point 2): raising a logger's level is **necessary
but not sufficient** for output. `standalone.xml` pins `CONSOLE` at `level="INFO"`; the
`FILE` handler defaults to `ALL`. Setting a logger to `FINE` surfaces its `FINE` records
in `server.log` but **never on the console**, because the `CONSOLE` handler's own level
floor silently discards anything below `INFO` regardless of what the logger permits. This
is a JBoss-LogManager-shaped trap that Logback (whose appenders have no separate level
filter by default) does not have.

`listLoggers` reporting an effective level of `FINE` for such a logger would be actively
misleading to a support engineer whose actual sink is the console.

**What Slice 2 does about it — the minimum that is honest:**

- `effectiveLevel` stays the *logger's* effective level (consistent with the Logback
  adapter and with `core`'s capability-check logic, which compares target vs. effective to
  classify raise/lower).
- The adapter exposes a new, JBoss-LogManager-specific method **not** on the `LoggingAdapter`
  SPI (it doesn't generalise): `List<HandlerFloor> handlerFloorsBelow(String loggerName,
  Level target)` — the handlers on the path from this logger to the root whose own level
  is stricter than `target`, i.e. the ones that would swallow records the new level lets
  through. `HandlerFloor` is `{ String handlerName, Level floor }`.
- On `applyLevel`, when `handlerFloorsBelow(name, level)` is non-empty and `level` is more
  verbose than the logger's *previous effective* level (a genuine raise), the adapter emits
  **one** diagnostic via `Diagnostics.warn`, naming the first offending handler (and, if
  there is more than one, an `(and N more)` count):
  `"logaperture: <logger> set to <level>, but handler <H> has a level floor of <F> — <level>
  records will not reach it. Lower the handler in standalone.xml, or accept that this
  override only affects sinks without a stricter floor."`

**What Slice 2 deliberately does *not* do:** it does not lower handlers automatically, and
it does not add a structured "warnings" channel to `setLevel`'s response. Automatically
lowering a handler is a mutation with blast radius beyond the one logger the user named —
it belongs behind an explicit opt-in (`setLevel` option, or a `logctl` flag), which is a
later refinement. `doctor` (deferred) is where the full logger-vs-handler-vs-sink picture
gets rendered. Slice 2's bar is "the user is told, in the agent's own diagnostics, when a
change won't do what they expect" — not "the tool fixes it for them".

## Module

```
logaperture-adapter-jboss-logmanager   NEW
  Depends on: logaperture-api (Level), logaperture-core (LoggingAdapter SPI),
  logaperture-bridge (Diagnostics — for the handler-floor warning).
  org.jboss.logmanager:jboss-logmanager as `provided` scope — present at test
  time, present at runtime inside WildFly, never shaded into the agent jar
  (same discipline as logback-classic for the Logback adapter, §4.6 /
  level-control.md's Can-Retransform-Classes note). Compiled against
  3.0.6.Final (a `jboss-logmanager.version` property); the level-control API
  subset used is stable back to WildFly 26.1.3's 2.1.x. The module's own
  test JVM runs with `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`.
```

Add `logaperture-adapter-jboss-logmanager` to the top-level spec's §4.6 module list, which
currently lists only `-log4j2` / `-jul` / `-log4j1` and predates the realisation (§15.6)
that JBoss LogManager is its own backend covering WildFly and Quarkus-JVM.

## Testing

In-process, JBoss LogManager on the test classpath (with
`-Djava.util.logging.manager=org.jboss.logmanager.LogManager`), no container.

`JbossLogManagerAdapterTest` — the adapter in isolation, against `LogContext.create()`:

- Level mapping round-trips for all seven LogAperture levels; `FINER`→`TRACE` and
  `CONFIG`→`INFO` read-back approximations are asserted explicitly, and the reset path
  (`applyLevel` with the read-back level) restores the exact original
  `java.util.logging.Level` (`assertSame`, not just equal).
- `knownLoggerNames` / `configuredLevel` / `effectiveLevel` / `applyLevel` against a
  freshly built `LogContext`: an explicit level, an inherited logger (`configuredLevel`
  empty), the side-effecting create-on-observe, a not-yet-created logger pre-set via
  `applyLevel`, hierarchy resolution, and `applyLevel(name, null)` clearing back to
  inherited.
- Two independent `LogContext`s: a level set in one is invisible in the other.
- The root logger is surfaced as `"ROOT"` (not `""`); `configuredLevel("ROOT")` /
  `applyLevel("ROOT", …)` resolve to the real empty-string root and an inheriting child
  sees the change.
- Handler floor: a `ConsoleHandler` pinned at `INFO` on the context root;
  `handlerFloorsBelow(x, DEBUG)` returns it, `handlerFloorsBelow(x, WARN)` does not;
  `applyLevel(x, DEBUG)` emits exactly one `level floor` diagnostic naming
  `ConsoleHandler`; `applyLevel(x, WARN)` (not a raise past the floor) emits none.
- Re-appliability: `applyLevel` twice is a no-op-equivalent; the adapter survives a
  `LogContext` whose loggers were externally reset.

`JbossLogManagerLevelControlTest` — the Slice 2 exit criterion: `AggregateLevelControl` +
`LevelControlService` driving `JbossLogManagerAdapterFactory.forContext(...)` through the
full loop — `listLoggers`/`setLevel`/`resetLevel`/`resetAll` with the level mapped exactly
to `FINE`, a `--for` reverted by `sweepExpiredOverrides`, and a `--sticky` override
resuming over the same `LogContext` across a simulated restart (two `FileStateStore.open()`
cycles, `logaperture.home` at a `@TempDir`).

## Exit criterion — Slice 2

`AggregateLevelControl` driving a `JbossLogManagerAdapter` bound to a hand-built
`LogContext` performs the full Feature 1 + Feature 2 loop — list, set (all three tiers),
reset, expiry, resume — with correct level mapping and exact reset, and emits a handler-
floor diagnostic when a set level would be swallowed by a stricter handler. No WildFly
involved. Full reactor green.

---

# Slice 3 — WildFly container integration

## Scope

A new `logaperture-container-wildfly` module: a `ContainerIntegration` that detects
standalone WildFly, respects the premain discipline, discovers and tracks every registered
`LogContext`, re-broadcasts active overrides after a redeploy and after a logging-subsystem
reconfiguration, and is verified against a real WildFly via Testcontainers.

**In scope:** detection; the premain gotcha and its regression test; `LogContext`
enumeration and add/remove lifecycle via `LogContextSelector`; `stableKey` = `"system"`
for the server context and the deployment name for any deployment context (used to
recognise a context across a redeploy, not to key persistence); the redeploy loop
(re-broadcast on context registration); the §15.5 verification sweep as the re-apply
mechanism for the event-less logging-subsystem-change case; the display-only CONTEXT
column (shown only when >1 context); the real-WildFly IT.

**Out of scope:** override scoping / a `logctl --context` flag (deferred — see Slice 1's
"Broadcast semantics"); domain mode (§15.6 — stated explicitly as out of scope for v1);
`use-deployment-logging-config` deployments with their own bundled logging setup (§15.6's
exception — David confirmed his deployments don't use it; deferred); `<logging-profile>`
handling (same); gate/render-stage anything.

## Detection and the premain gotcha

WildFly sets `java.util.logging.manager=org.jboss.logmanager.LogManager` and relies on
**nothing touching `java.util.logging` before that takes effect**. An agent `premain` runs
early enough to break this: touch a JUL class too soon and the JDK's default `LogManager`
is installed instead, and WildFly's logging bootstrap fails or misbehaves (§15.6, "The
premain gotcha that will cost you a day").

Therefore:

- **`detect()`** probes **only** system properties and class *presence*, never a
  `java.util.logging` method and never a force-load of a JBoss LogManager class. It
  requires **both** `System.getProperty("java.util.logging.manager")` ==
  `org.jboss.logmanager.LogManager` **and** `org.jboss.modules.Module` loadable — the
  second disambiguates a JBoss-Modules server (WildFly) from other JBoss-LogManager users
  (Quarkus-JVM, a separate future integration). A domain-mode launch
  (`jboss.domain.base.dir` set) is detected and declined with a diagnostic (§15.6: v1 is
  standalone only).
- **Discovery** (`WildFlyLogManagerReadiness`, driven on a daemon thread from `activate`)
  does the M0 spike point 2 discipline: poll `System.getProperty("java.util.logging.manager")`
  (a side channel — never `java.util.logging` itself) until it reads
  `org.jboss.logmanager.LogManager`, wait a short settle, *then* make the first JUL call —
  `LogManager.getLogManager()` — and confirm the installed manager really is the JBoss one
  before installing anything. Then bind a `JbossLogManagerAdapter` to
  `LogContext.getLogContext()` (the current selector's context — the server's own system
  context) and `installContext` it as `stableKey` `"system"`.
- **Regression test (§15.6 demands this):** the Testcontainers IT boots WildFly with the
  agent attached and asserts `java.util.logging.LogManager.getLogManager().getClass()
  .getName()` (read from inside the server, e.g. via a tiny deployed probe or a management
  query) is `org.jboss.logmanager.LogManager`. A green agent install that broke the
  `LogManager` is a failure, not a pass.

## `LogContext` discovery and lifecycle

Implementation finding: **`org.jboss.logmanager.LogContextSelector` exposes only
`getLogContext()`** — there is no API to *enumerate* every registered `LogContext`.
`ClassLoaderLogContextSelector` (what WildFly builds on) keeps its per-classloader
contexts in a private map. So "discover every `LogContext`" is not something the public
API supports, and Slice 3 does not attempt it.

It does not need to. For a stock standalone WildFly with no `use-deployment-logging-config`
and no `<logging-profile>` — the only configuration this release supports — **every
deployment's loggers route to the server's own system `LogContext`** (the M0 finding). So
there is exactly one context, `LogContext.getLogContext()` returns it, and the Slice 1
multi-context machinery runs at N = 1, exactly as for `none`.

A deployment gets its **own** `LogContext` only under the deferred config
(`use-deployment-logging-config` / `<logging-profile>`). Since this release doesn't support
that and there's no enumeration API, the `onContextAdded` / `onContextRemoved` lifecycle
is not reachable in the supported configuration and Slice 3 wires nothing for it. The
`AggregateLevelControl.addContext` / `removeContext` methods from Slice 1 stay for when a
future slice adds deployment contexts.

## The redeploy loop

For the supported configuration there is a single system `LogContext` that **survives
every redeploy** — it is the server's, not the deployment's. So redeploy survival is
automatic, from two independent mechanisms already in place:

- The `JbossLogManagerAdapter` (Slice 2) pins a strong reference to every `Logger` it
  touches, so a redeployed app calling `Logger.getLogger("com.myapp.X")` gets back the
  same node — still carrying the override — rather than a fresh one.
- The override is in the one state file; a JVM restart resumes it (Feature 2), and the
  verification sweep re-applies it if anything cleared it.

The redeploy-keyed-by-deployment-name path the earlier draft described only applies to
per-deployment `LogContext`s, which the supported config doesn't produce.

## Reconfiguration re-application: the verification sweep

JBoss LogManager has no reconfiguration event (§4.3), and a level change made through
WildFly's own `/subsystem=logging` management API (or an XML edit + `:reload`) will
overwrite a runtime override with no notification. Per §15.5, the answer is not a
per-container hook but a **core invariant**: the agent must be able to re-establish its
entire installed state, idempotently, at any moment, from an event *or* a periodic sweep.

Slice 3 adds the **verification sweep** as `LevelControlService.verifyAndReapply(Instant)`
+ `AggregateLevelControl.verificationSweep(Instant)`:

- For every active override, compare the adapter's current `effectiveLevel` against the
  override's level. On disagreement, re-apply via `OverrideApplier` and record a
  `"verification-sweep"` mutation. Idempotent — an already-correct override is skipped, so
  a quiet sweep writes nothing and produces no audit noise. Expired `FOR` overrides are
  left to the expiry sweep.
- `WildFlyContainer`'s single sweep thread runs it every N seconds (default 30) right
  after the expiry sweep. `NoneContainer` runs it too, on the same schedule — §15.5 makes
  it a core invariant, not a WildFly special case (belt-and-suspenders there, since
  Logback fires its own reset event).

**A clean event hook was found (mechanism 1).**
`org.jboss.logmanager.LogManager.addConfigurationListener(Runnable)` fires on
`readConfiguration` / `updateConfiguration` — the path a `/subsystem=logging` change and an
XML edit + `:reload` both take. `WildFlyContainerIntegration` registers the verification
sweep against it (submitted to the sweep thread, never run on WildFly's config thread), so
a management change is corrected promptly; the periodic sweep is the floor. This is better
than §15.6's pessimistic "WildFly does not publish a clean event".

## Installation mechanics

- **Attach:** a line in `standalone.conf` (`JAVA_OPTS="$JAVA_OPTS -javaagent:/path/to/
  logaperture-agent.jar"`). `WildFlyContainerIntegration.guidance()` carries this, plus
  "the agent's overrides never touch `standalone.xml`".
- **`jboss.modules.system.pkgs` — resolved analytically; to be confirmed on the Docker
  shakeout.** The agent binds only the **system** `LogContext`, never a deployment
  classloader. WildFly puts `jboss-logmanager` on the *system* classpath (it must, so
  `java.util.logging` can load `org.jboss.logmanager.LogManager` from the
  `java.util.logging.manager` property), so the agent — also on the system classpath —
  already sees `org.jboss.logmanager.*` with **no `jboss.modules.system.pkgs` entry
  needed**. Reaching deployment `LogContext`s *would* need one, but that's the deferred
  config. The IT's boot assertion confirms the agent installs cleanly; if a
  `ClassNotFoundException` surfaces on the shakeout, the required line goes into
  `guidance()` and the README.
- **Domain mode:** `detect()` returns false when `jboss.domain.base.dir` is set.

## Never touch `standalone.xml`

§15.10, and for a WildFly audience §15.6 says to state it "in the first paragraph of the
README": a level change made through the WildFly CLI is written back into the XML; the
agent's overrides live only in its own store, expire on a timer, and touch no
server-owned file. Slice 3 adds nothing that writes `standalone.xml`,
`logging.properties`, or any deployment descriptor. The `logctl` confirmation and the
README both say so explicitly.

## `logctl` changes

- `logctl levels` and `logctl status` gain a **CONTEXT** column, shown **only when more
  than one context is present** in the result (so a plain `java -jar` user, and a stock
  WildFly with one shared context, never see it). Display only — there is no flag that
  consumes it.
- No new option and no new exit code. Overrides are blanket; there is nothing to
  disambiguate. (A `--context` selector and an "ambiguous context" exit code are what
  override scoping would add — deferred, see Slice 1's "Broadcast semantics".)

This is additive to the CLI transport spec; that spec gets a short "Multi-context CONTEXT
column (added by wildfly-support)" note rather than a rewrite.

## Testing

In-process (no Docker) — all run by `mvn verify`:

- `logaperture-container-wildfly`:
  - `WildFlyContainerIntegrationTest` — `detect()` true only with both signals (the test
    JVM sets `java.util.logging.manager` and has jboss-modules), false in domain mode,
    false without the manager property; `guidance()` points at `standalone.conf` and
    states `standalone.xml` is untouched.
  - `WildFlyLogManagerReadinessTest` — the readiness gate runs its callback once the
    manager is confirmed installed (the test JVM is the "already installed" case).
  - `WildFlyContainerTest` — the composition root against a hand-built `LogContext`: the
    full list/set/reset/resetAll loop with the level mapped exactly to `FINE`; the
    verification sweep re-applies an override cleared out from under it and audits a
    `"verification-sweep"` mutation; a quiet sweep is a no-op; `runVerificationSweepNow()`
    (the config-listener path) lands the re-apply on the sweep thread; `--for` expiry;
    `--sticky` resume across a simulated restart.
- `logaperture-core`: `LevelControlService.verifyAndReapply` (drift re-applied + audited,
  no-op when in force, expired `FOR` skipped) and `AggregateLevelControl.verificationSweep`
  (fan-out + count).
- `logaperture-cli` (`CommandsTest`): the CONTEXT column appears in `levels` / `status`
  iff the result spans more than one distinct context, and is absent otherwise.

**`logaperture-it` — Testcontainers, real WildFly 26.1.3.Final** (`WildFlyContainerIT`,
`@Testcontainers(disabledWithoutDocker = true)` so `mvn verify` stays green without Docker;
CI runs it on an ubuntu runner). **Written, not yet shaken out against a real Docker
daemon.** Implemented:
  - Boot with the agent attached; assert the WildFly integration installed and can see
    `org.jboss.*` loggers, and `server.log` shows a clean start with no premature
    `java.util.logging` access (the premain-gotcha regression signal).
  - `listLoggers` lists the single `"system"` context.
  - A `--for` override on `org.jboss.as.server` reverts on schedule.
  - `standalone.xml` is `md5sum`-identical after a session of overrides + `resetAll`.

  `@Disabled` pending the shakeout (each carries a TODO): the deployed-WAR
  visibility/redeploy test (needs an in-test WAR build) and the management-CLI-collision →
  verification-sweep test.

## Exit criterion — Slice 3

Code complete and `mvn verify` green (in-process tests). The real-WildFly bar —
`WildFlyContainerIT` passing against Docker, the two `@Disabled` sub-tests finished, and
the `jboss.modules.system.pkgs` question confirmed — is **pending a Docker shakeout run**.
That run, plus manual acceptance testing against a generic WAR and the day-job
application, is the remaining Slice 3 work; it needs a Docker daemon (this dev environment
has none).

---

# Cross-cutting

## Capability and audit

No new capabilities. The existing `view` / `level.raise` / `level.lower` / `persist` set
(level-control.md, persistence.md) covers everything here — controlling a WildFly logger is
the same operation as controlling a Logback one, at a different backend.

Audit records are **unchanged in shape** — a broadcast override is one logical mutation,
not one per context, so there is no `context` key to add. One new `source` value, same
`AuditRecord` shape:

- `"verification-sweep"` — an override re-applied after a WildFly management change (or an
  XML edit + `:reload`) reset the logger (Slice 3).
- (`"resume"` already exists — it now also covers the redeploy re-apply. With a single
  shared context, that is one `resume` record per override, as today.)

§9.7's deferred items (hash-chaining, syslog/Event Log mirroring) are unchanged.

## Roadmap impact

This work is taken **out of roadmap order**. §17 has M1 finishing — `top`, `doctor`,
storm detection in report-only mode, and the fuller §9 capability/audit model — before any
real container. Going to WildFly now consciously defers those:

- **`top` / `doctor` / storm-detection-report-only** slip behind this feature. They remain
  M1 scope; they are not cancelled. `doctor` in particular gains a clear WildFly job once
  this lands (the logger-vs-handler-vs-sink picture from Slice 2's handler-floor finding).
- **The §9 capability/audit model** is *not* expanded here and does not need to be — the
  minimal capability slice from Feature 1 already gates every mutation, and WildFly
  support adds no new operation or surface, so the roadmap's "a capability check retrofitted
  after the operations exist is a capability check with holes in it" warning (§17) is not
  triggered.

The rationale for the reorder: WildFly is the environment where this project earns its
keep for its first real user, and manual acceptance testing needs a real deployment
target — which `top` and `doctor` do not provide.

## Top-level spec changes

Landed with Slice 1 (the SPI) and Slice 2 (the module), per CLAUDE.md's spec-with-code
rule:

- **Slice 1 (done):** §15.2's `ContainerIntegration` sketch is updated to the implemented
  `activate` shape, with a note explaining the divergence from the original
  `discoverContexts` / `onContextAdded` / `onContextRemoved` sketch, and `stableKey` moved
  onto `ContextHandle`. The `> Implementation spec: doc/specs/wildfly-support.md` link
  under §15.6 was added with the spec's WIP commit.
- **Slice 2 (done):** §4.6 module list gains `logaperture-adapter-jboss-logmanager` (the
  list predated §15.6's JBoss-LogManager-is-its-own-backend point).
- **Slice 3 (done):** no top-level spec change needed — §4.6 already lists
  `logaperture-container-wildfly` and `logaperture-it`. The `logaperture-it` module is
  created for real (Testcontainers), populating that line.

## Overall exit criterion

A standalone WildFly 26.1.3.Final, agent attached via `standalone.conf`, controlled through
`logctl`, with server (and, under the deferred config, deployment) logger visibility,
blanket overrides that survive a restart and a redeploy, timer-based revert,
verification-sweep correction of external changes, and `standalone.xml` never touched.

All of the code for this is landed and covered by in-process tests. What remains before it
is *demonstrated* end to end: a shakeout run of `WildFlyContainerIT` against a real Docker
daemon (finishing its two `@Disabled` sub-tests and confirming `jboss.modules.system.pkgs`),
then David's manual acceptance testing against a generic WAR and the day-job application.
