# Handler-Floor Control — `--lower-handlers`

Status: draft, not yet implemented. Sign-off review pending.
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
"level floor" diagnostic this feature acts on.

## Functional summary

After this feature, the user will be able to:

- Add `--lower-handlers` to a `logctl` level command —
  `logctl trace org.perfmon4j --lower-handlers`,
  `logctl set org.perfmon4j TRACE for 30m --lower-handlers` — and have the
  handlers that would otherwise swallow the new level (WildFly's `CONSOLE`
  handler fixed at `INFO`, say) lowered at runtime so the records actually
  appear, instead of only getting the warning that they won't.
- Rely on that handler change being **tied to the override that caused it**: when
  the logger override expires, or is cleared with `logctl reset <logger>`, or
  `logctl reset --all` runs, each handler it lowered goes back to the level it had
  before — unless another still-active override also needs it lowered.
- See the lowered handlers named in `logctl status` and in the confirmation line,
  and in the agent's audit trail.
- Still never have `standalone.xml` or `logging.properties` touched — the handler
  level is changed on the live handler object only, in the agent's own tracking,
  and reverts on its own.
- Pass `--lower-handlers` harmlessly on a framework that has no handler levels
  (plain Logback): it is a no-op with a one-line note, not an error.

## Scope of this slice

The handler-floor *detection* shipped with the JBoss LogManager adapter
(`wildfly-support.md`, Slice 2), which deliberately stopped at "tell the user"
and reserved the fix as "an explicit opt-in (`setLevel` option, or a `logctl`
flag) … a later refinement". This spec is that refinement, and nothing more: one
opt-in flag, coupled to the triggering level override.

**In scope:**

- A new `SetLevelOptions` field `lowerHandlers` (default `false`), surfaced as the
  `--lower-handlers` CLI flag on `set` and the level-named forms.
- When set, and the target level is a genuine raise, the adapter lowers exactly
  the handlers `handlerFloorsBelow(logger, targetLevel)` reports for that logger —
  no other handlers, and no naming of handlers by the user.
- Baseline capture of each affected handler's level, and reversion coupled to the
  triggering `LevelOverride` (expiry, `resetLevel`, `resetAll`, and WildFly
  reconfiguration re-application all carry the handler change with them).
- A new capability `handler.lower`, required **in addition to** `level.raise`.
- Audit records for every handler lowering and every reversion (§9.7 shape).
- JBoss LogManager / JUL adapter implementation; Logback + `none` treat the flag
  as a no-op with a diagnostic.

**Explicitly out of scope** (deferred, each with what it needs):

- A standalone `logctl handler <name> <level>` operation and a `logctl handlers`
  listing — the user chose the coupled opt-in flag; a first-class handler
  operation is a separate feature with its own discovery surface and its own
  "handler override with no logger behind it" lifecycle question.
- Lowering handlers the user names explicitly, or handlers not on the target
  logger's path.
- *Raising* a handler floor (making a sink stricter) — that is a squelch/volume
  concern (Feature 3), not this.
- Formatter/filter changes on a handler (render-stage wrapping — `wildfly-support.md`
  "Out of scope").
- `doctor`'s full logger-vs-handler-vs-sink rendering (still deferred).

## The flag

`--lower-handlers` (no value). Valid only alongside a level raise; combining it
with a command that lowers a logger, or with `logctl reset`, is a usage error
(exit 2). It composes with every tier token:

```
logctl trace org.perfmon4j --lower-handlers
logctl set org.perfmon4j TRACE for 30m --reason INC-42 --lower-handlers
logctl debug org.hibernate.SQL sticky --lower-handlers
```

Phone-test: the flag contains none of `: = ( ) /`; the `--help` synopsis for
`set` / the level forms gains `[--lower-handlers]` in the options list, not the
synopsis line.

## Operations impact

No new top-level operation. `setLevel(name, level, options)` gains one option:

```
SetLevelOptions {
    includeChildren: boolean
    reason: String?
    expiresIn: Duration?
    tier: PersistenceTier
    lowerHandlers: boolean      // NEW, default false
}
```

`resetLevel` / `resetAll` / expiry sweep are unchanged in signature — their
*behaviour* extends to also revert coupled handler changes (see Semantics).

`listLoggers` / `LoggerInfo` gains an optional `loweredHandlers: List<String>` on
the row for a logger whose active override lowered handlers, so `logctl status`
and `--json` can show them.

## Adapter SPI

The base `LoggingAdapter` SPI gains:

```
/** Lower the given handlers (by the identity handlerFloorsBelow returned) to
 *  `target`, capturing each one's prior level on first touch. No-op for an
 *  adapter without handler levels. Returns the handlers actually changed. */
List<HandlerFloor> lowerHandlers(String loggerName, Level target);

/** Recompute what floor each previously-lowered handler needs from the set of
 *  still-active overrides passed in, and restore or re-lower accordingly.
 *  Called on every reset, expiry, and reconfiguration re-application. */
void reconcileHandlerFloors(Collection<LevelOverride> activeOverrides);
```

The JBoss LogManager adapter implements both against
`Logger.getHandlers()` / `Handler.setLevel()` (all JDK). Logback and `none`
return an empty list / do nothing.

## Semantics to pin down

- **Baseline capture for handlers.** The first time a handler is lowered, capture
  its current level. `reconcileHandlerFloors` is the only path that restores it.
  Without a per-handler baseline, reversion is undefined — exactly the reasoning
  §5 gives for logger baselines.
- **Coupling to the triggering override.** A lowered handler floor has no
  independent lifetime. It is reverted (via `reconcileHandlerFloors`) whenever the
  `LevelOverride` that requested it is removed — by `resetLevel`, `resetAll`, the
  expiry sweep, or being superseded. Its tier and `expiresAt` are the override's.
- **Overlap.** Two active overrides can both need `CONSOLE` lowered.
  `reconcileHandlerFloors` recomputes the needed floor as "the most verbose level
  any active override targets through this handler" — so clearing one override
  never raises a handler another still needs. Recompute-from-active-set rather
  than a reference counter, because a WildFly reconfiguration can replace the
  handler instance and desync a counter.
- **Target level to lower *to*.** Exactly the triggering override's target level —
  least surprise, and `reconcileHandlerFloors` already generalises it for the
  overlap case. Not `ALL`/`TRACE`.
- **Multi-context (WildFly).** Handlers are per-`LogContext`. The flag lowers
  handlers in whichever context(s) the coupled logger override is applied to,
  following `wildfly-support.md`'s broadcast semantics. Re-application onto a
  context that reappears after redeploy re-runs `lowerHandlers` for that context.
- **Reconfiguration re-application.** `wildfly-support.md` §15.7 already re-applies
  active logger overrides after the server resets its logging. The re-apply step
  must also call `reconcileHandlerFloors` so a `--sticky` override keeps its
  handler lowered across a redeploy.
- **Logback / `none`.** `lowerHandlers` returns empty; core emits one diagnostic
  (`"--lower-handlers: <framework> handlers have no level of their own; nothing to
  lower"`) and the `setLevel` still succeeds.

## Data model

```
HandlerFloorOverride {
    contextKey: String?       // null for single-context
    handlerName: String
    previousLevel: Level      // baseline, for reversion
    loweredTo: Level
    triggeredBy: String       // the LevelOverride's loggerName
    appliedAt: Instant
    source: String
}
```

Tracked in `core` beside the `OverrideRegistry`, keyed by
`(contextKey, handlerName)`. Not persisted to the state file in this slice beyond
what the coupled `LevelOverride` already persists — a restored `--sticky` logger
override re-derives its handler floors via `reconcileHandlerFloors` on load.

## Capability and audit

- **New capability `handler.lower`** (§9.3). Required *together with* `level.raise`
  for any `setLevel` call with `lowerHandlers = true`. Rationale for a separate
  grant: a handler floor governs **every** logger that routes through that
  handler, so its blast radius is wider than the single-logger `level.raise`; a
  reviewer approving "let support raise a logger for 30 minutes" is not thereby
  approving "let support widen the console sink for the whole server". Add to the
  `Capability` enum and the §9.3 table when this lands.
- **Audit** (§9.7 fields) per handler lowering and per reversion: principal,
  source, `contextKey`, `handlerName`, previous level, new level, `reason` (copied
  from the triggering override), and `triggeredBy` (the logger name). One record
  per handler, not one per flag.

## Failure handling

- A handler whose `setLevel` throws is logged to the agent's diagnostic writer and
  skipped; the others are still lowered and the logger override still stands. The
  confirmation line names which handlers were lowered and which failed.
- `reconcileHandlerFloors` never throws out to a caller — a handler that has since
  vanished (context torn down) is simply dropped from tracking.
- Adapter without the capability granted: `CapabilityDeniedException(HANDLER_LOWER)`
  before any change, same as `level.*`.

## Testing

**Unit — JBoss LogManager adapter (in-process, no WildFly):**

- A `ConsoleHandler` at `INFO` on the root; `lowerHandlers("x", TRACE)` lowers it
  to `TRACE` and returns it; a second call is idempotent.
- `reconcileHandlerFloors` with an empty active set restores the captured `INFO`;
  with one override still targeting `DEBUG` through it, restores to `DEBUG` not
  `INFO`.
- Two overlapping overrides (`x`→`TRACE`, `y`→`DEBUG`, same handler): clearing `x`
  leaves the handler at `DEBUG`; clearing both restores `INFO`.
- Handler identity: two `ConsoleHandler`s with different configured names are
  tracked and reverted independently (**depends on Open decision #1**).

**Unit — core / CLI:**

- `SetLevelOptions` rejects `lowerHandlers` with a logger *lower*; `--lower-handlers`
  with `reset` is exit 2.
- Capability: `handler.lower` withheld → the call is denied and no handler or
  logger change is made.
- `logctl status` / `--json` shows `loweredHandlers` for the triggering logger.
- Logback adapter: `--lower-handlers` → success + the "nothing to lower" note.

**Cross-process (extends `LevelControlEndToEndIT` / `WildFlyContainerIT`):**

- Against real standalone WildFly: `logctl trace <logger> --lower-handlers` makes
  a `TRACE` line reach `CONSOLE`; `logctl reset <logger>` and it stops; a
  `--sticky` one survives a redeploy with the handler still lowered.

## Open decisions (sign-off)

1. **Handler identity for mutation + reversion.** `handlerFloorsBelow` today
   returns `handler.getClass().getSimpleName()` — fine for a one-shot warning, not
   unique enough to lower-then-restore reliably if two handlers share a class. Do
   we resolve the real configured handler name from `org.jboss.logmanager`
   (WildFly names them `CONSOLE`, `FILE`), fall back to an identity hash, or
   both? This gates the data model's key.
2. **Level to lower *to*** — this draft commits to "exactly the triggering
   override's target". Confirm, versus lowering to `ALL` once and never touching
   it again (bigger sink blast radius, less churn).
3. **`--session` overrides and `persist`.** A `--session` logger override with
   `--lower-handlers`: does the handler change need only `handler.lower`, or also
   `persist` because a widened sink is arguably a bigger deal than a
   process-lifetime logger tweak?
4. **Does `--lower-handlers` imply anything for `includeChildren`?** If a level is
   fanned out to descendants, are their handler floors lowered too, or only the
   named logger's path?
5. **Confirmation-line verbosity.** Name every lowered handler inline, or a count
   with detail behind `logctl status` / `--json`?

## Exit criterion

Against a plain `java -jar` + Logback process and a standalone WildFly:

- `logctl set <logger> TRACE for 10m --lower-handlers` on WildFly lowers the
  `CONSOLE` handler, a `TRACE` line appears on the console, and both the logger
  and the handler revert when the 10 minutes elapse — with an audit record for
  each direction.
- `logctl reset <logger>` reverts the handler immediately; a second override
  still needing that handler keeps it lowered.
- The same command on the Logback process succeeds with the "nothing to lower"
  note and changes no handler.
- `handler.lower` withheld makes the command exit 6 naming the capability, with
  nothing changed.
