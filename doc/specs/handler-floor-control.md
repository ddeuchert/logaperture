# Handler-Level Control — `logctl handler`

Status: implemented and verified end-to-end, including against a real
standalone WildFly (`WildFlyContainerIT`) and the handler-level verification
sweep (`AggregateLevelControl.verificationSweep`, shared with loggers' own).
One finding from that real-WildFly run changed the shipped behavior: handler
**name resolution** (Open decision #1) does not, in practice, produce
WildFly's friendly names (`CONSOLE`, `FILE`) — every handler falls back to its
`<class>@<idhash>` identity token. See the "Adapter SPI" section below for
what was tried and why it doesn't pan out. The token is still a fully usable
identifier (stable, and exactly what every warning's suggested command
names), so the feature works end to end regardless — just without the
cosmetic friendly name.
Priority: **high** — pulled forward in §17 as the first behaviour-modifying feature
after M1. "Make this class TRACE and let me see it on the console" is a primary
developer interaction (§14.1); today it dead-ends at a warning to hand-edit
`standalone.xml`. The `CONSOLE` handler is the case to get right first.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §5 (Feature 1 —
level control), §9.3 (capability model), §9.7 (audit), §15.6 (WildFly), §17
(roadmap).
Builds on: [`doc/specs/level-control.md`](level-control.md) (Feature 1 —
`setLevel`, baseline capture, reversion), [`doc/specs/persistence.md`](persistence.md)
(Feature 2 — tiers, expiry, redeploy re-application), and
[`doc/specs/wildfly-support.md`](wildfly-support.md), whose JBoss LogManager
adapter already computes `handlerFloorsBelow(loggerName, target)` and emits the
"level floor" diagnostic this feature turns into an actionable message.

## Functional summary

After this feature, the user will be able to:

- Raise a logger's level and, when a handler on its path would still swallow the
  new records (WildFly's `CONSOLE` handler fixed at `INFO`, say), get a **warning
  that names each blocking handler and the exact command to clear it** —
  `logctl trace org.perfmon4j` prints
  `WARN: TRACE is below the current threshold of handler CONSOLE (INFO) — to see
  this output run: logctl handler CONSOLE TRACE`. The level change still takes
  effect; the warning is advice, not an error.
- Run that command — **`logctl handler <name> <level>`** — as a first-class
  operation to set a handler's own level, up or down, with the same lifetime
  tokens as a level command: `logctl handler CONSOLE TRACE`,
  `logctl handler CONSOLE DEBUG for 30m --reason INC-42`,
  `logctl handler CONSOLE ALL sticky`.
- Lower **only the handlers they care about** — if two handlers block the output
  the warning lists a command for each, and the developer who only wants it on
  the console runs just the `CONSOLE` one.
- Revert a handler exactly like a logger: on its own expiry, with
  `logctl handler CONSOLE reset`, or with `logctl reset --all`. Each reverts to
  the level the handler had before LogAperture first touched it.
- See active handler overrides in `logctl status` (and `--json`), and every
  handler change and reversion in the agent's audit trail.
- Still never have `standalone.xml` or `logging.properties` touched — the handler
  level is changed on the live handler object only, tracked by the agent, and
  reverts on its own.
- Run `logctl handler` harmlessly on a framework whose handlers have no level of
  their own (plain Logback appenders): it is a no-op with a one-line note, not an
  error.

## Scope of this slice

The handler-floor *detection* shipped with the JBoss LogManager adapter
(`wildfly-support.md`, Slice 2), which deliberately stopped at "tell the user"
and reserved the fix as "an explicit opt-in (`setLevel` option, or a `logctl`
flag) … a later refinement". This spec is that refinement. It resolves it as a
**standalone handler operation plus an actionable warning on level commands** —
not as a flag coupled to the triggering logger override. The developer decides,
per handler, whether they want the sink widened.

**In scope:**

- A new operation `setHandlerLevel(name, level, options)` and its reversion
  `resetHandler(name)`, surfaced as `logctl handler <name> <level>` /
  `logctl handler <name> reset`. `options` carries the same
  `reason` / `expiresIn` / `tier` as `SetLevelOptions` (no `includeChildren` —
  handlers have no hierarchy).
- Both directions: lowering a handler's threshold (revealing output) and raising
  it (squelching). Capabilities `handler.lower` / `handler.raise` mirror
  `level.lower` / `level.raise`.
- Independent lifetime: a handler override has its own baseline, its own tier and
  `expiresAt`, its own audit records, and reverts on its own expiry / reset /
  `resetAll` — it is **not** coupled to any logger override.
- An **actionable warning** emitted by every level command (`set`, `trace`,
  `debug`, …) that raises a logger past a handler on its path: it names each
  blocking handler, its current level, and the `logctl handler …` command to
  clear it — one per handler. Non-fatal, exit 0.
- Baseline capture of each handler's level on first touch; reversion restores it.
  Supersede semantics match loggers (a second `logctl handler CONSOLE …` replaces
  the first, keeping the original baseline).
- Persistence: a `--for` / `--sticky` handler override is written to the state
  file and re-applied on resume and after WildFly reconfiguration, exactly as a
  logger override is (`persistence.md`). `--session` is not persisted.
- Multi-context (WildFly): a handler name resolves in every managed `LogContext`
  that has one; the change broadcasts to all of them, baseline per context.
- JBoss LogManager / JUL adapter implementation; Logback + `none` treat the
  operation as a no-op with a diagnostic.

**Explicitly out of scope** (deferred, each with what it needs):

- Naming handlers that are **not** on any logger's resolved path, or bulk
  operations over "all handlers" — `logctl handler` takes one name at a time.
- Formatter / filter changes on a handler (render-stage wrapping —
  `wildfly-support.md` "Out of scope").
- Logback appender **filter** manipulation (`ThresholdFilter` / `LevelFilter`) to
  simulate a handler level — a separate feature; here Logback is a no-op.
- `doctor`'s full logger-vs-handler-vs-sink rendering (still deferred).
- A precedence model between a handler override and a logging-config reload that
  *changes* the handler's configured level (as opposed to resetting it) — beyond
  what `persistence.md` §6.6 already says for loggers.

## The operation

`logctl handler <name> <level> [<tier token>] [--reason <text>]`

```
logctl handler CONSOLE TRACE
logctl handler CONSOLE DEBUG for 30m --reason INC-42
logctl handler CONSOLE ALL sticky
logctl handler FILE WARN                 # squelch: raise the FILE handler's floor
logctl handler CONSOLE reset             # revert now
```

- `<name>` is the configured handler name as the framework knows it — WildFly's
  `CONSOLE`, `FILE`. It is what the warning message prints, so a developer never
  has to guess it. An unknown name fails the way any unreachable adapter target
  does — reported by the message the adapter's `UnknownHandlerException` carries
  — rather than a dedicated exit code enumerating known names; that enumeration
  needs the discovery listing Open decision A deferred, and revisits this when
  it lands.
- `<level>` is any LogAperture level, same grammar as `logctl set`.
- Tier tokens are exactly Feature 1/2's, including the CLI's own bare-token
  default: no token at all means `for 4h`, same as `logctl trace <logger>` —
  not `--session` (`SetHandlerLevelOptions.defaults()`'s `SESSION` default is
  the *operations*-level default for a caller that skips the CLI, e.g. JMX
  directly). `--reason` is copied into the audit record.
- Phone-test: `handler`, the name, and the level contain none of `: = ( ) /`; the
  `--help` topic for `handler` mirrors `set`.

`resetHandler` is reached by `logctl handler <name> reset`; `logctl reset --all`
reverts handler overrides alongside logger overrides. `logctl reset <name>`
without `--all` stays logger-only — handler resets are always spelled
`handler <name> reset` to keep the two namespaces distinct.

## Warning on level commands

Every level-setting command runs `handlerFloorsBelow(logger, targetLevel)` after
applying the level (it already does, for the Slice 2 diagnostic). When the result
is non-empty **and** the new level is a genuine raise in verbosity, the command
prints a warning to stderr and still exits 0.

One blocking handler:

```
WARN: org.perfmon4j is now TRACE, but handler CONSOLE is at INFO — TRACE and
      DEBUG records from this logger will not reach the console.
      To see them, also lower the handler:
          logctl handler CONSOLE TRACE
```

More than one:

```
WARN: org.perfmon4j is now TRACE, but 2 handlers on its path are above that
      level and will drop these records:
          logctl handler CONSOLE TRACE     (currently INFO)
          logctl handler FILE TRACE        (currently INFO)
      Run the ones whose output you want — e.g. just CONSOLE for local viewing.
```

The per-handler command uses the triggering level as its suggested argument, but
the developer is free to pick another. The warning does **not** change any
handler and never fails the level command — printed to stdout after the
confirmation line in text mode, and as a `warnings[]` array alongside the
override in `--json` (empty when there's nothing to warn about). No `--quiet`
flag exists anywhere in `logctl` today to suppress it with; adding one is
out of scope here.

## Operations impact

New operations on the core service (and the JMX surface, per `level-control.md`'s
"JMX first, `logctl` later" convention):

```
setHandlerLevel(name: String, level: Level, options: SetHandlerLevelOptions) -> HandlerLevelOverride?
resetHandler(name: String) -> void

SetHandlerLevelOptions {
    reason: String?
    expiresIn: Duration?
    tier: PersistenceTier = SESSION
}
```

`setHandlerLevel` returns the override it created, or nothing at all — not an
error — when the adapter has no handler levels to set (see "Logback / none"
below). That empty case is a first-class outcome, not a null slipping through:
it means no capability check ran, nothing was mutated, tracked, or persisted.

Validated at construction exactly as `SetLevelOptions`: `tier == FOR` iff
`expiresIn` is non-null and positive.

`resetAll()` is unchanged in signature; its behaviour extends to also revert every
active handler override.

`logctl status` / `--json` shows active handler overrides (name, level, tier,
expiry) straight from the `HandlerLevelOverride` registry — no new adapter call
needed, since only overridden handlers are tracked. A full `logctl status
--handlers` listing of *every* handler (overridden or not, with baseline/current
level) and a `blockingHandlers` echo of the warning on `LoggerInfo` are deferred
past this slice — **Open decision A, resolved: defer.**

## Adapter SPI

The base `LoggingAdapter` SPI gains:

```
/** Whether this adapter's handlers have a level of their own at all.
 *  Default false. Gates every other method below: core checks this before
 *  doing anything else, so "no handler levels" (Logback, none) and "a real
 *  handler whose level happens to be unset" are never confused with each
 *  other via a shared null/empty return. */
boolean hasHandlerLevels();

/** The handler's current level, or empty if it has none set. Only
 *  meaningful when hasHandlerLevels() is true. */
Optional<Level> handlerLevel(HandlerRef ref);

/** Set the identified handler's level, returning its prior level (empty if
 *  it had none). Throws UnknownHandlerException if no handler resolves to
 *  ref in any managed context. Only called when hasHandlerLevels() is true. */
Optional<Level> setHandlerLevel(HandlerRef ref, Level level);

/** Every handler currently resolvable. Used to validate a `logctl handler
 *  <name>` argument and list known names on a mismatch; the fuller
 *  status/discovery listing this could also back is deferred (Open decision A). */
List<HandlerRef> knownHandlers();
```

`hasHandlerLevels()` is the one core actually branches on — a handler-floor
warning or a `setHandlerLevel` call short-circuits to "nothing to do" the
moment it's false, before touching capability, baseline, or registry state
for that context.

`handlerFloorsBelow(String loggerName, Level target)` already exists
(`wildfly-support.md` Slice 2); its returned `HandlerFloor` is extended to carry
the stable `HandlerRef` (below) and the handler's current level, so core can build
the warning without a second call.

`HandlerRef` is **the configured handler name** when the framework exposes one,
falling back to a stable identity token (`<class-simple-name>@
<identityHashCode-hex>`) for an anonymous handler. Core keys everything on
`(contextKey, HandlerRef)`. *(Open decision #1, resolved: configured name,
identity-token fallback.)*

The JBoss LogManager adapter implements these against `Logger.getHandlers()` /
`Handler.getLevel()` / `Handler.setLevel()` (all JDK) and reports
`hasHandlerLevels() = true`. Logback and `none` keep the SPI's defaults
(`false` / empty / empty) — they implement none of this.

**Name resolution against real WildFly: tried, doesn't work.** The adapter
reflects into `org.jboss.logmanager.configuration.ContextConfiguration` (JBoss
LogManager 3.x's own declarative-config API, attached to a `LogContext`'s root
logger) to recover the configured name. Confirmed against a real standalone
WildFly 26.1.3.Final (`WildFlyContainerIT`): the attachment is never present —
WildFly's logging subsystem manages handlers entirely through its own
management model (`/subsystem=logging/console-handler=CONSOLE`, an
MSC-service-backed resource address), not through JBoss LogManager's
declarative config API, so there is nothing there to reflect into. Every
handler therefore resolves to its identity-token fallback in practice, not the
friendly name — `CONSOLE`/`FILE` are never what a user actually types. The
fallback is still fully functional (stable per agent lifetime, and named
verbatim in every warning's suggested command, so it is copy-pasteable even
though it isn't pretty), so the feature works end to end regardless; a real
fix would mean teaching `logaperture-container-wildfly` (which already knows
WildFly specifically, unlike this generic JUL adapter) to resolve names some
other way — e.g. by correlating handler instances against the management
model over the local management interface — which is real, separate,
deferred work, not a follow-up to this reflection attempt.

Two independent bugs surfaced by that same real-WildFly run, both fixed before
the above finding was even reachable:

- **Wrong classloader.** `Class.forName("org.jboss.logmanager.Logger")`
  resolves against *this adapter's own* classloader. Under JBoss Modules that
  is not the loader that actually has `org.jboss.logmanager` on its path, so
  the lookup threw `ClassNotFoundException` even though `root` was plainly an
  instance of exactly that class. Fixed by never asking for that class by
  name: `root.getClass()` already *is* the correct runtime `Class`, and every
  further reflective lookup (`ContextConfiguration`, its attachment key) goes
  through `root.getClass().getClassLoader()` instead of the caller's own.
- **Cold lookup.** `setHandlerLevel`/`handlerLevel` only ever consulted a
  cache that `handlerFloorsBelow`/`knownHandlers` populated as a side effect
  — so `logctl handler <name> <level>` typed as literally the first command
  against a fresh agent (nothing yet triggered that populate step) always
  threw `UnknownHandlerException`, for a real, resolvable handler. Fixed:
  a cache miss now falls back to walking every known handler once (populating
  the cache as it goes) before giving up.

Both are exactly the kind of gap that in-process unit tests — which always
called `handlerFloorsBelow` first to obtain a ref, then used that same ref —
could not have caught; only the real cross-process, real-server run did.

## Semantics to pin down

- **Baseline capture.** Kept out of `HandlerLevelOverride` itself, mirroring how
  a logger's baseline lives in `BaselineRegistry` rather than in `LevelOverride`:
  a parallel `HandlerBaselineRegistry` captures each handler's pre-LogAperture
  level, lazily, the first time `setHandlerLevel` touches it. `resetHandler`,
  `resetAll`, and the expiry sweep restore from that registry, never from the
  override. A later `setHandlerLevel` on the same `(contextKey, ref)` while an
  override is active *supersedes* — the registry entry it was captured from
  doesn't change on a second touch, so reversion still lands on the
  pre-LogAperture value regardless of how many times the override was replaced.
- **Independent lifetime.** A handler override is its own thing. Nothing about a
  logger override creates, extends, or reverts it, and vice versa. Its tier,
  `expiresAt`, reason and audit trail are its own.
- **No overlap recomputation.** Because there is exactly one override per
  `(contextKey, ref)`, there is no "two overrides need this handler" case and no
  reference counting — superseding replaces, resetting restores the baseline.
- **Direction.** `level` below the handler's current level is a *lower* (needs
  `handler.lower`); above it is a *raise* (needs `handler.raise`). Equal is a
  no-op with a note. `ALL` / `OFF` are valid targets.
- **Squelch warning (raise direction).** Raising a handler's floor above the
  level of one or more **currently-active logger overrides** that route through it
  would silence output the operator explicitly asked for. Whether `logctl handler
  <name> <stricter>` warns about that, and in how much detail, is **Open decision
  B.**
- **Multi-context (WildFly).** A `HandlerRef` name is resolved in every managed
  `LogContext`. `setHandlerLevel` applies to each context that has a matching
  handler, capturing a baseline per `(contextKey, ref)`, following
  `wildfly-support.md`'s broadcast semantics. A context that reappears after
  redeploy has the override re-applied to it.
- **Reconfiguration re-application.** `HandlerLevelControlService.verifyAndReapply`
  is the `LevelControlService.verifyAndReapply` counterpart for handlers, and
  `AggregateLevelControl.verificationSweep` runs it across every context
  alongside the logger sweep — so `WildFlyContainer`'s existing periodic sweep
  and its `LogManager` configuration-change hook cover handler drift too, with
  no changes needed in `WildFlyContainer` itself (it already just calls
  `aggregate.verificationSweep(now)`). A handler override also survives
  **redeploy** (broadcast onto a newly-registered context, same as a logger
  override) and **resume** (below).
- **Resume.** A persisted `--for` / `--sticky` handler override is re-applied on
  agent startup like a logger override (`persistence.md` §6.5). A `--for` one
  whose `expiresAt` has passed is written straight to the audit as a `REVERSION`
  and not applied. A handler that no longer exists is dropped with a diagnostic.
- **Logback / `none`.** `hasHandlerLevels()` is `false`; `setHandlerLevel`
  returns nothing (no capability check, no mutation, no tracking, no persist)
  and `logctl handler` reports "this framework's handlers have no level of
  their own; nothing to change" and exits 0. Confirmed against a real
  cross-process run (`LevelControlEndToEndIT`), not just in-process.

## Data model

```
HandlerLevelOverride {
    handlerRef: HandlerRef    // configured name, or <class>@<idhash> fallback
    level: Level              // current target
    reason: String?
    appliedAt: Instant
    source: String
    tier: PersistenceTier
    expiresAt: Instant?       // non-null iff tier == FOR
}
```

Exactly `LevelOverride`'s own field set with `loggerName`/`includeChildren`
swapped for `handlerRef` (no children to include) — no `previousLevel` here
either, for the same reason `LevelOverride` has none: the baseline lives in its
own registry (`HandlerBaselineRegistry`), not on the override. One
`HandlerOverrideRegistry` per logging context (mirroring `OverrideRegistry`)
holds these, keyed by `handlerRef` — multi-context scoping is which
context's registry an entry lives in, not a field on the record, same
convention `LevelOverride`/`OverrideRegistry` already use. Persisted to the
state file when `tier != SESSION`, in its own section alongside the logger
overrides (`persistence.md` state-file
shape), so a `--sticky` handler override survives an agent restart.

## Capability and audit

- **New capabilities `handler.lower` and `handler.raise`** (§9.3), mirroring
  `level.lower` / `level.raise`. `setHandlerLevel` checks the one matching the
  direction of the change **before** any mutation. `resetHandler` always
  requires `handler.lower` — the same simplification `resetLevel` makes
  (`level-control.md`): every reset is treated as "get back to normal"
  regardless of whether reverting to baseline happens to raise or lower this
  particular handler, rather than judging direction per reset. `persist` is
  additionally required when `tier != SESSION`, exactly as for `setLevel`
  (`persistence.md` §9). Rationale for keeping these separate from `level.*`: a
  handler floor governs **every** logger that routes through that handler, so its
  blast radius is wider than a single-logger level change — a reviewer approving
  "let support raise a logger for 30 minutes" is not thereby approving "let
  support widen the console sink for the whole server".
- **Audit** (§9.7 fields) on every `setHandlerLevel` and every reversion:
  principal, source, `contextKey`, `handlerRef`, previous level, new level,
  `reason`, `tier`, `expiresAt`. One record per handler per context. Reversions
  carry `source` = `reset` / `expiry` / `resetAll` / `resume`.

## Failure handling

- `setHandlerLevel` on a handler whose `Handler.setLevel` throws: the exception is
  logged to the agent's diagnostic writer, the override is **not** recorded, and
  the command reports the failure (exit non-zero). No partial state.
- In multi-context, a per-context `setLevel` that throws is logged and that
  context skipped; the contexts that succeeded keep their override and the command
  reports which contexts failed.
- The expiry sweep and `resetAll` never throw out to a caller — a handler that has
  since vanished (context torn down) is dropped from tracking with a diagnostic.
- Capability withheld: `CapabilityDeniedException(HANDLER_LOWER | HANDLER_RAISE)`
  before any change, same as `level.*`; the command exits 6 naming the capability.

## Testing

**Unit — JBoss LogManager adapter (in-process, no WildFly):**

- A `ConsoleHandler` named `CONSOLE` at `INFO` on the root: `setHandlerLevel(ref,
  TRACE)` returns `INFO` and the handler is now `TRACE`; calling again with
  `TRACE` is idempotent and still returns `INFO` as prior only on the first call.
- `handlerFloorsBelow("x", TRACE)` returns a `HandlerFloor` carrying the `CONSOLE`
  ref and `currentLevel = INFO`.
- Name resolution: no `org.jboss.logmanager` on this test's classpath (by
  design — see `JulLoggingAdapterTest`'s own class doc), so every handler here
  resolves to its `<class>@<idhash>` identity-hash fallback, confirmed still
  settable and revertible by that token. Resolving the real configured name is
  JBoss-LogManager-specific and, per the real-WildFly finding above, doesn't
  actually happen there either — so there is no environment, in-process or
  real, where the friendly-name path is exercised today.
- `setHandlerLevel`/`handlerLevel` resolve a ref **cold** — with nothing having
  called `handlerFloorsBelow`/`knownHandlers` for that handler yet in this
  adapter instance's lifetime — not only a ref freshly returned by one of
  those calls (the cold-lookup bug above).
- `knownHandlers()` lists them.

**Unit — core / CLI:**

- `setHandlerLevel` records a `HandlerLevelOverride` with the captured baseline;
  `resetHandler` restores it and writes a `REVERSION` audit record.
- Supersede: `CONSOLE→DEBUG` then `CONSOLE→TRACE`, then `reset` → handler is back
  at the original `INFO`, not `DEBUG`.
- Expiry: `CONSOLE TRACE for 1s` reverts on the sweep with an `expiry` audit
  record.
- `resetAll` reverts both a logger override and a handler override in one call.
- Tier: `CONSOLE TRACE sticky` is written to the state file; a simulated resume
  re-applies it; an expired `--for` one is not applied but is audited.
- Capability: `handler.lower` withheld → `logctl handler CONSOLE TRACE` denied,
  exit 6, no handler change and no override recorded; `handler.raise` withheld →
  `logctl handler CONSOLE WARN` denied; `resetHandler` needs `handler.lower` too
  (withheld → denied, same as `resetLevel`).
- `persist` withheld → `CONSOLE TRACE for 30m` denied (per `persistence.md` §9).
- Warning: `logctl trace x` with a `CONSOLE` floor prints the single-handler
  warning and exits 0; with two floors prints the multi-handler form; `--json`
  puts them in `warnings[]`; the level change happens regardless.
- Unknown handler name → the command fails with the adapter's own message
  (`unknown handler: <ref>`) and a non-specific exit code — `logctl` has no
  dependency on `core` to catch `UnknownHandlerException` by type and hand it a
  dedicated exit code the way `CapabilityDeniedException` gets one; naming every
  known handler in that message needs the discovery listing Open decision A
  deferred.
- Logback adapter: `logctl handler CONSOLE TRACE` → exit 0, "nothing to change"
  note, no override recorded — confirmed both in-process and cross-process
  (`LevelControlEndToEndIT`, a real agent + real Logback process + real JMX null
  return for the composite result type).

**Cross-process (extends `LevelControlEndToEndIT` / `WildFlyContainerIT`):**

- `LevelControlEndToEndIT` (Logback fixture): exercises `logctl handler`'s
  no-op path end-to-end — real agent, real process, real JMX.
- `WildFlyContainerIT`, against real standalone WildFly 26.1.3.Final:
  `logctl trace <logger>` prints the handler-floor warning; the handler it
  names resolves to its identity-token fallback, not `CONSOLE` — confirmed,
  not just "unverified" (see "Adapter SPI" above for what was tried and why it
  doesn't pan out against WildFly's actual handler-management model), and the
  warning's suggested command uses that same token, so it is still directly
  copy-pasteable. `logctl handler <that-token> TRACE` then makes a real
  `FINEST`/TRACE-level line actually reach the console's captured output
  (previously invisible at the handler's default `INFO` floor, logger already
  raised); `logctl handler <that-token> reset` reverts it, confirmed by a
  further redeploy adding no new occurrence.
- Not yet covered: `logctl handler <name> ... sticky` surviving a **redeploy**
  specifically (the broadcast-onto-a-new-context path is unit-tested with fake
  multi-context setups, not yet exercised against a real WildFly redeploy —
  though note stock WildFly's single shared system context, per the M0
  finding, means a WAR redeploy never actually creates a new context to
  broadcast onto in practice). Surviving a `/subsystem=logging` change or
  `:reload` (drift, not redeploy) now has the mechanism (`verificationSweep`
  covers handlers, per "Reconfiguration re-application" above) but no
  dedicated real-WildFly test exercising it yet, parallel to
  `managementCliLoggingChange_isCorrectedByTheVerificationSweep`'s logger one.

## Open decisions (sign-off)

*Resolved during review:* handler identity is the configured name with a
`<class>@<idhash>` fallback (was #1 — confirmed against real WildFly that the
fallback is what actually fires in practice; see "Adapter SPI" above); the handler is set to exactly the level the
user names (was #2 — the user names it directly now); capability follows the same
tier rule as loggers — `handler.lower`/`handler.raise` plus `persist` when
`tier != SESSION` (was #3); `includeChildren` does not apply (was #4 — no coupling
to a logger override); the confirmation/warning names every blocking handler
inline, one actionable command each (was #5), which the user called out as a
feature, not a cost.

- **A. `logctl status --handlers` / `listHandlers()`.** Resolved: **defer.** The
  warning message already gives a developer every handler name they need to act;
  ship without a standalone listing and pick it up in a follow-up (possibly
  alongside `doctor`'s fuller rendering). `knownHandlers()` stays in the adapter
  SPI — it backs `logctl handler`'s unknown-name error, independent of any status
  listing.
- **D. Ship both directions now, or lower-only first?** Resolved: **both.** The
  user's own framing of this feature ("reset the level of an appender up or
  down") settles it — `handler.lower` and `handler.raise` both ship this slice, as
  written above.
- **B. Squelch-direction warning.** Resolved: **defer.** `logctl handler <name>
  <stricter-level>` ships (per D) without warning about currently-active logger
  overrides it might silence — that symmetry with the lower-direction warning is a
  cheap, self-contained follow-up once the base command is in, not a reason to
  hold up this slice.
- **C. `--session` handler overrides and the state file.** Resolved: **treat
  identically to `--session` loggers** — no special-cased louder confirmation
  line. Revisit only if this proves confusing in practice.

## Exit criterion

Against a plain `java -jar` + Logback process and a standalone WildFly — met,
using whatever identifier the console handler actually resolves to (its
identity-token fallback in practice, per the finding above — `CONSOLE` below
stands for that):

- `logctl trace <logger>` on WildFly raises the logger and prints a warning
  naming the `CONSOLE` handler and the command to clear it; the logger reverts on
  its own tier.
- `logctl handler CONSOLE TRACE for 10m` lowers the `CONSOLE` handler, a `TRACE`
  line appears on the console, and the handler reverts when the 10 minutes elapse
  — with an audit record for each direction.
- `logctl handler CONSOLE reset` reverts it immediately; `logctl reset --all`
  reverts a handler override alongside logger overrides.
- `logctl handler CONSOLE DEBUG sticky` survives a WildFly redeploy with the
  handler still lowered — the mechanism is unit-tested with fake multi-context
  setups (`addContext` rebroadcast); not yet exercised as a real-WildFly
  redeploy scenario (see "Cross-process" under Testing).
- The same command on the Logback process succeeds with the "nothing to change"
  note and changes no appender — met, and confirmed cross-process
  (`LevelControlEndToEndIT`).
- `handler.lower` withheld makes `logctl handler CONSOLE TRACE` exit 6 naming the
  capability, with nothing changed.
