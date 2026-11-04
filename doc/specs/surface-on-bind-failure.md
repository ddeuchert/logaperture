# A control surface for a framework found and not bound

Status: **Signed off 2026-11-03** (decisions D1 to D6 below, every recommendation taken); built the same day. Release `1.1.0`.

Parent specs: [`log4j2-adapter.md`](log4j2-adapter.md) (A10, A12, Slice 7 and Slice 8 "As built") and
[`class-loader-detector.md`](class-loader-detector.md) (C8). Builds on [`cli-transport.md`](cli-transport.md)
("Discovery") and [`doctor.md`](doctor.md). Tracking issue:
[#322](https://github.com/ddeuchert/logaperture/issues/322).

## Functional summary

After this feature, the user will be able to:

- Run `logctl doctor` with no `--pid` in an application where LogAperture found a Log4j 2 it could not control
  (older than 2.20.0, or its adapter failed to start) and be told which Log4j and why, instead of "No
  LogAperture-enabled JVM found. Start the application with `-javaagent`" for an application that already has it.
- See from `logctl list loggers` in such a JVM that LogAperture is running and controls nothing, with a pointer to
  `logctl doctor`, instead of an empty table.
- Read an accurate message from `logctl attach` when an attached application has not yet logged through a framework
  LogAperture controls: it no longer says Log4j 2 cannot be controlled.

Guide pages this changes (written with the last slice of #302, as decided): `troubleshooting.md` (what `doctor`
says about an old Log4j, and how to get to it), `how-to/several-jvms.md` and `how-to/windows-service.md` (the "No
LogAperture-enabled JVM found" line, which stays but is no longer the answer for a JVM that has the agent and
a Log4j it could not bind), and `reference/doctor-checks.md` (the two rows are reachable without `--pid`).

Not part of this feature: an application whose only logging framework LogAperture does not know at all (Log4j 1,
`slf4j-simple`, JCL): nothing is found, so there is nothing to report, and the "No LogAperture-enabled JVM found"
line stays for it.

## What is true today (checked 2026-11-03)

- **The control surface is published once the first logging context is installed** (`EngineStartup.onFirstContextReady`,
  called through `ContainerContextSink.once`). Publishing registers the JMX control surface and sets the marker
  system property `logaperture.version`, which is what `logctl`'s discovery filters on
  ([`cli-transport.md`](cli-transport.md) "Discovery"): an attach, read the target's system properties, keep the
  ones with the marker.
- **A Log4j that is found and cannot be bound is not a context**, so it publishes nothing. Slice 7 records the failure
  (`BindFailures`) and `doctor` shows it as `log4j2.version` or `log4j2.not-bound`, but only once the surface exists.
- **With an explicit `--pid` it usually does exist, by accident.** `logctl` starts the JDK's JMX agent in the target,
  which creates JDK `java.util.logging` loggers (`javax.management.*`, `sun.rmi.*`), and the `none` container installs
  a JUL context for any JUL activity. Measured: a fixture whose only framework is Log4j 2.19.0, attached by pid,
  answers `doctor` with `log4j2.version / WARNING`. That is a side effect of attaching, not something LogAperture
  arranges, and it depends on JUL.
- **Without `--pid` it does not.** The marker is unset until the surface is published, so discovery finds no
  candidate and `logctl` exits 3 with "Start the application with `-javaagent:logaperture-agent.jar`", for an
  application that has it. The Functional summary of `log4j2-adapter.md` promises "told by `doctor` when Log4j was
  found but not controlled, and why"; for this JVM it is unmet.
- `AttachWatch` (the `logctl attach` path) says, after 60 s without a context, that LogAperture "cannot control" a
  backend "such as Log4j 2". That was true when it was written and is not now.

(An earlier note in `log4j2-adapter.md` and in #322 said there is "no MBean to ask" for a JVM whose only framework
fails to bind. That is wrong for an explicit pid, as above; the gap is discovery.)

## Decisions

| Id | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | How the surface comes up for a failed bind | (a) The detector tells its sink that a framework was found and not bound; the agent's sink publishes the surface and the marker (once), with no context. (b) Publish at `premain`, always, whatever is installed. (c) A second property (`logaperture.agent`) set at `premain`, used only by discovery to name the JVM and say "LogAperture is loaded, nothing controlled yet"; the surface stays as it is. (d) Leave it, and narrow the promise in the Functional summary of `log4j2-adapter.md`. | **(a).** The failure is exactly when a user needs `doctor`, and (a) makes it deterministic where today it rests on JUL activity that the attach happens to cause (c leaves that fragility in place). (b) gives up "marker means the control plane is up" for every JVM and every container, including ones with a context a moment later. (d) leaves the most common old-Log4j case (an application and nothing else) with a misleading message. The marker's meaning becomes "the control plane is up: there is something to control or something to explain". |
| D2 | Which failures publish | (a) A framework that said its version is unsupported, and one whose adapter threw or could not be created (`BindFailures.Kind.VERSION` and `FAILED`). (b) Also a bind that gave up after 3 s with no started context (`GAVE_UP`). | **(a).** A `GAVE_UP` is usually a Log4j on the class path that the application never used (Slice 7 made it `INFO` for that reason); publishing for it would make every JVM with an unused `log4j-core` a discoverable "LogAperture" JVM with nothing in it. If a context is installed it publishes anyway, and the `INFO` row is there. |
| D3 | What publishing with no context does besides publishing | (a) Publish only; the vendor-defaults audit stays with the first real context, as today. (b) Run the whole first-context callback, audit included. | **(a).** The audit records that the vendor defaults file was applied; with no context nothing was applied (the comment in `AgentBootstrap` says so). The callback is split in two: *publish* (idempotent, run by the first context **or** a failed bind) and *audit* (run by the first context). A context installed later still audits. |
| D4 | What `logctl list loggers` says when the agent controls nothing | (a) Nothing new: an empty table. (b) One line when the list is empty: "No loggers: LogAperture is running but controls no logging context in this JVM. `logctl doctor` says why." | **(b).** An empty table reads as "no loggers", which is not what is true; the line costs nothing and applies to the empty case however it arose. Text output only; `--json` keeps its empty list. |
| D5 | The `AttachWatch` message | (a) Reword both lines: waiting for a framework LogAperture controls (Logback, `java.util.logging`, Log4j 2.20 or later); if it found a framework it could not control, `logctl doctor` says why. (b) Leave it. | **(a).** (b) tells a Log4j 2 user it cannot work, which has been false since #302. |
| D6 | The discovery message when nothing is found | (a) Unchanged. (b) Add "or the application's logging framework is not one LogAperture supports". | **(a).** With D1 the cases where the agent is present and found something are candidates; where it found nothing the existing line is still the right first thing to check, and the supported-backend list is in the guide. |

## Scope

1. **`ContextSink` gets `unbound(String framework)`** (default: nothing). `BoundInstance.fail` calls it for a failure of
   kind `VERSION` or `FAILED` (D2), after recording the row. `ContainerContextSink.unbound` runs the *publish*
   callback, once, swallowing a failure as `add` does ("an install that fails is a `Diagnostics` line").
2. **`EngineStartup.onFirstContextReady` is split** into `onPublish` (register the control surface, set the marker,
   print the banner; idempotent, a second call does nothing) and the vendor-defaults audit, which the first
   *context* runs (D3). `ContainerContextSink.once` is still the guard; a context that arrives after a failed bind
   finds the surface up, audits, and does not publish again.
3. **The banner** for a surface with no context says so: `LogAperture <version> active (JVM): no logging context
   yet, see logctl doctor`, once; the context banner is unchanged. (The WARN naming the framework and why was already printed.)
4. **`logctl list loggers`** prints the line of D4 in text mode when the list is empty.
5. **`AttachWatch`** messages per D5.
6. **Not changed:** the marker's name and type, the MBean, `--json`, the `none`, `wildfly` and `quarkus` containers'
   own readiness, discovery, and what `doctor` prints (the rows were built in Slice 7).

## Tests

- **Unit.** `BoundInstance` calls `unbound` for a too-old framework and for one that threw, not for a give-up and not
  for a bind that worked; `ContainerContextSink.unbound` publishes once, a later `add` audits and does not publish
  again, and an `unbound` after an `add` does nothing; `EngineStartup`'s publish is idempotent and the audit runs
  only with a context; the `AttachWatch` text; `list loggers` with an empty list (text and `--json`).
- **Real process, `BundledLog4j2EndToEndIT`** (these are the two tests Slice 8 could not write):
  - A fixture on **Log4j 2.19.0** (copied into `target/` by `maven-dependency-plugin:copy`, so it does not clash with
    the cell's version): **before any attach**, a plain attach that only reads system properties sees
    `logaperture.version` (the discovery filter; this is what fails today), and then `doctor` has
    `log4j2.version / WARNING` naming 2.19.0 and `list loggers` shows no Log4j context.
  - A fixture that loads Log4j's `LoggerContext` class and never starts a context: nothing is published for the
    give-up (D2), and `doctor` still has `log4j2.not-bound / INFO` once something else publishes (an attach).
  These run once, in the default cell (Slice 8, M8-5), not in every matrix cell.
- **`logctl` end to end** with no `--pid` against the 2.19.0 fixture: not added as a separate test. Discovery's only
  filter is the marker, which the 2.19.0 test reads with the same plain attach, and `CliEndToEndIT` uses a fake control
  surface rather than the real agent. (As built, below.)

## As built

- `ContextSink.unbound(framework)` (default nothing); `BoundInstance.fail` calls it for a failure of kind `VERSION` or
  `FAILED`, never for a give-up; a sink that throws is a `Diagnostics` line. `ContainerContextSink` takes the extra
  callback (the old constructor keeps a no-op) and `FrameworkDetection.start` passes it through.
- `EngineStartup.onSurfaceNeeded` publishes with no context; `publishControlSurface` is synchronized and idempotent
  (the marker says it is up), so a context after a failed bind audits the vendor defaults through the unchanged
  `onFirstContextReady` and does not publish again, and the reverse order does nothing twice. The no-context banner is
  `LogAperture <version> active (JVM): no logging context yet, see logctl doctor`.
- `logctl list loggers` (text) with no loggers says `No loggers known yet. LogAperture is running; if it controls no
  logging context in this JVM, `logctl doctor` says why.` (the first sentence is the old message, so the case of a
  context that has simply logged nothing yet is still true); `--json` is unchanged. `AttachWatch`'s two lines name
  Log4j 2 (2.20 or later) and point at `doctor`.
- Real process, `BundledLog4j2EndToEndIT`, once in the default cell: a Log4j 2.19.0 fixture (copied into `target/` by
  `maven-dependency-plugin:copy`) has the marker set by a plain attach, before any JMX connection, and `doctor` says
  `log4j2.version / WARNING`; the test fails with the new call disabled. A fixture that never starts a context
  publishes nothing for the give-up (marker unset after 5 s), and `doctor` still has `log4j2.not-bound / INFO` once an
  attach brings the surface up.

## Estimate and slot

About a day: the sink and the split callback, a few lines each; the CLI line and the `AttachWatch` text; the two
integration tests and the unit tests. Release `1.1.0`, with #302 (the Functional summary of `log4j2-adapter.md`
promises it).
