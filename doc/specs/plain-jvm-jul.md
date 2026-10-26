# Plain-JVM `java.util.logging`

Status: **Signed off 2026-10-25** (decisions #1 to #7 below; #5 amended the same day, see "As
built"). Release `1.1.0`.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §11 (compatibility matrix, the
plain `java -jar` row), §15.2 (the `ContainerIntegration` SPI and the `none` integration), §15.4 (several frameworks in one JVM),
§15.6 (the premain gotcha: never be the first `java.util.logging` caller), §17.1 (1.1.0). Evidence:
[`doc/spikes/quarkus-jvm-mode.md`](../spikes/quarkus-jvm-mode.md) finding 7, and the measurements
under "What is already established" below. Builds on: [`quarkus-support.md`](quarkus-support.md)
(the shared readiness principle, #114). Related: [#273](https://github.com/ddeuchert/logaperture/issues/273)
(`logctl attach`), [`doc/spikes/dynamic-attach.md`](../spikes/dynamic-attach.md). Tracking issue:
[#221](https://github.com/ddeuchert/logaperture/issues/221).

## Functional summary

After this feature, the user will be able to:

- Add the agent to a plain `java -jar` application that logs through `java.util.logging` and
  control it with `logctl`, the same way as on WildFly: `set logger`, `set handler`, vendor
  defaults, recipes, `drop` and `trim` rules, `top`, `doctor` and storm detection.
- See the JDK's own handlers in `logctl list handlers` as `console` and `file`, and raise or lower
  them; `logctl top` counts what the `file` handler writes.
- Have an override survive the application re-reading its `logging.properties`.

A plain JVM that logs through Logback works exactly as it does in 1.0.

Guide pages this changes: `get-started/install-plain-jvm.md` (JUL becomes a supported route with
the full feature set; Logback keeps its 1.0 note); `index.md` (the support table and the "Not
supported" line); the "needs `java.util.logging` or JBoss LogManager" notes on
`concepts/rules.md`, `how-to/silence-noise.md`, `how-to/find-disk-usage.md`, `how-to/storms.md`,
`troubleshooting.md` and `reference/doctor-checks.md`, which now include a plain JVM on JUL.
`reference/agent-options.md` does not change (no new property).

Not part of this feature: `logctl attach` (#273); controlling JUL inside a Logback application
(#1); Log4j 2.

## What is already established

- **The adapter is done.** `JulLoggingAdapter` already runs WildFly and Quarkus, and its class doc
  says it works unchanged on the JDK's own `LogManager`. Handler levels, rules, `top`, `doctor` and
  storms all go through it. What is missing is only the `none` integration binding it.
- **The signal** (spike finding 7): on a plain JUL app none of `java.util.logging.Logger`,
  `LogManager` or `LogManager$RootLogger` is loaded at `premain`; all appear together at the
  application's first `Logger.getLogger`. Waiting for `LogManager$RootLogger` means the agent is
  never the first JUL caller, so it can never pre-empt a `java.util.logging.manager` the
  application sets in `main` (§15.6). This is the principle of the Quarkus gate (#114 Q1).
- **Today a JUL-only app gets nothing.** Measured on `develop` (JDK 21.0.12): the agent loads,
  writes no line, and `logctl status` says *No LogAperture-enabled JVM found*.
- **The agent initialises JUL itself in every Logback app.** Measured on the same build with a
  Logback 1.5.18 app and `-Xlog:class+load`: without the agent, `java.util.logging.LogManager` is
  never loaded; with it, `LogManager` and `LogManager$RootLogger` load as the agent creates the
  platform MBean server, because the JDK registers its `LoggingMXBean` there. So "JUL has been
  initialised" is true in every Logback app the agent runs in, and cannot by itself mean "this
  app logs through JUL". Decision #1 rests on this.

## Decisions

All seven were agreed on 2026-10-25 from the sign-off review, each as the outcome in the last
column.

| Id | Decision | Options | Outcome |
|---|---|---|---|
| #1 | Which backend `none` binds | (a) **One backend, chosen up front:** if Logback is on the system class path (a resource lookup for `ch/qos/logback/classic/LoggerContext.class`, which loads nothing), bind Logback exactly as today; otherwise wait for JUL. (b) Bind both, each when it is initialised (§15.4). (c) Bind whichever is initialised first. | **(a)**. (b) would add a JUL context to every Logback app (the agent initialises JUL itself, see above), where `add rule`, `top` and storms would act on JUL and never see a Logback event, which is worse than not offering them. (c) is a race: a library touching JUL before Logback starts would win. (a) leaves Logback apps byte-for-byte as in 1.0. Its cost: a Logback app that also logs directly through JUL gets no control over the JUL half, as today. |
| #2 | How the signal is watched | (a) A daemon thread polls `Instrumentation.getAllLoadedClasses()` for `java.util.logging.LogManager$RootLogger`, checking once immediately; every 100 ms for the first 60 s, then every 1 s; it never gives up. (b) A one-shot class-file transformer, like the Logback gate. | **(a)**. It also finds a class that is already loaded, which `logctl attach` (#273) needs on JUL, and it does not depend on load hooks firing for JDK classes from the shared archive (CDS). It costs one scan a second in an app that never uses JUL, such as a Log4j 2 app; a scan of about 10,000 classes is well under a millisecond. The 60 s and 1 s figures follow `JBossLogManagerReadiness`. |
| #3 | Re-reading `logging.properties` | (a) Register a `LogManager.addConfigurationListener` (JDK 9+) and re-apply overrides and handler settings as soon as `readConfiguration()` or `updateConfiguration()` runs, as Logback's reset event does today. (b) Leave it to the 30 s verification sweep. | **(a)**. A few lines, and it closes up to 30 s of wrong levels after a reload. The JUL adapter registers it, but only when the `none` integration builds it (`JulAdapterFactory.forPlainJvm`), the way the Logback adapter owns its reset hook; WildFly and Quarkus are unchanged. |
| #4 | Handler names | (a) Name the JDK's handlers by type: `console`, `file`, `socket` (numbered for a second one: `file-2`), reusing Quarkus's type-based resolver; any other handler keeps its `<class>@<idhash>` token. (b) Tokens for all, as plain JUL has today. | **(a)**. `set handler console DEBUG` beats `set handler ConsoleHandler@1b6d3586 DEBUG`, and the resolver already exists (#114 Q3). It moves from `logaperture-container-quarkus` to `logaperture-adapter-jul` so both integrations can share it; the Quarkus behaviour does not change. |
| #5 | The JDK `FileHandler`'s file and limits | (a) Leave them unread: `doctor`'s `handler.unbounded-growth` and `disk.headroom` do not report on a JDK `FileHandler`, and the guide says so. `top` counts it by type (amended, see "As built"). (b) Open `java.util.logging` to the agent with `Instrumentation.redefineModule` and read the private `pattern`, `limit` and `count` fields. | **(a)** for 1.1.0, with (b) as its own `1.x` issue ([#280](https://github.com/ddeuchert/logaperture/issues/280)). The JDK keeps those fields private and `java.logging` does not open the package; (b) changes the module graph of the application's JVM, which needs its own §9 review. `doctor`'s other checks work either way. |
| #6 | JBoss LogManager on a plain JVM | An app outside WildFly and Quarkus that sets `java.util.logging.manager=org.jboss.logmanager.LogManager` itself. (a) Works through the same path, not promised: documented as untested. (b) In scope, with its own test. | **(a)**. The same signal fires and the adapter is the one WildFly uses, so it should work, but no user has asked for it and an IT variant is a day of work. |
| #7 | Startup line and `env` | (a) Unchanged: the startup line stays `LogAperture <version> active (JVM)`, and `env`'s backend line already says `java.util.logging (JUL)`. (b) Name the backend in the startup line too. | **(a)**. The information is already in `env`, and the startup line is the same on every plain JVM today. |

## Scope

1. **Backend choice (#1).** `NoneContainerIntegration.activate` checks the system class loader for
   Logback's `LoggerContext` resource. If found, the Logback path runs exactly as today. If not,
   the JUL path below runs. The check loads no class and touches no JUL.
2. **JUL readiness (#2).** A new `JulLoadDetector` in `logaperture-container-none`, with the same
   shape as `LogbackLoadDetector`: wait for the signal on a daemon thread, then run the install.
   It must only inspect class presence; it never calls into `java.util.logging` before the signal.
3. **Install.** On the signal, install one `system` context over
   `JulAdapterFactory.forPlainJvm()` (#3, #4) through the existing
   `NoneContainer.installContext`, and fire the first-context callback (MXBean, marker, banner).
   Vendor defaults, recipes, the state store and storm detection need no change.
4. **Reconfiguration (#3).** `NoneContainer.installContext` already hands the adapter its re-apply
   hook through `onReset`; the plain-JVM adapter registers it as a `LogManager` configuration
   listener. `NoneContainer.close()` removes it through `clearResetListener`.
5. **Shared resolver (#4).** Move the type-based resolver into `logaperture-adapter-jul` as
   `TypeHandlerNameResolver`; Quarkus uses it from there.
6. **Docs.** The §11 row for plain `java -jar`, and the guide pages listed in the Functional
   summary, with output captured from a real run.

## Acceptance

`PlainJulEndToEndIT` (agent module, next to `LevelControlEndToEndIT`) runs a small app that logs
through `java.util.logging` only, to a `ConsoleHandler` and a `FileHandler`, and checks:

- the agent writes its startup line only after the app's first JUL call, and the app's own
  `java.util.logging.manager` and `logging.properties` take effect (the agent never initialises
  JUL first);
- `logctl env` reports the backend as `java.util.logging (JUL)`; `list handlers` shows `console`
  and `file`, the latter as writing a file;
- `set logger com.acme FINE for 1m` makes FINE lines appear (the console follows, as on WildFly),
  and they stop after expiry;
- `set handler console WARNING` hides INFO lines; `reset handlers` restores it;
- a `drop` rule suppresses matching lines; `top` counts the bytes written to `file`; `doctor` runs
  its logger checks and raises no `handler.unbounded-growth` for `file` (#5);
- after the app calls `LogManager.readConfiguration()`, which resets levels and replaces the
  handlers with new instances, the logger override and a `console` handler override are both back
  before the call returns, the latter on the new handler, and the `drop` rule is on it too (#3);
- a `sticky` override set in one run is in force in the next once the agent has installed;
- the existing Logback `LevelControlEndToEndIT` still passes, and a Logback app shows no JUL
  context (#1).

## Behaviour to document rather than change

- A Logback app that also logs directly through `java.util.logging` gets control of the Logback
  side only (#1). With `jul-to-slf4j`, JUL records reach Logback and are controlled there.
- On a JDK `FileHandler`, `doctor` does not report the file's growth or disk headroom (#5).
- The agent installs up to about 100 ms after the application's first JUL call (#2), so the
  first lines an application logs go out under its own levels, before a resumed `sticky` override
  applies.
- An app with neither Logback nor JUL (Log4j 2, say) still gets nothing, and now costs one class
  scan a second (#2).

## Out of scope

`logctl attach` (#273), including the "already loaded" case for Logback; binding JUL next to
Logback (#1); reading the JDK `FileHandler`'s private fields (#5, [#280](https://github.com/ddeuchert/logaperture/issues/280)); JBoss LogManager on a plain JVM
as a tested target (#6); Log4j 2; Spring Boot, whose fat jar keeps Logback out of the system
class path and gets its own integration in 1.2.0.

## Not established

- Whether a load hook would see `LogManager$RootLogger` loaded from the CDS archive (not needed
  with #2 (a)).
- The cost of one `getAllLoadedClasses()` scan on a large app (estimated, not measured; the
  overhead benchmark can measure it).
- JDK 17 and 25 (the measurements above are JDK 21; the spike's finding 7 was on 21 too).

## As built

What the implementation settled or found that the decisions left open:

- **`top` and the JDK `FileHandler` (#5, amended 2026-10-25).** `top` counts only handlers that
  write to a file, and decided that from the file's path, which the JDK `FileHandler` keeps
  private, so as signed off `top` would have counted nothing on plain JUL. `HandlerDiagnostics`
  gains an additive `fileWithoutPath`: a handler named `FileHandler` whose path cannot be read is
  persistent (so `top` counts it and `list handlers` shows it as a file) but has no known file
  (`hasKnownFile()`), which is what `doctor`'s file checks now key on, so they still leave it
  alone rather than report a size cap that may well be configured.
- **A reload replaces the handlers.** `readConfiguration()` closes the JDK's handlers and creates
  new ones. The adapter's name-to-handler cache still pointed at the closed instance, so a handler
  override re-applied after a reload landed on it. Before the re-apply hook runs, the plain-JVM
  adapter now drops closed instances and names the new ones.
- **One backend, measured.** `LevelControlEndToEndIT` checks that a Logback app lists no JUL logger
  (the JDK's `global`), and `PlainJulEndToEndIT` that a JUL app does.

## Found along the way

The agent's own MBean registration initialises JUL in a Logback app (see "What is already
established"). A Logback app that sets `java.util.logging.manager` itself after Logback has
started would get the JDK default manager instead. That is 1.0 behaviour, not caused by this
feature; tracked as [#279](https://github.com/ddeuchert/logaperture/issues/279).

## Estimate and slot

About 3 working days: the backend choice and JUL detector with unit tests, 1; the configuration
listener and moving the resolver, 0.5; the JUL end-to-end IT, 1; guide and spec, 0.5. Release
`1.1.0`, first in the build order of [#272](https://github.com/ddeuchert/logaperture/issues/272),
ahead of `logctl attach` (#273), which builds on its readiness check.
