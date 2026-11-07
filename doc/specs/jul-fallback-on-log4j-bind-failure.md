# Bind `java.util.logging` when Log4j 2 cannot be bound (`log4j-jul`)

Status: **Signed off 2026-11-06** (decisions J1 to J5 below, every recommendation taken); built the same day.
Release `1.1.0`.

Parent specs: [`class-loader-detector.md`](class-loader-detector.md) (C1, which this amends; C4, C8),
[`log4j2-adapter.md`](log4j2-adapter.md) (A10), [`surface-on-bind-failure.md`](surface-on-bind-failure.md)
(D1, D2), [`plain-jvm-jul.md`](plain-jvm-jul.md) (#1). Tracking issue:
[#311](https://github.com/ddeuchert/logaperture/issues/311), under #302.

## Functional summary

After this feature, the user will be able to:

- Run an application that uses `-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager`
  with a Log4j 2 that LogAperture cannot control (older than 2.20.0, or one that never started) and
  still use `logctl list loggers`, `set logger` and the rest on its `java.util.logging` loggers,
  as before Log4j 2 support existed, instead of having no level control at all.
- Read in `logctl doctor` and in the startup output that Log4j 2 was found but not controlled and
  that `java.util.logging` is controlled instead.

Guide pages this changes: `troubleshooting.md` (what `doctor` shows for an old Log4j when `log4j-jul` is
in use).

Not part of this feature: any change for an application on Log4j 2 without `log4j-jul`, or on a Log4j
the adapter binds; a retry of a Log4j that gave up (class-loader-detector.md, "As built").

## The problem

With `java.util.logging.manager` naming `log4j-jul`'s manager, the `none` integration does not install
the JUL context (class-loader-detector.md C1): that tree is only a front for Log4j 2, and a second
context would list every logger twice. It decides this from the system property at startup, before
it knows whether the Log4j 2 bind will succeed. Where it does not, the application has no control at
all, though before the Log4j 2 watch (#310) it had a working JUL context:

- a Log4j older than 2.20.0, which the adapter refuses (A10): outcome `VERSION`;
- a Log4j whose adapter could not be created or threw: `FAILED`;
- a Log4j whose first context has not started 3 s after its `LoggerContext` class loaded; the detector
  gives up once and never offers that loader again: `GAVE_UP`.

## Findings from the code (2026-11-06)

- `BoundInstance.fail` is the one place a bind ends without a context, and it already knows which of
  the three it is (`BindFailures.Kind`) and which loader (`owner`). It tells the sink only for `VERSION`
  and `FAILED` (`ContextSink.unbound`, D2 of surface-on-bind-failure).
- Once `fail` has run, the scanner never offers that loader again and the instance is closed, so that
  loader's Log4j 2 cannot bind later. A fallback JUL context therefore cannot double-list that tree.
- `NoneContainerIntegration.activate` is where C1 is decided, and the only place that can install the
  JUL context (`installSystemContext`, which waits for JUL itself to load, `JulLoadDetector`).
- A Log4j in another loader (a webapp with its own copy) is a different tree from the one
  `log4j-jul` fronts. A failure there must not bring JUL in, or a healthy `log4j-jul` system Log4j that
  binds a moment later would be listed twice.
- `log4j-jul`'s manager must be loaded by the system class loader, so the Log4j it fronts is the one
  visible there. That is the only owner whose failure matters here.

## Decisions

| Id | Decision | Options | Recommendation |
|---|---|---|---|
| J1 | Which outcomes fall back to JUL | (a) `VERSION`, `FAILED` and `GAVE_UP`. (b) `VERSION` and `FAILED` only. | **(a).** The issue names the give-up, and a JVM that gave up has no control either way. The give-up is rare under `log4j-jul` (the manager starts the context on the first JUL log), so the risk of fallback touching JUL early is small, and J4 keeps it from starting anything. |
| J2 | Whose failure counts | (a) Any Log4j 2 owner. (b) Only the system class loader's, the one `log4j-jul` fronts. | **(b).** A webapp's failed Log4j is not what the JUL tree fronts; (a) would double-list a healthy system Log4j (see Findings). Owner identity is compared with `==`, as the detector always does. |
| J3 | How the detector tells the `none` integration | (a) A new `ContextSink.notBound(framework, owner)`, forwarded by `ContainerContextSink` to a new default method `ContainerIntegration.frameworkNotBound(framework, owner)` that the `none` integration implements; it returns whether it took over, so the sink can tell `doctor`. (b) A callback passed to `FrameworkDetection.start` and then to `activate`. (c) The `none` integration polls `BindFailures`. | **(a).** The SPI already carries `adoptContext` and `releaseContext` from the detector to the integration (C6), and this is the same direction; `none` is the only integration that needs it, so it is a default no-op. (b) changes `activate`'s signature for every container; (c) is polling for something that is pushed today. The `unbound` that publishes the control surface (D1, D2) keeps its meaning and is called as before. |
| J4 | What the fallback installs, and when | (a) The same JUL context the `none` integration installs without `log4j-jul` (`JulAdapterFactory.forPlainJvm`, through `installSystemContext`, which waits for JUL to be loaded and runs once). (b) A JUL context made directly at the moment of failure. | **(a).** It is the path that already guards failures, shares the "first context ready" callback and waits for JUL rather than forcing it to load. Once only: a second failure notice (two owners) does not install a second context. |
| J5 | What the user is told | (a) Nothing beyond what `BindFailures` already says. (b) One `Diagnostics` line when the fallback installs, and the existing `doctor` row stays (`log4j2.version` / `log4j2.not-bound`) with a note in its detail that `java.util.logging` is controlled instead. | **(b).** A silent change of which tree is controlled is the "reassuring answer that does nothing" of class-loader-detector.md C8 the other way round. The check ids do not change (`reference/doctor-checks.md` is untouched); only the detail text. |

## Scope

1. `ContextSink` and `ContainerContextSink`: the notice of J3, with the kind and the owner, for all three
   outcomes (`GAVE_UP` included, which still does not publish the control surface). `BoundInstance.fail`
   makes the call after recording the row.
2. `ContainerIntegration.frameworkNotBound(...)`: default no-op.
3. `NoneContainerIntegration`: when `java.util.logging.manager` names a framework's manager (the C1 check),
   keep skipping JUL at startup; on `frameworkNotBound` for that framework's owner being the system
   class loader (J2), install the JUL context once (J4) and log the line of J5.
4. `BindFailures`: the `doctor` detail for the three kinds says that `java.util.logging` is controlled
   instead, when it is. Wording only.
5. Spec and guide: C1 in `class-loader-detector.md` gains "unless the Log4j 2 bind is refused or gives
   up (this spec)"; the guide pages above.

## Acceptance

- **Unit (core):** `BoundInstance` notifies the sink with the kind and owner for `VERSION`, `FAILED` and
  `GAVE_UP`, and for a successful bind does not; `ContainerContextSink` forwards it to the integration
  and `BoundInstance` still publishes the surface only for `VERSION` and `FAILED` (its existing test).
- **Unit (none):** a notice for the system class loader installs exactly one (the property check at startup is the
  existing C1 behaviour, covered by the IT); a notice for another loader installs none;
  a second notice installs no second.
- **Integration (`Log4jJulFallbackEndToEndIT`):** a fixture on `log4j-jul` with Log4j 2.19.0, run under the
  agent: the worker logger is listed once, `set logger` changes it and the application's output follows,
  `doctor` shows `log4j2.version` with the note. The healthy case (2.20+ behind `log4j-jul`: no JUL context)
  is covered by the unit tests only; there is no end-to-end run for it.
- A plain JVM on Logback, on JUL alone, and on Log4j 2 without `log4j-jul` is unchanged.

## As built

`BoundInstance.fail` calls `ContextSink.notBound(framework, owner)` for every kind of failure, after the row and
`unbound`. `ContainerContextSink` forwards it to `ContainerIntegration.frameworkNotBound`; `NoneContainerIntegration`
arms the fallback only where it skipped JUL for `log4j-jul`, installs it once for the system class loader's Log4j
(owner compared with `==`, and the manager property naming that framework's manager), and logs the line of J5. `BindFailures.noteFallback` adds the note to the row's detail.
The pieces are exercised by `BoundInstanceTest`, `ContainerContextSinkTest`, `NoneContainerIntegrationTest` and the IT.

## Not established

- The fallback is claimed when the install is scheduled, which runs on the detector thread. If that install fails
  (it logs an error), `doctor` still says `java.util.logging` is controlled. Rare; not handled.
- A healthy Log4j whose context starts later than 3 s is a `GAVE_UP` and now gets the JUL front, permanently
  (no retry, as before). Accepted in J1.

- A Log4j whose context starts more than 3 s after its marker class loaded, while the fallback has
  already installed JUL: the Log4j is not bound (no retry) and the JUL tree fronts it, which is the
  control the user has. Not tested with a late start.
- Whether the JUL adapter's configuration-reset hook (`forPlainJvm`) behaves under `log4j-jul`'s manager
  as under the default one: it worked in 1.0, and the integration test above covers `set logger`, not a
  reset.
