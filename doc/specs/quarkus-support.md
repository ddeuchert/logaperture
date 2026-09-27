# Quarkus JVM mode support — overview

Status: **Overview, pre-sign-off.** This is not yet an implementation spec. It collects what the
spike established and the decisions a spec has to make. The spec is written from it once the
release slot is chosen on 2026-10-04 (top-level spec §17.1).

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
  `doctor`, `storms`.
- See Quarkus's own handlers in `logctl list handlers` as `console`, `file` and so on, and raise
  or lower them.
- Get a clear warning when `logctl set logger X TRACE` can't take effect because the app was
  built with a higher `quarkus.log.min-level`, and what to rebuild with.
- Do the same on a plain `java -jar` app that logs through `java.util.logging` alone.

## Where things stand

| Area | Status on Quarkus 3.39.5 (spike) |
|---|---|
| Engine: aggregate, services, state store, `logctl` | Works unchanged |
| Level control, `for` expiry, `sticky` across restart, reset | Works |
| `drop` / `trim` rules | Work, but only after the first sweep (see below) |
| Detection | Missing: today's agent does nothing on Quarkus and breaks nothing |
| Readiness gate | The WildFly gate is **unsafe** here as it stands |
| Handler catalogue, `set handler`, handler-floor hint, `top` | Miss Quarkus's real handlers |
| TRACE on a default build | Silently does nothing |
| Dev mode | Works; not a supported target |
| Native image | Never (§15.9) |

## What has to be built

1. **A safe readiness gate.** The WildFly gate fires on the first line of `QuarkusEntryPoint.main`
   and can make the first JUL call before Quarkus sets its build-time floor
   (`logging.initial-configurator.min-level`). JBoss LogManager fixes that floor per logger at
   creation, so winning the race permanently changes the app's logging (floor `ALL` instead of
   `DEBUG`: 3 of 21 timing-shifted runs, 2 of 2 at a 1 ms poll). The fix is one more
   class-presence condition, "`org.jboss.logmanager.LogContext` is loaded", so the agent only ever
   joins an initialisation the app already started (16 of 16 runs correct). Spike finding 2.
2. **`QuarkusContainerIntegration`.** Detection without touching JUL: the `QuarkusEntryPoint`
   resource *and* a Quarkus launch (`quarkus-run.jar`, `io.quarkus.runner.GeneratedMain`, a
   `*-runner.jar`, or `quarkus-application.dat`), because a bootstrap jar on a non-Quarkus
   classpath otherwise triggers a false positive. The host is the existing JBoss LogManager
   container, generalised to take a container name. Findings 1, 7.
3. **An early re-sweep.** Quarkus's startup logging setup replaces our filters on its root
   `QuarkusDelayedHandler`. The periodic sweep restores them idempotently, but with the default
   30 s interval a sticky `drop` rule leaks for the first ~30 s of every start. Re-sweeping when
   `QuarkusDelayedHandler.isActivated()` turns true restored everything at +1.8 s, before the app
   finished starting. The JBoss LogManager config listener never fires on Quarkus. Finding 5.
4. **A transparent delayed handler.** Quarkus's console and file handlers are children of
   `QuarkusDelayedHandler`. The JUL adapter must catalogue those children in its place for
   `list handlers`, handler-level control, `ALL_HANDLERS` fan-out, the handler-floor hint, `top`
   and `doctor`, while keeping the delayed handler as the attach point for filters. Findings 3, 5, 6.
5. **Min-level honesty.** After `set logger`, check `isLoggable(level)`. If it's false, warn and
   name `quarkus.log.min-level`. Add a `doctor` finding, and stop `list loggers` from reporting an
   effective level below the floor. The check is generic, not Quarkus-specific. Finding 4.
6. **Plain `java -jar` on JUL.** The `none` integration also binds the JUL adapter once
   `java.util.logging.LogManager$RootLogger` is loaded, which only happens after the app itself
   touched JUL. Finding 7.
7. **Tests and docs.** A sample Quarkus module plus `QuarkusIT`. A quickstart and a limits page.

## Behaviour to document rather than change

- Overrides bypass per-category `quarkus.log.category."X".min-level`. That's only applied when
  Quarkus configures logging at startup.
- Dev mode runs with floor `ALL`, so TRACE works there even when it doesn't in production.
- Quarkus's default handlers have `autoflush` on, so `doctor` flags it on every stock app unless
  the check is tuned (Q7).

## Open decisions for the spec

- **Q1 — Shared gate or Quarkus-only gate?** The extra condition is untested on WildFly,
  including the #86/#87 launch shape. Either regression-test it and share it, or ship it
  Quarkus-only first.
- **Q2 — Early re-sweep mechanism.** Poll `QuarkusDelayedHandler.isActivated()` (exact, but a
  Quarkus internal), or a container-agnostic fast sweep for the first 10–20 s after install
  (less exact, but also helps WildFly and later Spring Boot).
- **Q3 — Handler names.** Type-based names only (`console`, `file`, `syslog`, `socket`), or also
  the configured names of `quarkus.log.handler.*."<name>"` handlers, read through
  `ConfigProvider` (about one extra day).
- **Q4 — Supported Quarkus versions.** The spike tested only 3.39.5. Pin a tested range and add
  the current LTS stream to the IT. Degrade to the periodic sweep when reflection finds nothing.
- **Q5 — Plain-JVM JUL in the same slice?** It shares the gate work (1–2 days), but serves a
  different audience.
- **Q6 — Where the sample Quarkus module lives.** In the reactor, behind a profile, or out of the
  default build. It brings the Quarkus BOM and plugin in, which lengthens cold CI builds.
- **Q7 — `doctor`'s autoflush finding on Quarkus.** Tune it, or accept a warning on every stock
  app.
- **Q8 — Name.** `logctl env` should print `Quarkus <version>`, read from the `quarkus-core` jar
  manifest (file read only).

Out of scope: native image; the Quarkus extension (`quarkus ext add logaperture`, §18.2);
dev-mode live reload as a supported target; `quarkus.log.*.async` handlers (two nesting levels,
untested); syslog and socket handlers beyond naming.

## Estimate and slot

About 6–7 working days for one developer, spec sign-off included (spike "Estimate"). Release
slot, per §17.1: a preview in `1.0.0` only if the Oct 4–13 window is free (library recipes #92
cut to 1.1 on Oct 4); otherwise a `1.1.0` of its own, ahead of Spring Boot.
