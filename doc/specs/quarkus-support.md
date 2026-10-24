# Quarkus JVM mode support

Status: **Signed off 2026-10-18** (decisions Q1 to Q9 below). Implemented on `feature/114-quarkus-support`
(PR #225). Release `1.1.0`: `develop` is the 1.1.0 trunk since the release lines were cut ([`release-lines.md`](release-lines.md)), so there is no hold (Q9).

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §15.9 (Quarkus), §15.1–15.5
(container SPI, verification sweep), §17.1 (release plan). Evidence:
[`doc/spikes/quarkus-jvm-mode.md`](../spikes/quarkus-jvm-mode.md). Related:
[`wildfly-support.md`](wildfly-support.md) ("Detection and the premain gotcha"),
[`handler-floor-control.md`](handler-floor-control.md). Tracking issue:
[#114](https://github.com/ddeuchert/logaperture/issues/114).

## Functional summary

After this feature, the user will be able to:

- Add LogAperture to a Quarkus app running in JVM mode with one `-javaagent` flag (or
  `JAVA_OPTS_APPEND` in Quarkus's container images), and control it with `logctl` the same way
  as a WildFly server: `set logger`, `set handler`, vendor defaults, `drop` / `trim` rules, `top`,
  `doctor`, storms.
- See Quarkus's own handlers in `logctl list handlers` as `console`, `file`, `syslog` and
  `socket`, and raise or lower them.
- Get a clear warning when `logctl set logger X TRACE` can't take effect because the app was
  built with a higher `quarkus.log.min-level`, and what to rebuild with.
- See `Quarkus <version>` in `logctl env`.

Guide pages this changes: new `get-started/install-quarkus.md` (install, the supported versions,
the limits below); `reference/doctor-checks.md` (the new min-level check, the autoflush
demotion); `get-started/quick-start.md` and `troubleshooting.md` (a Quarkus pointer); `index.md`
and `mkdocs.yml` navigation. `reference/agent-options.md` changes only if the slice adds a
`-Dlogaperture.*` property (none is planned).

Not part of this feature: plain `java -jar` apps on `java.util.logging` alone
([#221](https://github.com/ddeuchert/logaperture/issues/221)).

## Decisions

All nine were agreed on 2026-10-18 from the sign-off review.

| Id | Decision | Outcome |
|---|---|---|
| Q1 | Readiness gate | The poll, timeout and TCCL handling become shared code; the "ready" condition is a parameter. Quarkus passes the new condition (`org.jboss.logmanager.LogContext` loaded). WildFly keeps today's condition, so its behaviour does not change in 1.1.0. |
| Q2 | Early re-sweep | **Both.** Poll `QuarkusDelayedHandler.isActivated()` by reflection and sweep when it turns true; and run a startup fast sweep (about every 250 ms for the first 10 to 20 s after install) as the safety net. The fast sweep is a startup window in the shared sweep policy, switched on by the container: Quarkus on, WildFly off in 1.1.0 ([#223](https://github.com/ddeuchert/logaperture/issues/223) for WildFly). The interval and window are sized from a measured sweep cost on a large app. |
| Q3 | Handler names | Type-based only: `console`, `file`, `syslog`, `socket`. Configured names are [#222](https://github.com/ddeuchert/logaperture/issues/222). |
| Q4 | Quarkus versions | The tested minor (3.39.x) plus the current LTS stream, both in the IT; the guide names exactly those. Any other version is not blocked: it degrades to the periodic sweep plus the fast sweep, and `doctor` reports what could not be found. |
| Q5 | Plain-JVM JUL | Split out: [#221](https://github.com/ddeuchert/logaperture/issues/221), same milestone, built right after this on the shared gate. #114 is Quarkus only. |
| Q6 | Sample module | In the reactor behind a profile, off in the default build; a separate CI job activates it and the release workflow always does. |
| Q7 | `doctor` autoflush | On Quarkus, the autoflush finding on Quarkus's own default handlers is reported as info, marked as a framework default. A warning stays for handlers a user created. |
| Q8 | `logctl env` | Prints `Quarkus <version>`, read from the `quarkus-core` jar manifest (a file read, never touching JUL). |
| Q9 | Slot | Release `1.1.0`, ahead of Spring Boot (which stays in 1.2 per §17.1). The spec merged first. The implementation was held as a draft PR until `develop` became the 1.1.0 trunk (release lines, #257); the hold ended 2026-10-24 and the PR goes through review and `WildFlyContainerIT` like any other. #114's Project Status becomes Merged when the PR merges. |

## Where things stand

| Area | Status on Quarkus 3.39.5 (spike) |
|---|---|
| Engine: aggregate, services, state store, `logctl` | Works unchanged |
| Level control, `for` expiry, `sticky` across restart, reset | Works |
| `drop` / `trim` rules | Work, but only after the first sweep |
| Detection | Missing: today's agent does nothing on Quarkus and breaks nothing |
| Readiness gate | The WildFly gate is **unsafe** here as it stands |
| Handler catalogue, `set handler`, handler-floor hint, `top` | Miss Quarkus's real handlers |
| TRACE on a default build | Silently does nothing |
| Dev mode | Works; not a supported target |
| Native image | Never (§15.9) |

## What has to be built

1. **A safe readiness gate (Q1).** The WildFly gate fires on the first line of
   `QuarkusEntryPoint.main` and can make the first JUL call before Quarkus sets its build-time
   floor (`logging.initial-configurator.min-level`). JBoss LogManager fixes that floor per logger
   at creation, so winning the race permanently changes the app's logging (floor `ALL` instead of
   `DEBUG`: 3 of 21 timing-shifted runs, 2 of 2 at a 1 ms poll). The Quarkus condition adds
   "`org.jboss.logmanager.LogContext` is loaded", so the agent only ever joins an initialisation
   the app already started (16 of 16 runs correct). Shared code, parameterised condition; the
   WildFly condition is untouched. Spike finding 2.
2. **`QuarkusContainerIntegration` (Q4, Q8).** Detection without touching JUL: the
   `QuarkusEntryPoint` resource *and* a Quarkus launch (`quarkus-run.jar`,
   `io.quarkus.runner.GeneratedMain`, a `*-runner.jar`, or `quarkus-application.dat`), because a
   bootstrap jar on a non-Quarkus classpath otherwise triggers a false positive. The host is the
   existing JBoss LogManager container, generalised to take a container name. The version comes
   from the `quarkus-core` jar manifest. Findings 1, 7.
3. **Early re-sweep, two mechanisms (Q2).** Quarkus's startup logging setup replaces our filters
   on its root `QuarkusDelayedHandler`.
   - *Exact trigger:* poll `QuarkusDelayedHandler.isActivated()` (found by reflection) and run
     the verification sweep when it turns true. In the spike this restored everything at +1.8 s,
     before the app finished starting. If the reflection finds nothing, `doctor` says the early
     sweep is off and why.
   - *Safety net:* a startup fast-sweep window in the shared sweep policy, enabled by the
     container. It needs no Quarkus internals, so it covers Quarkus versions where the reflection
     fails and dev-mode live reload. Sweeps are idempotent, so extra ticks only cost time; the
     slice measures one sweep on a large app and fixes the interval and window from the result.
   - *Measured (slice 3):* one verification sweep over a JBoss LogManager tree with 20 sticky
     overrides took a median 3.9 ms for 1 000 loggers, 16 ms for 5 000 and 57 ms for 20 000 (p95
     81 ms), on the development machine, after warm-up. The cost is linear in logger count. So the
     window is fixed at **250 ms for 15 s**, scheduled with a fixed *delay* so a slow sweep on a
     very large app throttles itself instead of piling up; at 20 000 loggers that is at most about
     a fifth of one background thread for 15 s. The `isActivated()` poll is every 50 ms for up to
     2 minutes and costs a reflective call.
   - The JBoss LogManager config listener never fires on Quarkus, so it is not used. Finding 5.
4. **A transparent delayed handler (Q3).** Quarkus's console and file handlers are children of
   `QuarkusDelayedHandler`. The JUL adapter catalogues those children in its place for
   `list handlers`, handler-level control, `ALL_HANDLERS` fan-out, the handler-floor hint, `top`
   and `doctor`, while keeping the delayed handler as the attach point for filters. Names are by
   type: `console`, `file`, `syslog`, `socket`. Findings 3, 5, 6.
5. **Min-level honesty.** After `set logger`, check `isLoggable(level)`. If it's false, warn and
   name `quarkus.log.min-level`. Add a `doctor` finding, and stop `list loggers` from reporting
   an effective level below the floor. The check is generic, not Quarkus-specific. Finding 4.
6. **`doctor` autoflush on Quarkus (Q7).** The finding on Quarkus's own default handlers is
   info, marked as a framework default. Handlers a user created keep the warning.
7. **Tests and docs (Q6).** A `logaperture-sample-quarkus` module behind a profile, plus
   `QuarkusIT`, run on the tested minor and the current LTS stream. A separate CI job activates
   the profile; the release workflow always does. The guide pages listed in the Functional
   summary.

## Acceptance

`QuarkusIT` runs `java -jar quarkus-run.jar` with the agent and checks:

- boot is intact and the min-level floor is unchanged (the app's logging semantics are not
  altered by the agent);
- DEBUG works; TRACE on a default build produces the warning, not silence;
- a sticky `drop` rule is effective before the first request, with the exact trigger, and, with
  reflection disabled, within the fast-sweep window;
- sticky overrides survive a restart;
- `list handlers` shows `console` and `file`, and `top` counts the main log file;
- `WildFlyContainerIT` still passes with the shared gate (and on every
  update of the PR branch).

## Behaviour to document rather than change

- Overrides bypass per-category `quarkus.log.category."X".min-level`. That is only applied when
  Quarkus configures logging at startup.
- Dev mode runs with floor `ALL`, so TRACE works there even when it doesn't in production.
- Versions other than the two tested ones run in a degraded mode (Q4).

## Out of scope

Native image; the Quarkus extension (`quarkus ext add logaperture`, §18.2); dev-mode live reload
as a supported target; `quarkus.log.*.async` handlers (two nesting levels, untested); syslog and
socket handlers beyond naming; configured handler names
([#222](https://github.com/ddeuchert/logaperture/issues/222)); the fast sweep on WildFly
([#223](https://github.com/ddeuchert/logaperture/issues/223)); plain-JVM `java.util.logging`
([#221](https://github.com/ddeuchert/logaperture/issues/221)).

## Not established

From the spike, to be closed by the slice's tests: Quarkus versions other than 3.39.5; async
handlers; storm detection on Quarkus (confirmed by code, not by a run); `--vendor-defaults` on
Quarkus; container-image packaging; whether the `LogContext` condition is also right for WildFly
(deliberately not needed, Q1).

## Estimate and slot

About 6.5 to 7.5 working days for one developer, spec sign-off included. Day 1 is this spec; the
rest follows the spike's plan (shared gate and `WildFlyContainerIT` regression; the
integration, exact trigger and fast-sweep window; the transparent delayed handler; min-level
honesty and the `doctor` changes; sample module and IT; guide). Q2 adds half a day to the spike's
figure. Release `1.1.0` (Q9).
