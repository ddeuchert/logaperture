# WildFly: defer handler installs until the server's logging is configured (issue #86)

Status: **signed off 2026-09-23** — D1–D6 accepted as recommended ("Decisions agreed" below).
**Implemented** on `feature/86-defer-handler-install`: `HandlerInstallPolicy`,
`AggregateLevelControl.installHandlerLevel()` and its gate, and `WildFlyContainer`'s floor and
one-shot; unit-tested (`HandlerInstallPolicyTest`, `AggregateLevelControlTest`,
`DeferredHandlerInstallTest`). The real-WildFly IT and the run on the reporting launch remain the
exit criterion.
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
- Have `drop` and `trim` rules start applying about 20 seconds after startup (a fixed,
  documented delay), rather than to the very first seconds of boot. The server's own log
  says when they took effect.
- Change that delay with `-Dlogaperture.handlerInstallDelaySeconds=<n>`, or set it to `0` on a
  launch where an early install is known to work and boot-time trimming matters.

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
- **Manual, on the launch that reproduced the failure** (the only place it does): LogAperture
  first in the `-javaagent` list, five clean starts in a row; the trim rule applies after the
  delay; and, informationally, with `handlerInstallDelaySeconds=0` the failure returns (which
  confirms the delay is what fixes it).

## Exit criterion

The reporting launch boots cleanly five times running with LogAperture first, sticky `drop`
and `trim` rules apply after the delay, the unit tests and `WildFlyContainerIT` pass, and the
docs above are updated in the same change.

## Decisions agreed

All six accepted as recommended, 2026-09-23.

| # | Decision | Agreed |
|---|---|---|
| D1 | What triggers phase 2 | One-shot at the floor, plus the existing sweep tick and config listener after the floor. Alternatives: the first sweep tick only (up to 30 s, no new timer); a positive "server has bootstrapped" signal (nothing observed to key on yet — see #87). |
| D2 | The floor value | 20 s default (the only value observed safe), tunable, `0` disables the deferral. Alternative: a lower default — untested, could reintroduce the abort. |
| D3 | Defer on every WildFly launch, or only on a launch that looks like the reproducing one (`jboss.modules.system.pkgs` naming `org.jboss.logmanager`) | Every WildFly launch: the cause is unknown, so "looks like the reproducing one" is a guess. Cost: boot-time trim/drop is lost on launches where early install works — mitigated by `0`. |
| D4 | Observability | Two `INFO` lines (deferred / complete). No `logctl` surface in this change; a status line in `doctor` is #85. |
| D5 | Readiness gate | Unchanged. The spike showed the steps it releases are safe early. A stricter "bootstrapped" signal is a separate change once #87 gives us something to key on. |
| D6 | Property name and shape | `logaperture.handlerInstallDelaySeconds` (integer seconds, 0..600). Alternative: match `logaperture.sweep.seconds`'s naming. |
