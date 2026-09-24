# WildFly: defer handler installs until the server's logging is configured (issue #86)

Status: **signed off 2026-09-23** — D1–D6 accepted as recommended ("Decisions agreed" below).
**Implemented** on `feature/86-defer-handler-install`: `HandlerInstallPolicy`,
`AggregateLevelControl.installHandlerLevel()` and its gate, and `WildFlyContainer`'s floor and
one-shot; unit-tested (`HandlerInstallPolicyTest`, `AggregateLevelControlTest`,
`DeferredHandlerInstallTest`). The real-WildFly IT and the run on the reporting launch remain the
exit criterion.
**Revised 2026-09-24 (issue #87):** mechanism found — the handler-name resolver, not the
handlers; fixed at the source, and the delay's default drops to `0` (D7, D8, "Revision:
mechanism found" below). D7–D8 signed off 2026-09-24.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §15.6 (WildFly, the premain
gotcha), §15.5 (the re-application invariant), §18.14 (filtering events logged before the
container's logging is ready).
Builds on: [`wildfly-support.md`](wildfly-support.md) ("Detection and the premain gotcha",
"Reconfiguration re-application: the verification sweep"),
[`rule-pipeline-foundation.md`](rule-pipeline-foundation.md), [`top.md`](top.md),
[`storm-detection.md`](storm-detection.md), and [`trim-rule.md`](trim-rule.md) (#34, merged). All four install something on the JBoss handlers.
Evidence: [`doc/spikes/early-handler-install.md`](../spikes/early-handler-install.md).
Tracks: [issue #86](https://github.com/ddeuchert/logaperture/issues/86). Follow-up:
[#87](https://github.com/ddeuchert/logaperture/issues/87) (find the mechanism; restore early
wrapping if it can be made safe).

## Functional summary

After this feature, the user will be able to:

- Start WildFly with LogAperture in the `-javaagent` list — first, last, or in between —
  without the server aborting at startup.
- Restart a WildFly server and still have its `sticky` levels, handler levels and rules come
  back on their own, as before.
- Have `drop` and `trim` rules apply from the moment LogAperture installs during startup,
  including to other agents' startup logging that reaches the server's log handlers
  (issue #87 revision; before it, they started about 20 seconds in).
- Hold those rules back with `-Dlogaperture.handlerInstallDelaySeconds=<n>` if a launch ever
  needs it; the server's own log then says when they took effect.

## Problem

On a real WildFly launch, a `-javaagent` LogAperture install that put its filters or
formatters on the JBoss LogManager's handlers during `premain` made the JVM abort with
`ModuleNotFoundException: org.jboss.as.standalone`. The spike isolated the trigger to exactly
that: **any** of trim rendering, top's byte counting, storm detection, or the rule pipeline,
installed on the boot handlers before jboss-modules had started, was enough on its own;
everything else the install does at that point was safe. The mechanism is not known.

Two things made the install run that early on this launch. First, the readiness gate ("the
JBoss LogManager class has been loaded by some classloader") was already true at `premain`,
because `-Djboss.modules.system.pkgs` naming `org.jboss.logmanager` puts that class on the
system classloader. Second, the install runs every handler step in one pass.

## Scope

**In scope:** the WildFly container only (`WildFlyContainer`, `WildFlyContainerIntegration`),
and the shared core pieces it calls.

**Out of scope:**
- `NoneContainer` and the Logback adapter — no evidence they are affected, and Logback has its
  own reset event.
- Finding why handler installs break startup (#87).
- Changing the readiness gate (D5).
- A `logctl doctor` status line for "handler install pending" — folded into
  [#85](https://github.com/ddeuchert/logaperture/issues/85) rather than growing this change.

## Design

### Two phases inside the WildFly install

`WildFlyContainer.installContext` currently runs one long sequence. Split it at the point the
spike found:

**Phase 1 — logger level, runs at readiness exactly as today.** Baseline capture, the handler
and level services, and resuming persisted levels, handler levels and rules, the doctor and
environment services, JMX registration. Persisted rules resume and appear in `logctl` as
today; they do not filter anything yet (see "What the user sees" below). Handler *levels*
(`setLevel` on a handler) are part of phase 1 — the spike's `resume` stage includes resuming
them and booted, though whether the state file used there held any handler overrides was not
recorded, so this is worth confirming in the IT.

**Phase 2 — handler level, deferred.** The four steps that install a formatter or filter on
a handler, in their existing order: trim rendering, top's byte
counting, storm detection, the rule pipeline. The order must not change: trim's formatter wrap
installs *inside* top's ([`trim-rule.md`](trim-rule.md) "Interaction with top"), and the
verification sweep already runs them in this order.

Phase 2 is a single core method, callable from the container and from the sweep, so the two
cannot drift. Every step is already idempotent — that is what lets the verification sweep call
them on every tick today — so running phase 2 more than once, or from more than one trigger,
is safe by construction.

### What triggers phase 2, and the floor (D1, D2)

Phase 2 must not run before a **floor**: `logaperture.handlerInstallDelaySeconds` after the
readiness gate passes (default **20**; `0` = no deferral, i.e. today's behavior; above 600 is
clamped to 600; a negative or non-numeric value falls back to 20 with a message on stderr — never
silently to `0`, which would switch the protection off). 20 s is the only delay the spike observed to boot cleanly (`defer-jul`, delaying
everything by 20 s); smaller values were not tested, so the default does not go below it.

Once the floor has passed, phase 2 runs on the **first** of:
1. a **one-shot task** scheduled at the floor, on the existing sweep executor (deterministic
   time-to-effect);
2. a verification sweep tick;
3. the JBoss LogManager configuration-change listener already wired in
   `wireConfigurationListener`.

(2) and (3) exist because the sweep and the listener already re-establish this state; they
must not run phase 2 *before* the floor, and after it they are harmless repeats of (1). The
floor is a property of the container, not the callers: a `phase2Allowed()` check (floor
elapsed) guards the single phase-2 method for whichever caller reaches it.

The one-shot is timed on the monotonic clock while the floor is checked on the wall clock; if it
fires before the floor by the latter, it reschedules for the time left rather than waiting for a
sweep. Phase 2 calls are serialized, since each step is check-then-wrap and the installing thread,
the one-shot, the sweep and the listener can all reach it. A context counts as installed only when
every step succeeded; the "complete" line is logged once, on the first success.

If phase 2 throws, it logs and is retried on the next sweep tick; it never propagates into
WildFly ([`level-control.md`](level-control.md) "Failure handling").

### What the user sees during the gap

- Persisted rules and overrides resume at phase 1: audit records with `source=resume` appear
  at startup as today, and `logctl` lists the rules.
- Levels (loggers and handlers) apply immediately; only filtering (`drop`) and stack-trace
  reduction (`trim`), `top`'s byte counts and storm detection wait for phase 2. A rule's hit
  count stays 0 until then.
- The server's log says so: an `INFO` line at install ("handler-level install deferred for
  `N`s") and one at activation ("handler-level install complete (`n` handlers)") (D4). With
  `handlerInstallDelaySeconds=0` neither line is written, since nothing was deferred.

## Revision: mechanism found (issue #87)

The spike's trigger ("installing a filter or formatter on a handler") was a common *prefix*, not
the handler mutation. Each of the four phase-2 steps starts by listing the handlers
(`JulLoggingAdapter.realHandlers()`), which resolves their WildFly names
(`WildFlyHandlerNameResolver`), which asked JBoss Modules for its boot module loader. JBoss
Modules creates that loader once, on first request, from the `module.path` system property, and
`org.jboss.modules.Main` sets `module.path` only when it parses `-mp`, then reuses the loader. Asked
for at `premain`, the loader is fixed with no module roots and `Main` fails to find
`org.jboss.as.standalone`. Full evidence, including two reproductions:
[`doc/spikes/early-handler-install.md`](../spikes/early-handler-install.md) "Mechanism".

**Fix at the source (D7).** `WildFlyHandlerNameResolver` returns no names, and touches no JBoss
Modules class, while `module.path` is unset. `Main` sets it before it creates the loader, so once
it is set, creating the loader gives the same result `Main` would. Unresolved names are an
existing, handled state: handlers keep their identity tokens and resolution is retried on every
later call ("Lifecycle" in [`handler-floor-control.md`](handler-floor-control.md)).

**The deferral stays, off by default (D8).** With the cause removed there is nothing left to wait
for, so `logaperture.handlerInstallDelaySeconds` defaults to `0`: phase 2 runs inside the install
again, and `drop`/`trim` reach boot-time events on handlers that exist then, which is what
§18.14 wanted. The property, the floor, the one-shot and the two `INFO` lines stay as a safety
valve for a launch that turns up a second, different cause; a negative or non-numeric value now
falls back to `0` (the default) with the same stderr message.

**Limits.**
- A launch that gives JBoss Modules its path only through the `JAVA_MODULEPATH` environment
  variable, never `-mp` or `-Dmodule.path`, never sets `module.path`, so handler names never
  resolve there (identity tokens and `ALL_HANDLERS` still work). No such launch is known. WildFly's
  scripts always pass `-mp`.
- Another agent can still cause the same abort by asking for the boot module loader at
  `premain` itself. So can initializing `org.jboss.modules.Module` early when
  `-Djboss.protocol.handler.modules` is set, because its static initializer then asks for the
  loader. Neither is ours to fix. The guard runs before we touch any JBoss Modules class, so we
  no longer trigger either.

## Docs to update with the change

- [`wildfly-support.md`](wildfly-support.md): the readiness discussion currently says the
  JBoss LogManager class is never visible on the system classloader on WildFly. That holds on a
  stock launch and is false with `jboss.modules.system.pkgs` naming it; note the exception and
  point here.
- [`logaperture-spec.md`](../logaperture-spec.md): link from §15.6; §18.14 already links the
  spike and #86/#87.
- `USER_GUIDE_NOTES.md`: the ordering entry already describes the gap; add the delay property.
- `CHANGELOG.md`.

## Testing

- **Unit (fake adapter):** nothing installs on handlers before the floor, whichever trigger
  fires first (a sweep tick, the listener, the one-shot); everything installs, in the fixed
  order, after it; repeat triggers are no-ops; a phase-2 failure is retried on the next tick;
  `0` runs phase 2 within `installContext` as today.
- **`WildFlyContainerIT` (real WildFly, Testcontainers):** boot is clean with the agent first
  and last in the `-javaagent` list; a sticky `drop` rule persisted from a previous run
  resumes at startup and is effective (the dropped message absent, the hit count moving) within
  the floor plus a small margin.
- **Revision (#87), unit:** `WildFlyHandlerNameResolverTest` — no boot-module-loader lookup
  while `module.path` is unset; the lookup happens once it is set. `HandlerInstallPolicyTest` —
  default `0`.
- **Revision (#87), reproduction harness** (not committed; described in the spike doc): WildFly 33
  launched through `org.jboss.modules.Main` on the class path with `org.jboss.logmanager` in
  `jboss.modules.system.pkgs`, LogAperture first, a slow second agent, delay `0`. Before the fix
  it aborts; after it, it boots.
- **Manual, on the launch that reproduced the failure** (the only place it does): LogAperture
  first in the `-javaagent` list, five clean starts in a row; the trim rule applies after the
  delay; and, informationally, with `handlerInstallDelaySeconds=0` the failure returns (which
  confirms the delay is what fixes it).

## Exit criterion

The reporting launch boots cleanly five times running with LogAperture first, sticky `drop`
and `trim` rules apply after the delay, the unit tests and `WildFlyContainerIT` pass, and the
docs above are updated in the same change.

## Decisions agreed

D1–D6 accepted as recommended, 2026-09-23. D7–D8 (#87 revision) accepted as
recommended 2026-09-24; D8 supersedes D2's default.

| # | Decision | Agreed |
|---|---|---|
| D1 | What triggers phase 2 | One-shot at the floor, plus the existing sweep tick and config listener after the floor. Alternatives: the first sweep tick only (up to 30 s, no new timer); a positive "server has bootstrapped" signal (nothing observed to key on yet — see #87). |
| D2 | The floor value | 20 s default (the only value observed safe), tunable, `0` disables the deferral. Alternative: a lower default — untested, could reintroduce the abort. |
| D3 | Defer on every WildFly launch, or only on a launch that looks like the reproducing one (`jboss.modules.system.pkgs` naming `org.jboss.logmanager`) | Every WildFly launch: the cause is unknown, so "looks like the reproducing one" is a guess. Cost: boot-time trim/drop is lost on launches where early install works — mitigated by `0`. |
| D4 | Observability | Two `INFO` lines (deferred / complete). No `logctl` surface in this change; a status line in `doctor` is #85. |
| D5 | Readiness gate | Unchanged. The spike showed the steps it releases are safe early. A stricter "bootstrapped" signal is a separate change once #87 gives us something to key on. |
| D6 | Property name and shape | `logaperture.handlerInstallDelaySeconds` (integer seconds, 0..600). Alternative: match `logaperture.sweep.seconds`'s naming. |
| D7 | (#87 revision) Where to fix the abort | In the name resolver: no boot-module-loader lookup until `module.path` is set. Alternatives: skip name resolution until a later phase (moves the problem, and phase 2 still needs the handler list); wrap handlers without listing them via the adapter (a larger adapter change for the same effect). |
| D8 | (#87 revision) The delay's default | `0`, property kept as a safety valve. Alternative: keep 20 s until the reporting launch confirms `0` (safer, but gives up boot-time filtering on every launch for a cause now fixed). |
