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
cosmetic friendly name. A code-review pass after the initial implementation
found `logctl status` was not actually rendering active handler overrides
despite this spec's own Functional summary promising it — fixed (`logctl
status`/`--json` now list them; see "Testing" below) along with several other
findings (a `Handler#setLevel(null)` NPE on a first-capture-fails baseline, a
capability-direction default that silently demanded `handler.lower` even to
raise, and a `--reason` parser gap on `handler <name> reset`).

**Follow-up (issue [#13](https://github.com/ddeuchert/logaperture/issues/13),
implemented on branch `feature/13-all-handlers`):** the identity-token
fallback above is stable per agent lifetime but not across a WildFly restart,
since a JVM identity hash carries no such guarantee. `ALL_HANDLERS`, a
reserved `HandlerRef` meaning "every handler this context can log to,"
replaces per-handler addressing on WildFly with a target that's stable by
construction — design reviewed and signed off 2026-09-05 (all 8 decisions),
folded into "Adapter SPI", "Semantics to pin down", "Capability and audit",
and "Open decisions" below, then implemented the same day: `knownHandlers()`/
`realHandlers()` split, the core-side fan-out in
`HandlerLevelControlService`, the collapsed blocking-handler warning in the
JUL adapter's `handlerFloorsBelow`, and `JbossHandlerNames`'s removal.
Verified by unit tests (`HandlerLevelControlServiceTest`,
`JulLoggingAdapterTest`) and confirmed against a real standalone WildFly
26.1.3.Final (`WildFlyContainerIT`, 7/7 passing, including both rewritten
`ALL_HANDLERS` tests). Real per-handler WildFly names
([#14](https://github.com/ddeuchert/logaperture/issues/14), alpha-1) are
**signed off, not yet implemented** — see "WildFly handler name resolution" below.

**Planned extension (issue [#28](https://github.com/ddeuchert/logaperture/issues/28),
alpha-2).** A well-known `DEFAULT_HANDLERS` logical ref alongside
`ALL_HANDLERS` (user-assigned members, persisted sticky) and an additive
`logctl debug <logger> --to <group>` delivery target are still spec-section-
not-yet-written — see #28 for the full three-piece workflow. The **`AUTO`
handler level** ([#20](https://github.com/ddeuchert/logaperture/issues/20))
piece is specced below ("AUTO handler level"), scoped deliberately narrower
than #28's own framing: AUTO works on any `HandlerRef` you can already name
(a real handler, or `ALL_HANDLERS`) with no dependency on `DEFAULT_HANDLERS`
existing yet — a named group is simply one more valid `HandlerRef` once #28
lands, so nothing here needs rework when it does. **Status: implemented,
unit-tested** — see "AUTO handler level" below for the full account.

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
- On WildFly, where individual handlers can't be named at all (see the Status
  note above), target every handler at once with the reserved name
  `ALL_HANDLERS` — `logctl handler ALL_HANDLERS TRACE` lowers every real
  handler in one command, and `logctl handler ALL_HANDLERS reset` reverts each
  one to its own prior level, not to a single shared value. The blocking-handler
  warning on WildFly now names `ALL_HANDLERS` with one command to run, instead
  of one unstable per-handler token per blocker.
- Put a handler into a self-adjusting mode — `logctl handler CONSOLE AUTO` —
  so its floor automatically tracks the lowest currently-active `logctl
  debug`/`trace`/`set` override, dropping the instant one starts and rising
  back to `CONSOLE`'s own baseline the instant none are left. No more
  manually lowering the handler every time a logger is raised, and no more
  remembering to raise it back afterward.
- Give `AUTO` the same lifetime tokens as any handler override —
  `logctl handler CONSOLE AUTO sticky`, `logctl handler ALL_HANDLERS AUTO for
  2h` — controlling how long the *AUTO mode itself* lasts, independent of how
  often the level it tracks actually moves during that time.
- See a handler's `AUTO` status and the level it's currently tracking in
  `logctl status` / `--json` and `logctl handlers`.
- Switch an `AUTO` handler back to a fixed level, or reset it, exactly as
  today — `logctl handler CONSOLE INFO` or `logctl handler CONSOLE reset`
  simply supersedes/reverts it, the same as it would a fixed override.

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
- A reserved handler target `ALL_HANDLERS` (issue #13) that fans out
  `setHandlerLevel` / `resetHandler` over every real handler an adapter can
  act on right now — the mechanism WildFly needs, since individual handler
  names aren't reliably addressable there (identity-hash tokens, unstable
  across a restart; see the Status note above and "Adapter SPI" below). Off
  WildFly it's purely additive: real handlers stay individually addressable,
  and `ALL_HANDLERS` is simply one more valid name alongside them.
- An `AUTO` handler level (issue #20) — a handler mode, not a fixed `<level>`
  value, whose applied level is recomputed to track the lowest currently-active
  logger override in the same context, reverting to the handler's native
  baseline once none remain. See "AUTO handler level" below.

**Explicitly out of scope** (deferred, each with what it needs):

- Formatter / filter changes on a handler (render-stage wrapping —
  `wildfly-support.md` "Out of scope").
- Logback appender **filter** manipulation (`ThresholdFilter` / `LevelFilter`) to
  simulate a handler level — a separate feature; here Logback is a no-op.
- `doctor`'s full logger-vs-handler-vs-sink rendering (still deferred).
- A precedence model between a handler override and a logging-config reload that
  *changes* the handler's configured level (as opposed to resetting it) — beyond
  what `persistence.md` §6.6 already says for loggers.
- Naming handlers that are **not** on any logger's resolved path — `logctl
  handler` addresses only what a logger can reach, individually or via
  `ALL_HANDLERS`.
- Generalized named handler groups beyond the one reserved `ALL_HANDLERS`
  name — revisit only if a real second grouping shows up.
- Real per-handler WildFly names (issue #14, sequenced after `ALL_HANDLERS`
  lands; **now signed off** — see "WildFly handler name resolution" below) —
  `ALL_HANDLERS` is this slice's fix for *addressing* WildFly's handlers, not
  for naming each one individually.
- A well-known `DEFAULT_HANDLERS` logical group and `logctl debug <logger>
  --to <group>` delivery targeting (issue #28) — `AUTO` (below) works on any
  `HandlerRef` you can already name; a named group is simply one more valid
  `HandlerRef` once #28 lands, needing no rework here.

## The operation

`logctl handler <name> <level> [<tier token>] [--reason <text>]`

```
logctl handler CONSOLE TRACE
logctl handler CONSOLE DEBUG for 30m --reason INC-42
logctl handler CONSOLE ALL sticky
logctl handler FILE WARN                 # squelch: raise the FILE handler's floor
logctl handler CONSOLE reset             # revert now
logctl handler ALL_HANDLERS TRACE        # every real handler at once (WildFly)
logctl handler ALL_HANDLERS reset        # each reverts to its own baseline, not one shared value
```

- `<name>` is the configured handler name as the framework knows it — WildFly's
  `CONSOLE`, `FILE` — or the reserved name `ALL_HANDLERS`, which fans out over
  every handler `adapter.realHandlers()` returns (see "Adapter SPI"). It is
  what the warning message prints, so a developer never
  has to guess it. An unknown name fails the way any unreachable adapter target
  does — reported by the message the adapter's `UnknownHandlerException` carries
  — rather than a dedicated exit code enumerating known names; that enumeration
  needs the discovery listing Open decision A deferred, and revisits this when
  it lands. On WildFly, where `knownHandlers()` advertises only
  `ALL_HANDLERS`, addressing a real handler's own token directly (e.g. one
  copied from an old log line or audit record) hits this exact same
  unknown-handler error — no dedicated exception type for "used to be known";
  from the addressable surface's point of view it genuinely isn't known
  anymore (Decision #6).
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

**On WildFly, the multi-handler form collapses to one `ALL_HANDLERS` line**
(Decision #7). Today's per-handler warning is deliberately granular so a
developer can lower just `CONSOLE` and leave `FILE` alone — but once
`knownHandlers()` advertises only `ALL_HANDLERS`, that per-name granularity
no longer exists to offer. `handlerFloorsBelow` is already computed
per-adapter; the WildFly adapter's implementation collapses its own answer to
one `HandlerFloor` naming `ALL_HANDLERS`, at the strictest level among the
real handlers actually blocking:

```
WARN: org.perfmon4j is now TRACE, but handler ALL_HANDLERS is at INFO — TRACE
      and DEBUG records from this logger will not reach any sink.
      To see them, also lower the handler:
          logctl handler ALL_HANDLERS TRACE
```

Zero changes to core, the JMX surface, or `Commands` — the collapsing is
entirely the WildFly adapter's own answer to a question it already answers.
The known cost: the "just lower CONSOLE, leave FILE alone" granularity is
genuinely lost on WildFly until issue #14 lands real per-handler names.

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
needed, since only overridden handlers are tracked. The full catalog of *every*
handler is **`logctl handlers`** — see "The handler catalog" below (this is
Open decision A, originally deferred, delivered with issue #14 since #14 is what
makes the names on it worth reading). A `blockingHandlers` echo of the warning
on `LoggerInfo` is still deferred.

## The handler catalog

`logctl handlers` (issue [#15](https://github.com/ddeuchert/logaperture/issues/15))
— the read-only counterpart to `logctl levels`, for handlers. One row per name
the adapter advertises as addressable (`knownHandlers()`): on WildFly
`ALL_HANDLERS` plus every handler whose configured name has resolved (issue #14);
on plain JUL every real handler plus `ALL_HANDLERS`.

```
$ logctl handlers
HANDLER       LEVEL  PERSISTS  TARGET                                        OVERRIDE
ALL_HANDLERS  —      —         —                                             —
CONSOLE       INFO   no        —                                             —
FILE          DEBUG  file      /opt/jboss/wildfly/standalone/log/server.log  DEBUG (FOR, reverts in 27m)
```

No target, no tier, nothing to confirm; `--json` mirrors the rows. `VIEW` only,
no audit — same shape as `doctor` / `top` / `levels`. It answers a different
question from `logctl status`: `status` is "what has LogAperture changed";
`handlers` is "what handlers exist and what are they set to."

**Data model** — `HandlerInfo` in `api` (the `LoggerInfo` counterpart), one per
`knownHandlers()` entry: `ref`, current `level` (reflecting any active override;
`null` for `ALL_HANDLERS`, which is not a live handler), the static facts
`doctor` already reads via `handlerDiagnostics` (`persistent`, `targetPath`,
`autoFlush`), and the active override's `level` / `tier` / `expiresAt` if any.
`AggregateLevelControl.listHandlers()` merges every context's rows and stamps
each with its context key; `logctl handlers` shows a `[context]` prefix only
when the result spans more than one, exactly like `levels` / `doctor`.

On an adapter whose handlers have no level of their own (Logback, `none`),
`listHandlers()` is empty and `logctl handlers` prints a one-line note.

**On WildFly during boot** — before name resolution succeeds, `knownHandlers()`
is `[ALL_HANDLERS]` only, so `logctl handlers` shows just that one row; run again
a second or two later and `CONSOLE` / `FILE` appear. That transition is itself
the quickest way to confirm #14's resolution is working.

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

/** Every handler currently *addressable* by a user. Used to validate a
 *  `logctl handler <name>` argument and list known names on a mismatch; the
 *  fuller status/discovery listing this could also back is deferred (Open
 *  decision A). May include the reserved HandlerRef.ALL_HANDLERS as a
 *  member, or — WildFly under JBoss LogManager — return List.of(ALL_HANDLERS)
 *  alone, suppressing the real handlers entirely (issue #13, Decision #1). */
List<HandlerRef> knownHandlers();

/** Every real handler this adapter can act on right now — what
 *  ALL_HANDLERS fans out over, and what a per-handler baseline is captured
 *  against. Unlike knownHandlers(), never collapsed or suppressed: an
 *  adapter that hides its reals from the user still needs this list
 *  internally. Default: same as knownHandlers(). (issue #13, Decision #1) */
List<HandlerRef> realHandlers();
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

**`ALL_HANDLERS` is a third, reserved `HandlerRef` value** (issue #13), distinct
from both the configured-name and identity-token forms above — it never
refers to a handler instance, which is exactly why it's stable across a
restart where the other two forms aren't. `HandlerLevelControlService`
recognizes it as a target and fans out over `adapter.realHandlers()`,
running the existing single-ref path (capability check, baseline capture,
apply, audit) once per real handler — reusing `LevelControlService.setLevel`'s
own `includeChildren` fan-out pattern rather than inventing a second mutation
path. **The fan-out lives in core, not the adapter** (Decision #2): an
adapter that looped internally could only report one before/after `Level`
pair for the whole group, which breaks the moment two real handlers start at
different levels and need their *own* baselines restored on reset. Core
instead reuses `HandlerBaselineRegistry`'s existing per-ref semantics
directly.

The JBoss LogManager adapter implements these against `Logger.getHandlers()` /
`Handler.getLevel()` / `Handler.setLevel()` (all JDK) and reports
`hasHandlerLevels() = true`. Logback and `none` keep the SPI's defaults
(`false` / empty / empty) — they implement none of this. `WildFlyContainer`'s
adapter reports `knownHandlers() = List.of(ALL_HANDLERS)` while
`realHandlers()` still lists every actual handler underneath, unchanged.

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

**This reflection path is retired outright, not patched (Decision #8,
issue #13).** Its entire purpose was resolving a real WildFly handler's
friendly name — no longer attempted anywhere once WildFly's
`knownHandlers()` stops advertising real refs at all and returns
`List.of(ALL_HANDLERS)`. `JbossHandlerNames` is deleted rather than adapted
to a dangling `invalidate()` caller; issue #14's real fix will be built
fresh against WildFly's management API, not by resurrecting this class.

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

## WildFly handler name resolution (issue [#14](https://github.com/ddeuchert/logaperture/issues/14))

Status: **sign-off complete** (alpha-1) — all nine decisions resolved and
folded into the prose below. Not yet implemented.

The retired reflection path (see "Adapter SPI" above) failed because WildFly
does not drive JBoss LogManager through its declarative `ContextConfiguration`
— it manages every handler as its own MSC-service-backed management resource
(`/subsystem=logging/console-handler=CONSOLE`). This section resolves names
from *that* model instead, so `logctl handler CONSOLE TRACE`,
`logctl handler SIF INFO`, and the blocking-handler warning's per-handler
granularity all work on WildFly by the name an operator reads in
`standalone.xml`.

**After this feature, on WildFly the user will be able to:**

- Address an individual handler by its configured name — `logctl handler FILE`,
  `logctl handler API-REQUESTS`, `logctl handler SIF` — not only `ALL_HANDLERS`.
- See friendly names, not `PeriodicRotatingFileHandler@1a2b3c` tokens, in the
  blocking-handler warning, in `logctl status`, and in `doctor` / `top` output.
- Floor a dedicated handler (`logctl handler SIF INFO`) so an eval-time level
  bump on some category can't leak into that log.

`ALL_HANDLERS` (issue #13) stays exactly as-is — this is an **addition** on top
of a working baseline, not a replacement.

### The hard constraint: in-VM only

The agent already runs *inside* the WildFly JVM. Name resolution uses that and
nothing else:

- **No socket.** The management port (`9990` / native) is never opened or
  connected to. §8.2's "opens no network connections" property is preserved
  verbatim — unlike the Hawtio case (§18.3) there isn't even someone else's
  port involved.
- **No credentials.** No `$local` auth handshake, no management user. An
  in-process caller of the server's own `ModelController` / MSC registry is
  already inside the trust boundary the OS UID gate (§9.8) defines.
- **No new runtime dependency.** Everything is reflective, `root.getClass()
  .getClassLoader()`-anchored, exactly like the existing config-change-listener
  wiring in `WildFlyContainerIntegration` — no `wildfly-controller-client` or
  `jboss-msc` on the compile path.
- **Best-effort.** Any handler that doesn't resolve keeps its identity-token
  ref and stays non-addressable; a total failure degrades to today's
  `ALL_HANDLERS`-only behaviour with a diagnostic, never an error.

### Architecture: a resolver seam, generic adapter unchanged

`JulLoggingAdapter` stays the *generic* JUL adapter — no `org.jboss.msc` /
`org.jboss.as.*` knowledge, mirroring its existing "no compile-time reference
to any `org.jboss.logmanager` class" discipline.

A new SPI type — `HandlerNameResolver` — is injected into the adapter by its
factory:

```
/** Resolves live handler instances to their framework-configured names.
 *  doc/specs/handler-floor-control.md, issue #14. */
public interface HandlerNameResolver {
    /** Configured names for as many of `handlers` as can be resolved right
     *  now; absent entries keep the caller's identity-token fallback. A
     *  bulk call so an implementation can do one model read, not one per
     *  handler. Never throws — an implementation that can't resolve
     *  anything returns an empty map. */
    Map<Handler, String> resolve(List<Handler> handlers);

    /** The no-op resolver: resolves nothing. The default for plain JUL. */
    HandlerNameResolver NONE = handlers -> Map.of();
}
```

- `JulAdapterFactory.forCurrentContext()` gains an overload taking a
  `HandlerNameResolver`; the no-arg form passes `NONE`. `NoneContainer` uses
  `NONE`; `WildFlyContainerIntegration` passes a `WildFlyHandlerNameResolver`
  from `logaperture-container-wildfly`.
- `JulLoggingAdapter.refFor(Handler)` becomes: consult a cached
  name-by-instance map; on a miss, `HandlerRef` is the resolved name if present
  else `HandlerRef.anonymous(handler)` — today's exact behaviour when the
  resolver is `NONE`.

### Resolution mechanism

`WildFlyHandlerNameResolver` runs entirely in-VM. As-built and verified against
real WildFly 26.1.3.Final (`WildFlyContainerIT`):

- **Names — in-VM `ModelController` read.** Obtain the server's
  `ModelController` from the MSC `ServiceContainer` (service `jboss.as.server-controller`),
  `createClient()` (in-VM, no socket, no `$local` handshake, no credentials),
  and execute `read-children-names` under `/subsystem=logging` for each handler
  resource type (`console-handler`, `file-handler`, `periodic-rotating-file-handler`,
  `size-rotating-file-handler`, `periodic-size-rotating-file-handler`,
  `syslog-handler`, `custom-handler`). The model is the sole source of the
  configured name.
- **Instance binding — by handler shape, then configured file name.** A
  `console-handler` name binds to the sole console `Handler` instance; a
  file-type name binds to the sole file instance, or — with more than one — by
  matching the model's `file.path` leaf against `FileHandler.getFile()`.

**The MSC service-name walk that Decision #1's "hybrid" first proposed for
instance binding was dropped: real WildFly 26.1.3 registers no
logging-*handler* MSC services at all** (the logging subsystem drives the
`LogContext` directly), so there was nothing to walk. Names come purely from
the management model; instances are matched by shape as above. This is the
"version-specific surprise" the sign-off explicitly left the IT to surface.

Two reflection disciplines the IT forced, both classic JBoss-Modules traps:

- **Load through the boot module loader.** `org.jboss.modules.Module` is the
  one WildFly class on the system class path; `getBootModuleLoader()
  .loadModule("org.jboss.as.server").getClassLoader()` is the loader that can
  then see `CurrentServiceContainer`, `ModelController`, `ModelControllerClient`
  and `org.jboss.dmr.ModelNode`. The agent's own loader cannot (the retired
  attempt's first bug).
- **Invoke through the public interface, never `getClass()`.** `ModelControllerImpl`,
  `ServiceContainerImpl`, `ModelControllerClientImpl` are all module-private —
  `instance.getClass().getMethod(…)` resolves the method on the inaccessible
  class and throws `IllegalAccessException`. Every call resolves its `Method`
  on the public interface (`ModelController`, `ServiceRegistry`,
  `ModelControllerClient`) and invokes it on the impl instance.

### `knownHandlers()` on WildFly

Today: `List.of(ALL_HANDLERS)`. After this feature: `ALL_HANDLERS` **plus every
real handler whose name resolved** to a configured name. A real handler still
stuck on an identity token is **not** added to `knownHandlers()` — its token is
unstable across a restart (issue #13's whole reason for `ALL_HANDLERS`), so
advertising it would reintroduce the bug #13 fixed. `realHandlers()` is
unchanged — it still lists every real handler for fan-out and baseline capture,
by resolved name where available and identity token otherwise.

### Lifecycle: when it runs, caching, re-resolution

- **Readiness.** The LogManager-ready gate
  (`WildFlyLogManagerReadiness`) fires *before* the server reaches `running`;
  the management model may not be queryable yet at `installContext`.
  Resolution is therefore **lazy** — attempted on the first
  `knownHandlers()` / `realHandlers()` call, and again on every later call
  while it keeps coming back empty, until it succeeds once. No permanent
  give-up (a slow-booting server still gets its names; a `resolve()` that
  can't reach the model returns fast). The attempt is serialised on a lock so
  concurrent first-callers make one attempt between them, not one each.
- **Caching.** A resolved name↔instance map is cached on the adapter. `refFor`
  reads it; once `resolution == DONE` no further model reads happen on the
  hot-ish `realHandlers()` path (`doctor`/`top`/the sweep all call it). The
  per-handler ref maps are not pruned when a handler instance is discarded —
  slow, reconfiguration-count-bounded growth, tracked as issue
  [#31](https://github.com/ddeuchert/logaperture/issues/31).
- **Re-resolution.** The `LogManager` configuration-change listener
  (`WildFlyContainerIntegration.wireConfigurationListener`) and the periodic
  verification sweep re-run on a `/subsystem=logging` change or `:reload`; the
  resolver cache is invalidated on the same signal. A **newly added** handler
  is picked up (a fresh `Handler` instance → named straight away on the next
  `realHandlers()`). A handler **renamed in place** (same instance, new
  configured name) keeps its existing ref — ref stability wins, and the old
  name still resolves to the live handler; chasing the rename would orphan any
  baseline/override keyed on the old ref (the same hazard as #29).

### Ref stability across a late resolution

A `HandlerRef`'s `value` must not change under a user who has already captured
it (overrides are keyed on `(contextKey, HandlerRef)`). So: **no real ref is
advertised on WildFly until its name has resolved.** Before resolution
succeeds, `knownHandlers()` is `[ALL_HANDLERS]` exactly as today, and the
blocking-handler warning names `ALL_HANDLERS`. Once resolution succeeds the
friendly refs appear and stay put. There is no identity-token→friendly-name
migration path to build because the identity token is never advertised as
addressable on WildFly in the first place.

### Downstream: `doctor` and `top`

Both iterate the same refs (`realHandlers()` / `handlerDiagnostics`). Once
names resolve they render `FILE` / `CONSOLE` / `SIF` instead of
`PeriodicRotatingFileHandler@…` with no change of their own — in scope for this
feature as the confirmation that resolution is wired through the one code path,
and a visible payoff for `doctor`'s customer-facing output.

### Failure handling

- A handler with no model entry (added programmatically, or a deployment's own
  handler) → identity token, not addressable, no error.
- MSC / `ModelController` unreachable (a security manager, an unexpected
  WildFly version, domain mode already declined earlier) → resolver returns an
  empty map, `knownHandlers()` stays `[ALL_HANDLERS]`, one diagnostic line, the
  feature is simply absent. `ALL_HANDLERS` control is completely unaffected.
- Never blocks `installContext` and never runs on WildFly's own configuration
  thread — resolution is lazy and on the caller's thread (a `logctl` request,
  the sweep), like every other adapter call.

### Version claim

Verified on **WildFly 26.1.3.Final** (`WildFlyContainerIT`). The spec claims
"best-effort, and degrades cleanly to `ALL_HANDLERS`-only elsewhere" — it does
not claim EAP or pre-26 coverage without a real run on them.

### Known limitation — handler-override resume races name resolution (issue [#29](https://github.com/ddeuchert/logaperture/issues/29))

`resumeFromStateStore` runs at `installContext`, before WildFly's management
model is queryable, so name resolution hasn't happened and `realHandlers()`
still returns identity tokens. Two consequences, deferred to #29:

- **A persisted per-handler sticky override is dropped on restart.**
  `logctl handler CONSOLE INFO sticky` — possible now that #14 makes per-handler
  addressing work — fails to re-apply on the next restart (`CONSOLE` doesn't
  resolve yet → `UnknownHandlerException` → "failed to resume … skipping it"),
  and isn't re-tried by the verification sweep because it was never tracked.
  The state-store entry survives, so it fails identically every restart until
  re-issued.
- **A sticky `ALL_HANDLERS` override orphans its per-real baseline.** It's
  fanned out over token refs and baselines are captured under those tokens;
  once `upgradeTokenRefs()` promotes the instances to `CONSOLE` / `FILE`, the
  next sweep re-captures a baseline against the friendly ref that reads back
  the already-applied override level. `logctl handler ALL_HANDLERS reset` then
  reverts to the override level, not the true original.

Unaffected: a live `logctl handler CONSOLE …` / `ALL_HANDLERS …`, an
`ALL_HANDLERS reset` / `reset --all` issued before the upgrade, and every
non-WildFly adapter.

**Not fixed by delaying resume** (a considered option, rejected): resume runs
at the earliest point the agent can set a level at all, and the ~10 s until the
management model is up is full of subsystem/deployment boot logging a sticky
*logger* override exists to capture — the agent can't pause the JVM, and
widening that hole to close a rare baseline bug is the wrong trade. The
direction in #29 keeps resume early and instead (a) keeps an un-resolvable-yet
handler override tracked as *pending* so the verification sweep applies it once
resolution catches up, and (b) migrates the `HandlerBaselineRegistry` /
`HandlerOverrideRegistry` key when `upgradeTokenRefs()` renames a ref.

### Sign-off — resolved

*All nine review decisions resolved to their leaning, and folded into the
prose above:* the mechanism is an **in-VM `ModelController` read** of
`/subsystem=logging` for names, with instances bound by handler shape /
configured file name — the "hybrid"'s MSC service walk was dropped when
`WildFlyContainerIT` showed real WildFly 26.1.3 registers no logging-handler
MSC services (was #1; contract held, mechanism adjusted to what real WildFly
does, per CLAUDE.md). `knownHandlers()` on
WildFly returns `ALL_HANDLERS` **plus** every real handler whose name resolved;
a real handler still on an identity token is not advertised, since its token is
unstable across a restart (was #2). The seam is a **bulk**
`Map<Handler,String> resolve(List<Handler>)` on a `HandlerNameResolver`
interface in `adapter-jul`, injected through `JulAdapterFactory` (was #3).
Resolution is **lazy** — attempted on the first `knownHandlers()` /
`realHandlers()` call, retried on later calls until it succeeds once, cache
invalidated on the existing config-change signal (was #4). **No real ref is
advertised until its name resolves**, so there is no identity-token→name
migration path (was #5). `doctor` and `top` **pick up friendly names** as part
of this feature, through the same `realHandlers()` path (was #6). Resolution is
**per-handler best-effort** — an unresolved handler keeps its token and stays
non-addressable, never an error, `ALL_HANDLERS` unaffected (was #7). The
version claim is **"verified on 26.1.3.Final, best-effort and cleanly degrading
elsewhere"** (was #8). The handler catalog (#15) — reframed as **`logctl
handlers`**, the `logctl levels` counterpart — is **delivered in the same PR**
rather than kept separate (was #9, reversed): #14 is what makes the names on it
worth reading, and the catalog is what makes #14 testable. See "The handler
catalog".

## AUTO handler level (issue [#20](https://github.com/ddeuchert/logaperture/issues/20))

Status: **implemented** — all 7 decisions (AUTO-1 through AUTO-7 below)
resolved and built as drafted, with two mechanism refinements folded back in
during implementation (see "Recompute trigger" and "Persistence and resume
ordering" below, each marked "As implemented"): the reactive listener is
wired by each container at construction time rather than by
`AggregateLevelControl`, and resume/redeploy ordering resolves per context
with no new aggregate-level method needed. Scoped narrower than issue #28's
own framing (see the "Planned extension" note at the top of this doc) — a
distinct, self-contained slice of #20, not folded into #28 as #28 originally
proposed: AUTO applies to any `HandlerRef` already addressable today — a real
handler, or `ALL_HANDLERS` — with no dependency on `DEFAULT_HANDLERS`
existing first. `DEFAULT_HANDLERS` and `logctl debug … --to <group>` delivery
targeting stayed behind in #28, trimmed of the AUTO material this section now
owns. Unit-tested in `LevelControlServiceTest` (the listener seam),
`HandlerLevelControlServiceTest` (activation, recompute, `ALL_HANDLERS`
fan-out, precedence, capability), and `AggregateLevelControlTest` (the
`setHandlerAuto` broadcast). Not yet exercised against real WildFly.

**After this feature, the user will be able to:**

- Put a handler into a self-tracking mode instead of a fixed level:
  `logctl handler CONSOLE AUTO`, `logctl handler ALL_HANDLERS AUTO sticky`.
- Raise a logger (`logctl debug org.acme for 30m`) and have an `AUTO` handler
  on its path drop to match automatically — no separate `logctl handler …`
  command needed, and no blocking-handler warning naming a floor that's
  already been cleared out from under it.
- Let the handler rise back to its own native level automatically the moment
  the last logger override that needed it resets or expires — no separate
  "remember to raise it back" step.
- See which handlers are in `AUTO`, and what level they're currently tracking,
  in `logctl status` / `--json` and `logctl handlers`.
- Move a handler out of `AUTO` at any time — a fixed `logctl handler CONSOLE
  <level>` or a plain `logctl handler CONSOLE reset` supersedes/reverts it
  exactly as it would a fixed override.

### Mechanism: a mode, not a `Level`

`AUTO` is **not** a fifth pseudo-value of {@link Level} alongside `ALL`/`OFF`.
`Level`'s ordinal ordering is used directly for real severity comparisons
(`isMoreVerboseThan`, every `compareTo`) throughout `core`, the JMX surface,
and every adapter; a value that isn't a real severity would need a special
case at every one of those call sites, and `ALL`/`OFF` earn their place in
that enum only because they're genuine configuration extremes a real
`logback.xml` can set — `AUTO` isn't a level a handler is ever actually
*at*, it's a policy for what level to be at.

`HandlerLevelOverride` instead gains a `mode` field:

```
enum HandlerLevelMode { FIXED, AUTO }

HandlerLevelOverride {
    handlerRef: HandlerRef
    level: Level        // FIXED: the level the user set.
                         // AUTO: the level last computed for it (see below) —
                         // always present, never null, so every other reader
                         // of this record (status, --json, audit) needs no
                         // AUTO-specific null handling.
    mode: HandlerLevelMode = FIXED
    reason: String?
    appliedAt: Instant
    source: String
    tier: PersistenceTier
    expiresAt: Instant?
}
```

`level` keeps its existing meaning for every reader — "the level currently
applied to this handler" — for both modes; `mode` is the only new thing to
branch on, and only `HandlerLevelControlService` ever does. `tier`/`expiresAt`
govern how long the **AUTO mode itself** lasts, exactly as they do for a fixed
override today — independent of how many times the tracked `level` moves
during that time. `logctl handler ALL_HANDLERS AUTO for 2h` means "track for
2 hours, then revert to baseline", not "the next 2-hour-old recompute expires".

A new operation, parallel to `setHandlerLevel` rather than folded into it (no
`Level` argument to parse `AUTO` out of):

```
setHandlerAuto(ref: HandlerRef, options: SetHandlerLevelOptions) -> HandlerLevelOverride?
```

surfaced as `logctl handler <name> AUTO [<tier token>] [--reason <text>]` —
same grammar position as `<level>` in `logctl handler <name> <level>`, same
tier tokens, same `--reason`. Empty return has the same meaning as
`setHandlerLevel`'s: `hasHandlerLevels() == false` (Logback, `none`), no-op.

### Recompute trigger: reactive, wired by each container at construction time

**As implemented** (adjusted from the sign-off's "`AggregateLevelControl` is
the only class that wires the two together", below — the mechanism, not the
contract): `LevelControlService`'s `LoggerOverrideChangeListener` is a
constructor-injected, immutable field, so it has to be supplied at
construction time, before `AggregateLevelControl.register` ever sees the
`ContextControl`. Each container's `installContext` — the one place that
already builds *both* services for a context — builds
`HandlerLevelControlService` first, then a listener closing over it
(`handlerService::recomputeAuto`), then passes that listener into
`LevelControlService`'s own constructor. `AggregateLevelControl` still owns
the two places recompute needs a one-off *pull* rather than the reactive
*push* above (see "Persistence and resume ordering" below), but the live
per-mutation wiring itself is a composition-root concern, not something
`AggregateLevelControl` can retrofit onto an already-constructed service.
`HandlerLevelControlService` also gained a second constructor-injected seam,
`ActiveLoggerFloor` (`Optional<Level> lowestActive()`), so `recomputeAuto()`
takes no arguments — it asks its own supplier, which each container wires as
`() -> ActiveLoggerFloor.lowestOf(overrides.all().values())`, closing over
the same `OverrideRegistry` instance handed to `LevelControlService`. Neither
service class ends up referencing the other's type.

Per issue #28: **reactive**, not swept — recomputed synchronously as part of
the same call that changed the logger-override set, not on the next periodic
tick. This matters for correctness, not just latency: the blocking-handler
warning (`LevelControlService.setLevel`'s tail, "Warning on level commands"
above) reads the adapter's *current* handler level to decide whether to fire.
If an `AUTO` handler's recompute happened even one tick later, the warning
would fire against the handler's stale, not-yet-lowered level and print a
"run `logctl handler CONSOLE TRACE`" suggestion for a handler that's already
at `TRACE` — actively wrong advice, not just a missed optimization. So
**recompute must happen before `setLevel` computes its blocking-handler
floors, inside the same call**, and once it has, no special-casing of the
warning is needed at all: an `AUTO` handler that already tracked down to the
new level simply no longer shows up in `handlerFloorsBelow`'s answer, the
same way a handler a human had already lowered by hand wouldn't.

`LevelControlService` and `HandlerLevelControlService` are deliberately
decoupled today (`HandlerLevelControlService`'s own class doc: "independent
lifetime… needs none of `LevelControlService`'s machinery"). AUTO is the
first feature where a handler override's behavior genuinely depends on
logger-override state, so *some* seam between the two is unavoidable — the
question is where. Every container (`NoneContainer`, `WildFlyContainer`)
already builds exactly one `LevelControlService` + one `HandlerLevelControlService`
pair per context and hands both to `AggregateLevelControl` as one
`ContextControl` — this is the one place in `core` that already holds both
halves for the same context. Putting the wiring there means:

- **`LevelControlService` gains one small, generically-named seam** — a
  `LoggerOverrideChangeListener` (single abstract method `onChange()`,
  default `NONE` = no-op) passed in at construction, invoked at the point in
  `setLevel` right after the mutation loop commits to the registry and
  *before* the blocking-handler floor computation runs, and once at the end
  of `resetLevel` and once at the end of `resetAll`'s loop and once at the
  end of `sweepExpiredOverrides`'s loop. `LevelControlService` calls this
  listener knowing nothing about handlers, `AUTO`, or `core`'s own handler
  classes — it is exactly as decoupled from `HandlerLevelControlService` as
  before, just no longer silent about "my tracked state changed".
- **`HandlerLevelControlService` gains `recomputeAuto()`** — for every
  tracked override in this context whose `mode` is `AUTO`, computes the
  target (`activeLoggerFloor.lowestActive()`, or the handler's own captured
  baseline if empty) and, if it differs from the override's current `level`,
  applies it and replaces the registry entry with an updated `level` (same
  `appliedAt`/`tier`/`expiresAt`/`reason` — a recompute is not a new
  override, just an updated one). For `ALL_HANDLERS` in `AUTO`, this fans out
  over `realHandlers()`, skipping any real handler that has its own
  more-specific override superseding it (the same carve-out
  `applyAndRecordGroupMutation` already makes) — every real moves to the same
  explicit target when one is active; with none active, each real reverts to
  its *own* baseline (they can genuinely disagree, same as a `FIXED`
  `ALL_HANDLERS` reset), and the group's single displayed `level` becomes the
  strictest of those baselines (a display-only summary, the same "no
  invented aggregate level" gap Decision #5 already lives with).
- **`resumeFromStateStore` and `adoptOverride` do *not* fire the live
  listener** — seeing why is the next section.

### Scope of "lowest active override": per context, not per routing path

The lowest active override considered is **every currently-active logger
override in the same logging context** — the same scope `OverrideRegistry`
already uses, with no notion of "does this logger's path actually reach this
handler". The codebase has no routing/path model to consult (a WildFly
context's loggers all share the same handler set today, per the M0 finding
already recorded in this doc's Status section), and inventing one here only
to serve `AUTO` would be scope creep. `DEFAULT_HANDLERS` (#28) is exactly the
mechanism for narrowing "which handlers should this affect" when that turns
out to matter — by **group membership**, not by a smarter recompute — so
this stays consistent with #28's own framing ("non-pollution falls out of
scoping the group, no per-logger routing") without `AUTO` itself needing to
know about groups.

### Persistence and resume ordering

An `AUTO` override persists its `mode` and its own `tier`/`expiresAt` (its
own lifetime), plus its last-known `level` as a cache — never trusted as
current on its own after a restart, always superseded by a recompute before
anything reads it in anger. This resolves the ordering hazard #20 itself
flagged (`HandlerLevelControlService.resumeFromStateStore` and
`LevelControlService.resumeFromStateStore` are two independent methods with
no ordering guarantee between them):

- Resuming an `AUTO` override (`HandlerLevelControlService.resumeOne`)
  re-applies its cached `level` verbatim, same as a `FIXED` one — a
  plausible-but-possibly-stale value, exactly like any other freshly-resumed
  state before the world has been re-observed once.
- **Neither `resumeFromStateStore` nor `adoptOverride` fires the live
  `LoggerOverrideChangeListener`** — both can each be called once per
  persisted entry / once per rebroadcast override during startup or a
  redeploy, and firing a full recompute after every single one is wasted
  work computing against a partially-resumed world.
- **As implemented**, this needs no cross-context barrier: `AUTO`'s scope is
  strictly per context (the "Scope" note above), so it's enough for each
  context's own resume to finish both halves before that context's own
  recompute runs — no dependency on any *other* context. Each container's
  `installContext` calls `handlerService.recomputeAuto()` once, right after
  `service.resumeFromStateStore(...)` and `handlerService.resumeFromStateStore(...)`
  have both returned for that context. `AggregateLevelControl.addContext`
  does the same for a redeploy: `control.handlerService().recomputeAuto()`
  once, after both the logger and handler `adoptOverride` loops have run
  against the new context. One pass per context, correct final state, no
  intermediate recomputes against half-resumed data, and no new
  aggregate-level method needed.

### Capability

Activating `AUTO` (`setHandlerAuto`) requires **`handler.lower`** — the same
simplification `resetHandler` already makes for reverting to baseline: `AUTO`'s
entire purpose is letting the floor drop to follow a raised logger, so the
capability that governs "let this handler get more verbose" is what a
reviewer is actually granting by turning it on, regardless of which specific
level it lands at first. `handler.raise` plays no role here, unlike `FIXED`,
where the direction is judged against the level named.

**Every subsequent reactive recompute checks no capability at all** — same
as the verification sweep, `resume`, and `reapply` today, none of which
re-check capability on every tick. This isn't a special carve-out for
`AUTO`; it's the same principle already in force: a capability check gates a
*new* operator action, and a recompute is system-driven continuation of one
`AUTO` activation that was already authorized once, whichever direction it
happens to move the level on a given tick (down when a new override appears,
back up toward baseline when the last one clears).

Deactivating `AUTO` — a fixed `setHandlerLevel` or `resetHandler` on the same
ref — needs exactly what it needs today; nothing about `AUTO` changes those
capability rules.

### Precedence: manual and `AUTO` on the same handler

**Last-writer-wins**, mirroring the supersede idiom this codebase already
uses everywhere else on `(contextKey, ref)` (two `FIXED` overrides on the
same logger or handler; an individual override peeled off by a later
`ALL_HANDLERS` mutation, in `applyAndRecordGroupMutation`):

- `logctl handler CONSOLE AUTO` while `CONSOLE` has a `FIXED` override:
  supersedes it, `mode` flips to `AUTO`, immediately recomputed.
- `logctl handler CONSOLE <level>` while `CONSOLE` is `AUTO`: supersedes it,
  `mode` flips to `FIXED`, tracks nothing from then on until re-activated.

No "manual as a ceiling `AUTO` can't cross" precedence model — the simplest
option, and consistent with every other supersede path in this feature
needing no such thing today.

### Interaction with the base feature

- **Data model, Capability and audit, Failure handling** (below): everything
  written there about `FIXED` overrides — baseline capture, independent
  lifetime, `ALL_HANDLERS`'s one-tracked-override/N-audit-rows shape,
  multi-context broadcast, reconfiguration re-application — applies to an
  `AUTO` override unchanged; `mode` is purely additive to that record.
- **Audit.** A recompute that actually changes the applied level writes a
  `MUTATION` audit record exactly like any other handler-level change,
  `source = "auto-recompute"`; a recompute tick that finds nothing to change
  writes nothing (no audit noise from an `AUTO` handler quietly agreeing with
  itself every time an unrelated logger is touched).
- **Verification sweep.** `verifyAndReapply` is unaffected — it compares the
  adapter's live level against the override's tracked `level`, which is
  exactly what a correct recompute already kept in sync; drift here means
  something *else* reconfigured the handler out from under `AUTO`, the same
  drift the sweep already exists to catch.
- **`--json` / `logctl status` / `logctl handlers`.** `HandlerLevelOverrideData`
  and `HandlerInfo` gain a `mode` field alongside `level` — additive per
  `doc/specs/component-versioning-policy` (§11.1: the `--json`/MXBean surface
  is additive-only within a major).

### Open decisions (sign-off) — resolved

*All seven decisions signed off in review, every one to its recommended
leaning as drafted — no changes requested:*

- **AUTO-1 (was #20 "Mechanism").** `mode` field, not a pseudo-`Level`; new
  `setHandlerAuto` operation rather than overloading `setHandlerLevel`'s
  `<level>` parse.
- **AUTO-2 (was #20 "Recompute trigger").** Reactive (settled by #28 already)
  — the seam is a `LoggerOverrideChangeListener` on `LevelControlService`,
  firing *inside* `setLevel` before the blocking-handler floor check so the
  warning never fires against a stale pre-recompute level. This ordering is
  a correctness requirement, not a preference. As implemented, the listener
  is wired by each container at construction time (not by
  `AggregateLevelControl`, which can't reach an already-constructed
  service's constructor-injected field) — see "Recompute trigger" above.
- **AUTO-3 (was #20 "Scope of lowest active override").** Per logging
  context, every active override, no path-relevance filtering — matches
  today's architecture (no routing model exists); `DEFAULT_HANDLERS` (#28)
  is where narrowing-by-scope belongs.
- **AUTO-4 (was #20 "Capability semantics").** Activation requires
  `handler.lower` once; every reactive recompute thereafter checks nothing,
  matching how resume/reapply/the verification sweep already behave.
- **AUTO-5 (was #20 "Persistence / resume ordering").** Persist `mode` +
  the override's own tier/expiry + a last-known `level` cache; one
  `recomputeAuto()` pull per context after that context's own resume (and
  after its own redeploy rebroadcast) rather than firing the live listener
  during either. As implemented this needs no cross-context barrier — `AUTO`
  is scoped per context (AUTO-3), so no new aggregate-level method was
  needed; see "Persistence and resume ordering" above.
- **AUTO-6 (was #20 "Interaction with the blocking-handler warning").**
  No special-casing needed in the warning path at all, given AUTO-2's
  ordering — an already-tracked-down handler simply stops appearing in
  `handlerFloorsBelow`'s answer.
- **AUTO-7 (new — precedence with a manual override, raised while drafting
  this section, not originally in #20).** Last-writer-wins on the same ref,
  no "manual as a ceiling" model.

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
- **`ALL_HANDLERS`: one tracked override, N audit rows (Decision #3,
  issue #13).** `logctl status` shows one row for an `ALL_HANDLERS` override,
  not one per real handler it happened to touch — a single
  `HandlerLevelOverride` is tracked under the `ALL_HANDLERS` key (status,
  resume, and the expiry sweep all key off it). The audit trail's existing
  granularity is unchanged: one audit record per real handler actually
  mutated, exactly as if each had been set individually. Only the
  status/tracking row consolidates.
- **`ALL_HANDLERS` reset restores per-handler, not to one value (Decision #4,
  issue #13).** `logctl handler ALL_HANDLERS reset` reverts every handler in
  `realHandlers()` to its own captured baseline in `HandlerBaselineRegistry`
  — never to the override's single `level` field. If `CONSOLE` started at
  `INFO` and `FILE` at `DEBUG`, both revert to their own prior value, not to
  a shared one. Consistent with baseline living outside the override record,
  above.
- **Direction.** `level` below the handler's current level is a *lower* (needs
  `handler.lower`); above it is a *raise* (needs `handler.raise`). Equal is a
  no-op with a note. `ALL` / `OFF` are valid targets. For `ALL_HANDLERS`
  (Decision #5, issue #13), there is no invented aggregate "current level" for
  the group: direction is judged **per real handler** with this exact,
  unchanged logic, and the operation requires the **union** of whichever
  capabilities the individual reals need — the same "all pass or all fail"
  principle multi-context prechecks already use.
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
    handlerRef: HandlerRef    // configured name, <class>@<idhash> fallback, or ALL_HANDLERS
    level: Level              // current target (FIXED: user-set; AUTO: last computed)
    mode: HandlerLevelMode = FIXED   // FIXED | AUTO -- see "AUTO handler level" (issue #20)
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

`ALL_HANDLERS` is a valid `handlerRef` value on the override record itself —
the group is tracked as a single entry keyed by the reserved ref, same
registry, same persistence path, no new record shape (Decision #3,
issue #13). `HandlerBaselineRegistry`, by contrast, is never keyed by
`ALL_HANDLERS` — it only ever holds entries for real refs, one per handler in
`realHandlers()`, which is exactly what lets reset restore each handler to
its own prior value (Decision #4).

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
  carry `source` = `reset` / `expiry` / `resetAll` / `resume`. For
  `ALL_HANDLERS` (Decision #3, issue #13), "one record per handler" means one
  per **real** handler actually mutated, `handlerRef` set to that real ref —
  never a single `ALL_HANDLERS`-keyed row standing in for the whole group; the
  audit trail's granularity doesn't change just because the tracked override
  does.
- `setHandlerAuto` (issue #20) checks `handler.lower` once, at activation —
  see "AUTO handler level" for why, and for why every later reactive
  recompute checks nothing further. A recompute that changes the applied
  level writes a `MUTATION` audit record with `source = "auto-recompute"`; a
  recompute that changes nothing writes no record.

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
- `logctl status` (and `--json`) render active handler overrides — a
  `HANDLER`/`LEVEL`/`TIER`/`REVERTS`/`REASON` table alongside (or, with no active
  logger overrides, instead of) the existing logger table; `--json` wraps both
  under `loggers`/`handlerOverrides` keys. `AggregateLevelControl.listHandlerOverrides`
  unions a handler active in more than one context by ref, same as the
  blocking-handlers union `setLevel` already does.
- `logctl handlers` (issue #15): `listHandlers()` returns one `HandlerInfo` per
  `knownHandlers()` entry with its current level, `handlerDiagnostics` facts, and
  any active override's level/tier/expiry; a row's level reflects an active
  override; an adapter with `hasHandlerLevels() == false` yields an empty list
  and the command prints a note. The text renderer shows the `PERSISTS`/`TARGET`
  columns and an `OVERRIDE` cell; `--json` wraps the rows under a `handlers`
  key with `null` where a field doesn't apply. The parser rejects arguments.

**Unit — `AUTO` (issue #20):**

- `setHandlerAuto(CONSOLE)` with no active logger overrides: `CONSOLE` stays
  at its captured baseline; the tracked override has `mode = AUTO`.
- `logctl debug org.acme` while `CONSOLE` is `AUTO`: `CONSOLE` drops to
  `DEBUG` in the same call, before the blocking-handler warning is computed
  — `logctl debug org.acme` prints **no** warning (the floor no longer
  blocks). A second, stricter `logctl trace org.other`: `CONSOLE` drops
  further to `TRACE`.
- The `org.acme` override resets (or expires): with `org.other` still
  `TRACE`, `CONSOLE` stays at `TRACE` (the remaining minimum), not back to
  baseline.
- The last remaining logger override (`org.other`) resets: `CONSOLE` returns
  to its own captured baseline, one audit `MUTATION` record,
  `source = "auto-recompute"`.
- A recompute that finds nothing to change (e.g. a second, less-verbose
  logger override appears while a more-verbose one is already active)
  writes no audit record and leaves the handler untouched.
- `ALL_HANDLERS AUTO`: fans out over `realHandlers()` on recompute, one audit
  row per real handler that actually changed level this tick, skipping a real
  handler with its own more-specific `FIXED` override.
- Precedence: `CONSOLE AUTO` then `CONSOLE DEBUG` — `mode` flips to `FIXED`,
  no further recompute touches it until re-activated; and the reverse
  (`CONSOLE DEBUG` then `CONSOLE AUTO`) immediately recomputes.
- Capability: `handler.lower` withheld → `setHandlerAuto` denied, exit 6, no
  override recorded; once active, withholding `handler.lower` afterward does
  **not** block a later recompute (no capability re-check on the reactive
  path).
- Resume ordering: a persisted `sticky AUTO` override and a persisted
  `sticky` logger override both resume; `AggregateLevelControl.recomputeAllAuto()`
  run once after both resume phases complete lands on the correct tracked
  level — not whatever stale `level` was persisted.
- `logctl status` / `--json` / `logctl handlers` show `mode = AUTO` and the
  currently-tracked `level` for an `AUTO` handler.

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

- **A. `logctl status --handlers` / `listHandlers()`.** Originally resolved
  *defer*; **delivered with issue #14** as `logctl handlers` (a standalone
  command, the `logctl levels` counterpart — not a `status` flag), because the
  catalog is what makes #14's resolved names visible and testable. See "The
  handler catalog".
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

**`ALL_HANDLERS` (issue [#13](https://github.com/ddeuchert/logaperture/issues/13),
resolved during follow-up review, signed off 2026-09-05 — see the Status note
at the top of this doc):**

1. **SPI: two lists, not one.** `knownHandlers()` keeps its existing job —
   what's addressable/advertised, and may collapse to `[ALL_HANDLERS]` on
   WildFly. A new `realHandlers()` is always the true, ungrouped list, used
   internally for fan-out and per-handler baseline capture. Off WildFly the
   two are identical apart from the extra `ALL_HANDLERS` entry. See "Adapter
   SPI".
2. **Fan-out lives in core, not the adapter.** `HandlerLevelControlService`
   loops over `realHandlers()` itself, reusing `HandlerBaselineRegistry`'s
   existing per-ref semantics — an adapter that looped internally could only
   report one before/after pair for the whole group, which breaks once two
   reals start at different levels. See "Adapter SPI".
3. **One tracked override, N audit rows.** A single `HandlerLevelOverride` is
   tracked under the `ALL_HANDLERS` key; the audit trail still writes one
   record per real handler actually mutated. See "Semantics to pin down" and
   "Capability and audit".
4. **Reset restores per-handler, not to one value.** `ALL_HANDLERS reset`
   reverts every real handler to its own captured baseline, never to the
   override's single `level` field. See "Semantics to pin down".
5. **Capability direction, judged per handler.** No invented aggregate
   "current level" for the group — direction is judged per real handler with
   the existing logic, and the operation requires the union of whichever
   capabilities the reals need. See "Semantics to pin down".
6. **Addressing a suppressed real ref directly falls through to the existing
   unknown-handler error.** No new exception type — from the addressable
   surface's point of view it genuinely isn't known anymore once WildFly's
   `knownHandlers()` stops listing it. See "The operation".
7. **The blocking-handler warning collapses in the adapter.** WildFly's
   `handlerFloorsBelow` implementation returns one collapsed `HandlerFloor`
   naming `ALL_HANDLERS` at the strictest real level among the actual
   blockers — zero changes to core, the JMX surface, or `Commands`. Known
   cost: the "just lower CONSOLE" granularity is lost on WildFly until
   issue #14 lands real names. See "Warning on level commands".
8. **`JbossHandlerNames` is retired outright, not patched.** Its entire
   purpose — resolving a real WildFly handler's friendly name — is no longer
   attempted anywhere once WildFly's `knownHandlers()` stops advertising real
   refs. Deleted rather than adapted; issue #14's real fix is built fresh
   against WildFly's management API. See "Adapter SPI".

Non-goals (explicitly not this story): generalized named handler groups
beyond the one reserved `ALL_HANDLERS` name (revisit only if a real second
grouping shows up); the squelch-direction warning (issue #16) and the full
handler catalog listing (issue #15), both unrelated and unchanged by this
story; real WildFly handler names (issue #14, deliberately sequenced after).

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
- `logctl handler ALL_HANDLERS TRACE` against real WildFly lowers every real
  handler in one command, with one audit record per real handler and one
  `ALL_HANDLERS`-keyed row in `logctl status`; `logctl handler ALL_HANDLERS
  reset` reverts each to its own prior level. Implemented, unit-tested, and
  confirmed against a real standalone WildFly 26.1.3.Final (issue #13,
  `WildFlyContainerIT`).
