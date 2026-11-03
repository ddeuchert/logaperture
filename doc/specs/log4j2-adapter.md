# Log4j 2 adapter

Status: **Signed off 2026-11-01** (decisions A1 to A12 below, every recommendation taken). Release `1.1.0`.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.3 (per-framework hook map),
§5 (level control), §7 (rules), §11 (compatibility matrix), §15.4 to §15.8 (containers),
§17.1 (1.1.0). Evidence: [`doc/spikes/log4j2-adapter.md`](../spikes/log4j2-adapter.md), which this
spec cites by probe id (`A3`, `SB7`, `TC6` and so on) rather than repeating. Depends on:
[`class-loader-detector.md`](class-loader-detector.md) (#301), which finds Log4j in a fat jar, a
webapp or a deployment and decides which backends bind in one JVM (its C1). Builds on:
[`plain-jvm-jul.md`](plain-jvm-jul.md) (the readiness principle), [`rule-pipeline-foundation.md`](rule-pipeline-foundation.md),
[`drop-rule.md`](drop-rule.md), [`trim-rule.md`](trim-rule.md),
[`handler-floor-control.md`](handler-floor-control.md). Tracking issue:
[#302](https://github.com/ddeuchert/logaperture/issues/302).

## Functional summary

After this feature, the user will be able to:

- Add the agent to an application that logs through **Log4j 2** (2.20.0 and later) and control it
  with `logctl`, the same way as on WildFly: `set logger`, `set handler`, vendor defaults, recipes,
  `drop` and `trim` rules, `doctor`, and storm detection.
- Do that in four setups: a plain `java -jar`; a Spring Boot fat jar on `spring-boot-starter-log4j2`;
  a Tomcat webapp that bundles its own `log4j-core`; and a WildFly deployment that excludes the
  server's own Log4j API.
- See a Log4j appender in `logctl list handlers` by the name it has in `log4j2.xml`, and raise or
  lower it.
- Have an override survive Log4j reloading its configuration, and survive Spring Boot applying its
  own `logging.level` settings.
- Be told when a Spring Boot Actuator change to a logger is being held back by an override.
- Be told, by `logctl doctor`, when Log4j was found but not controlled, and why.

`logctl top` does not count Log4j 2 output in 1.1.0 (A6; a nice-to-have if time allows, [#306](https://github.com/ddeuchert/logaperture/issues/306)).

Guide pages this changes: `get-started/install-plain-jvm.md` (Log4j 2 route, and the fat jar);
a new `get-started/install-tomcat.md` (shared with the detector spec); `get-started/install-wildfly.md`
(the deployment that excludes the server's Log4j API); `concepts/rules.md`,
`how-to/silence-noise.md`, `how-to/storms.md` and `how-to/find-disk-usage.md` (which backends
support what, and the 2.25.0 to 2.25.3 trim limit); `troubleshooting.md` (also the warning that a
change was undone by an override, A1); `concepts/tiers-and-expiry.md` (what `reset` gives back after a
native change, A2); `reference/doctor-checks.md`
(three new checks, A12); and `index.md` (the support table and the "Not supported" line, which today
names Log4j 2). `reference/agent-options.md` does not change.

Not part of this feature: `logctl top` on Log4j 2 (A6); Log4j 1 and reload4j; a warning for logger
names no bound backend can reach (#303); Spring Boot with Logback, and Spring Boot's own
integration (1.2.0).

## What is already established

- **Everything needed is reachable through log4j-core's public API, with no instrumentation**
  (spike, plain-JVM result): levels on `LoggerConfig`, one context-wide `Filter`, and a wrapper
  appender in each `LoggerConfig` slot. A reconfiguration wipes all three; a public property
  listener re-applies them (R0 to R2).
- **It holds in all four setups**: plain JVM, Spring Boot 3.5.16 (Log4j 2.24.3) and 4.1.2
  (2.25.5), Tomcat 11.0.27 with two Log4j versions side by side, WildFly 26.1.3 with a deployment
  that excludes the server's Log4j API (probes SB4 to SB10, TC1 to TC7, WF1 to WF7).
- **The floor is 2.20.0**, decided 2026-11-01: below it the fluent API skips filters, so `drop` and
  counted rules cannot see its events (I-F), and `getExplicitLevel()` only arrives in 2.19.
- **WildFly mostly does not need this adapter.** A deployment that uses WildFly's own Log4j API
  module is already JBoss LogManager's, and the shipped agent covers it (WF1, `w1`). This adapter
  is for the deployment that excludes that module.
- **Not established** is listed at the end; the biggest items are the gate-counts split (A3) and
  `top` (A6).

## Decisions

| Id | Decision | Options | Outcome |
|---|---|---|---|
| A1 | Spring Boot Actuator, or any native change, against an active override | (a) Re-assert silently (SB7 as probed: the change returns `204` and is undone with no message). (b) Re-assert, record it in the audit trail, and warn when the change was not part of a configuration swap. (c) Treat a native change as the new baseline and drop the override, like `reset --to-native`. (d) Let the native change win and drop the override. | **(b).** It keeps the contract every other backend has, that an override holds until it is reset or expires (§5, §15.5); §9.6 rules out (a), since a suppressed change must never be silent. (c) and (d) make an override unreliable on exactly the apps with Actuator exposed, the ones where someone else is also changing levels. The warning is `Diagnostics`, once per logger name per minute, and it names the override and its owner, so whoever used Actuator can find out. A change arriving within 5 s of a configuration swap is Spring's startup `logging.level` (SB6) and is re-asserted without a warning, only in the audit trail. WildFly's management CLI has the same question; it is not changed here. |
| A2 | What `reset` restores after a native change | (a) The level from before LogAperture first touched the name. (b) The level the native source last set, which becomes the baseline when drift is re-asserted. | **(b).** After Spring wrote `INFO` on `app.demo` (SB6), a reset that restored the pre-Spring `DEBUG` would undo the user's own `application.properties`. The baseline is updated from the drifted value at the moment it is re-asserted (A1), never from a value LogAperture wrote itself. |
| A3 | Where a trim verdict is computed | (a) Gate computes the verdict and counts; the slot wrapper recomputes only the trim choice, without counting (the spike's recommendation, **not probed**). (b) A `ContextDataProvider` carries the verdict on the event, registered by service file (probed, works on a plain JVM). | **(a), subject to the prototype in "Acceptance".** (b) cannot link in a fat jar or a webapp, and cannot be registered by `logctl attach` (SB11, "Gate-to-render hand-off"), so it would exclude three of the four setups. Needs one change in `core`: a way to ask `RuleGate` for the trim choice for an event without counting a hit (today `evaluate` counts). If the prototype shows (a) cannot be made correct, the fallback is trim on a plain JVM only, by (b), and a documented limit; that comes back to sign-off before it is built. |
| A4 | Trim on 2.25.0 to 2.25.3, and the synthetic class name | (a) Trim is a no-op on 2.25.0 to 2.25.3, with a `doctor` finding; elsewhere set a `ThrowableProxy` subclass on the rebuilt event. (b) Drop trim support for Log4j 2 until every version works. (c) Rebuild the layout. | **(a).** On 2.25.0 to 2.25.3 both converters render from the `Throwable`'s class with no proxy in the path, so no public hook reaches the output; the JUL adapter makes the same fail-safe choice for structured formatters (#83). (b) throws away trim on every other version. (c) is not possible: `AbstractAppender` has no `setLayout` (T1). The proxy subclass is the most version-sensitive part of the adapter, so it gets a rendering test on every supported minor (A11). |
| A5 | What a "handler level" is | (a) Log4j's `AppenderRef` level, set on every reference to the appender, in every `LoggerConfig`. (b) Not supported (`hasHandlerLevels()` false, as on Logback). | **(a).** There is no appender-level setter, but `removeAppender` plus `addAppender(appender, level, filter)` on the `LoggerConfig` changes the reference level (H1), and that is the closest thing Log4j has to the JUL handler level the SPI models. An appender referenced from several `LoggerConfig`s takes the new level on each; it reports the most restrictive of its current levels. The adapter tracks the values itself, because `getAppenderRefs()` goes on reporting the old level (H1). A handler is named by its name in `log4j2.xml` (no resolver needed, unlike JUL). Appenders the wrapper replaced still list under their original names. |
| A6 | `top` (byte counting) for Log4j 2 | (a) Ship in 1.1.0. (b) Defer, with its own issue; `top` says a Log4j 2 context is not counted. | **(b)**, tracked as [#306](https://github.com/ddeuchert/logaperture/issues/306) (1.1.0, low priority: built if there is time, else moved to `1.x`). The slot wrapper is the natural home, but a wrapper sees the event, not the bytes: counting means calling the layout a second time, or hooking the appender's manager, and neither was probed. The cost and the correctness of both are open (spike, "Not established"). Shipping the rest on schedule is worth more than shipping a guess. `top` shows the context as "not counted" and `installByteCounting` stays the SPI default. |
| A7 | Context names | (a) The class loader's identity hash. (b) The name Log4j gives its context: `log4j-web`'s context path (`/appA`) in a webapp, else `log4j2` for the first context in the loader, `log4j2-2`, … for further ones. | **(b).** The key must survive a redeploy (TC3) and the hash does not. It carries the `log4j2:` prefix (`class-loader-detector.md` C7). `list loggers` shows it in the CONTEXT column. Every context seen in the spike had exactly one Log4j context per copy; more than one is untested (see "Not established"). |
| A8 | A logger name no `LoggerConfig` has | (a) Pre-set creates a `LoggerConfig` of its own (native behaviour of `Configurator.setLevel`, L3b), recorded in the baseline as "created by LogAperture", removed by `reset`, and not shown as a native config in `list logger`. (b) Refuse to set a level on a name Log4j has not seen. | **(a).** It is what §8.5 "Known" means and what Log4j already does. The baseline records whether the name had its own `LoggerConfig` and its explicit level (possibly none); reset sets the level back or removes the `LoggerConfig` it created (L3c, L3e). The adapter sets levels on the context it holds, never through `Configurator`'s static helpers, which find their context from the caller's loader (SB3). |
| A9 | `doctor`'s facts about a file appender | (a) File name and whether the appender rolls (by type), nothing about sizes or counts. (b) Read the triggering policy and strategy. | **(a).** The file name is public on `FileAppender` and `RollingFileAppender`; the policies are not a stable API across 2.20 to current. `handler.unbounded-growth` therefore reports a plain `FileAppender` and stays quiet on a rolling one, as it does for a JDK `FileHandler` (`plain-jvm-jul.md` #5). (b) can follow as its own issue. |
| A10 | A Log4j below 2.20.0 | (a) Do not bind; one `Diagnostics` line and a `doctor` finding. (b) Bind with `drop` blind to fluent-API events and the explicit level read by reflection. | **(a).** (b) gives a user a `drop` rule that quietly misses events, which is the failure mode the floor exists to avoid. The version is read from the `LoggerContext` class's package, with no Log4j code run. The mixed case, `log4j-api` and `log4j-core` of different versions, was not tested (I-F); the adapter reads the core version and says so. |
| A11 | CI cost of the version matrix | (a) The full matrix on every PR. (b) A short set per PR, the full matrix nightly. | **(b).** Per PR: 2.20.0, 2.24.3, 2.25.3, 2.25.5, each synchronous. Nightly: 2.20.0, 2.23.0, 2.24.0 to 2.24.3, 2.25.0 to 2.25.5, latest, each in three modes (synchronous, all async loggers, `<Async>` appender). The nightly picks up a new Log4j release without a code change. The per-PR set includes both ends of the trim exception (2.25.3 and 2.25.4+, A4). |
| A12 | New `doctor` checks | (a) None; log the problems. (b) Three: `log4j2.version` (found below 2.20.0, not bound), `log4j2.trim` (trim disabled on 2.25.0 to 2.25.3), `log4j2.not-bound` (found, bind failed or gave up; from the detector's C8). | **(b),** with the check ids in `reference/doctor-checks.md`, which `GuideReferenceDriftTest` keeps in step with the code. Each says what to change: upgrade Log4j, upgrade past 2.25.3, or the reason the bind did not happen. |

## Scope

1. **Detection and binding.** A Log4j 2 watcher for the detector of `class-loader-detector.md`:
   marker class `org.apache.logging.log4j.core.LoggerContext`, framework packages
   `org.apache.logging.log4j.*`, the adapter entry class in the bridge jar. The adapter binds once
   the context is `STARTED` (I-4; `INITIALIZED` is not enough) and uses no Log4j class, and no JUL
   where `log4j-jul` is the JUL manager, before then. This is the same readiness principle as JUL
   and Quarkus.
2. **Levels** (A2, A8). On `LoggerConfig`, on the held context. `knownLoggerNames` is the union of
   the context's instantiated loggers and its `LoggerConfig`s (they differ, spike probe A1). `configuredLevel`
   is `getExplicitLevel()`; `effectiveLevel` is the nearest ancestor's. Baselines record whether
   the name had its own config and its explicit level.
3. **Re-apply and re-assert** (A1). A listener on the context's `"config"` property change. A real
   swap has `old != new`; every `updateLoggers()` fires one with `old == new`, and the listener must
   not recurse on those (R0). The two kinds run two callbacks. A swap wipes levels, the gate, the
   wrappers and the handler levels, so it runs `onReset`: core's whole re-apply, idempotent. An
   `old == new` event from anyone else's `updateLoggers()` is a native change (Spring's
   `logging.level`, Actuator) and runs `onNativeChange`: core compares each overridden name with its
   override and puts back any that differ (SB7), and leaves the vendor layer alone. Neither assumes the
   swap event comes before the `old == new` one (SB5), and nothing runs once the context is stopping
   (SB10). (The first draft of this item ran the whole re-apply on every event; see "Slice 2".)
4. **Gate** (A3). One context-wide `Filter` added with `config.addFilter`. Its first test is a
   level compare against the logger's level, before any rule lookup, because it runs ahead of the
   level check on every call, including disabled `debug()` in hot loops (G2). It never returns
   `ACCEPT`, which would skip the level check. It computes the full verdict once per call (drop,
   storm, trim hit counting) from the logger name, level, unformatted pattern and params, and
   throwable, and formats the message itself only when a rule matches on message text. It feeds
   storm detection through the same filter (`installStormDetection`).
5. **Trim** (A3, A4). Each appender in each `LoggerConfig` slot is wrapped, keeping its name and
   the `AppenderRef`'s level and filter. For a matching event the wrapper rebuilds it with a
   synthetic `Throwable` and a `ThrowableProxy` subclass that rewrites the first rendered line.
   It recomputes only the trim choice, never counting. Off on 2.25.0 to 2.25.3.
6. **Handler levels** (A5), **diagnostics** (A9), **`backendInfo`** (log4j-core's version).
7. **Lifecycle.** The context's shutdown listener tells the adapter instance to let go: it removes
   its listener and wrappers and ends its thread. Redeploy is the detector's (TC5, WF4).
8. **`logctl attach`.** Works with no extra step: nothing in A3's design needs a service file read
   at Log4j start. The attach case is covered by an IT.
9. **Docs.** §4.3's hook table (Log4j 2's render stage is slot wrapping, not `Layout` or
   `RewriteAppender`, answering §13 open question 1 for Log4j 2); §11 (already updated); §17.1;
   the guide pages in the Functional summary, with output captured from real runs.

## Acceptance

- **A3 prototype, before the gate and trim are built.** A throwaway harness with the gate counting
  and a non-counting trim query in the wrapper, run in the three async modes against a nested
  call (a parameter's `toString()` that logs: H8), a fluent call, a rule change between gate and
  render, and a logger-wide rule. Pass: every trim and every count agrees with the provider
  route's result in the spike's H table, with no double count. The result goes in this spec
  ("As built") and, if it fails, A3 is reopened.
- **Per setup** (plain JVM, Spring Boot 3.5 and 4.1, Tomcat with two Log4j versions, WildFly
  deployment): `set logger X DEBUG for 1m` makes debug lines appear and expire; an appender
  `set handler` hides lines and `reset handlers` restores it; a `drop` rule removes its lines; a
  `trim` rule trims with no synthetic class name in the output; `doctor` runs.
- **Reconfiguration:** `reconfigure()`, a `monitorInterval` reload and a Spring `logging.level`
  change each leave the override in force before the call returns or within one tick, and never
  recurse.
- **Actuator** (Spring Boot): a change to an overridden logger returns `204`, is put back, appears
  in the audit trail and produces the A1 warning; a change to a logger LogAperture does not
  override is left alone and becomes the baseline (A2).
- **Version matrix** (A11): levels, gate, trim rendering and handler levels pass on every version
  in the list; 2.25.0 to 2.25.3 report trim disabled; 2.19.x and below do not bind and `doctor`
  says so.
- **Async:** all three modes in every version of the per-PR set; `MutableLogEvent` reuse produces
  no stale trim.
- **Lifecycle:** the leak tests of `class-loader-detector.md`, and no work done after the context
  begins stopping.
- **Benchmark in `logaperture-bench`:** the gate with no rule, the gate with a non-matching rule,
  and a disabled `debug()`, against bare Log4j 2. The budgets are the ones in
  [`overhead-benchmarks.md`](overhead-benchmarks.md); a gate over budget blocks the release, as for
  the other adapters.
- A Logback or JUL app is unchanged; an app that sets its own configuration and context selector
  in `main()` keeps both.

## Behaviour to document rather than change

- `logctl top` does not count Log4j 2 output (A6).
- Trim is off on Log4j 2.25.0 to 2.25.3 (A4).
- A Spring Boot Actuator or `logging.level` change to a name under an active override is put back
  (A1). It is the same rule for any change to that level from outside LogAperture.
- Between a configuration swap and the re-apply, events can pass unfiltered. The window is the
  listener's own run time, not yet measured.
- `drop` and counted rules cannot see an event logged by a `log4j-api` older than 2.20.0 inside
  a mixed-version app (A10).
- A Spring Boot app's `log4j2-spring.xml`, devtools restarts and structured logging are not
  tested.
- A rule that matches on message text makes the gate format the message itself, so a parameter's
  `toString()` runs one extra time (A3 prototype, "As built"). A `toString()` with side effects,
  such as one that logs, repeats them. Rules that match on the unformatted pattern, the logger or
  the level never format.
- A rule changed between the gate and the render can split one event's decisions: counted at the
  gate, not trimmed at render (A3 prototype, case c11).

## As built

### A3 prototype (2026-11-01): passed, A3(a) stands

A throwaway harness (not committed) with a stand-in rule engine: one context-wide `Filter` that
counts, and a wrapper in each `LoggerConfig` slot that recomputes the trim choice and never counts.
Run on Log4j 2.20.0 and 2.25.5 (both ends of the supported range), each in synchronous,
all-async-loggers and `<Async>`-appender modes: six runs, identical results. Each case logs a
unique token, and the harness reads the output file back to check how often it was written and
whether it was trimmed, and compares the gate's hit count per token.

| Case | Result, all six runs |
|---|---|
| Pattern rule; text rule (gate formats); logger-wide rule | Trimmed, counted once. |
| Unmarked event with a throwable; logger-wide rule with no throwable | Not trimmed, not counted. |
| Level-disabled marked call; `isErrorEnabled()` guards before marked and unmarked calls | Not written or not counted; no stale verdict. |
| Fluent API (`atError().withThrowable(ex).log(…)`), 2.20.0 and 2.25.5 | Trimmed, counted once. |
| Nested: outer marked, inner unmarked; both marked (the `toString()` of a parameter logs the inner event) | Each event trimmed as its own rule says, counted once, written once (the spike's H8). |
| Rule switched off between gate and render | Counted once, not trimmed: a split decision, as expected. |

`filter(LogEvent)` was never called on the gate in any mode, so there is no second counting path.
The wrapper made one query per event with a throwable, none counted.

**One finding, not a failure of A3:** when a rule matches on message text, the gate formats the
message itself with `ParameterizedMessage.format`, and Log4j formats it again when it builds the
event, so a parameter's `toString()` ran twice (and the inner event it logged was written and
counted twice) in all six runs. Log4j gives the gate no formatted message to share. The provider
route (A3(b)) has to compute the same verdict at the gate, so it would format the same way; this
was reasoned, not probed. It is recorded under "Behaviour to document rather than change".

**Second run (2026-11-02), independent harness:** reproduced the result above and added 2.23.1
(2.20.0, 2.23.1 and 2.25.5, three modes each, nine runs, no mismatch between gate and render counts).
One observation for the wrapper: with an `<Async>` appender, the slot holds the `AsyncAppender`, so
the wrapper runs on the caller thread, before the queue. There is no gap between gate and render in
that mode, so the "rule changed between gate and render" split only arises with async loggers.

**The `core` change A3 asks for (built, 2026-11-02):** `RuleGate.trimChoice(RuleCandidateEvent)`
returns the trim the gate would choose, with no hit counted; `RuleService` shares one selection
routine between it and the counting `evaluate`. A default of `null` (no trim) keeps the other
adapters unchanged. `TrimChoiceTest` covers it never counting, the most-restrictive rule, no
throwable, drop-only rules, and a rule reset between gate and render.

Not covered: the real `RuleGate` driven from a Log4j 2 gate (the prototype used a stand-in; the
wrapper's counted-once, recomputed-at-render behaviour is tested only when the adapter lands), 2.24.3 and
2.25.3 (the per-PR matrix will run them), the cost of the gate,
and the `ThrowableProxy` subclass of A4 (the prototype swapped in a plain `Throwable`).

### Slice 1: detection and binding (built, 2026-11-02)

The first PR of the adapter, issue #302. The agent now finds a Log4j 2 that is not on the system
class path, binds it once it has a started context, and offers level control on it. In the order
of "Scope":

- **Module `logaperture-adapter-log4j2`**, compiled against Log4j 2.20.0 (`provided`), with the
  entry `Log4j2Framework`, the levels in `Log4j2LoggingAdapter`, and the context names of A7. Its
  class files are copied into the agent jar under `META-INF/logaperture/log4j2/` only, by path at
  `process-classes`; the agent's shade step does not bundle the module, so nothing of it sits at the
  jar's root (checked on the built jar: no `org/logaperture/adapter/log4j2` outside that folder).
  The system class loader cannot load it by accident (C2).
- **The watch** (`KnownFrameworks.LOG4J2`): marker `LoggerContext`, owner packages
  `org.apache.logging.log4j.`, `log4j-jul`'s manager as a front for it (C1). `WatchMatchesAdapterTest`
  keeps the strings true.
- **Readiness.** `ready()` asks `LogManager.getFactory()` for the contexts the factory already has
  (`getSelector().getLoggerContexts()`) and is true once one is `isStarted()`. It creates none.
  This is called only after the detector has seen `LoggerContext` loaded, which is the I-4 condition;
  `BundledLog4j2EndToEndIT` shows an application's own `log4j2.configurationFile`, set in `main`,
  stays in force with Log4j in the application's own loader.
- **Version (A10).** Read from the package of the loaded context class; below 2.20.0 `ready()`
  throws, and the detector's C8 line says why. The version is believed only when the package's
  manifest title names Log4j: in a shaded or assembled jar the manifest is the application's own, and
  its version (`1.4.0`) must not read as "too old". With no believable version the entry checks that
  `LoggerConfig.getExplicitLevel()` exists (added in 2.19.0) and refuses without it, so an old Log4j
  in a shaded jar is not bound blind. The `log4j2.version` `doctor` check is not in this slice (see below).
- **Levels (A2, A8).** On the context held, never through `Configurator`. `configuredLevel` is
  `getExplicitLevel()`; a name with no `LoggerConfig` of its own reports none. A pre-set creates a
  `LoggerConfig` of its own, and clearing it removes that `LoggerConfig` again, but only the object
  it created: after a reconfiguration the name may be a config the application declared, which is
  left alone. A declared one is given back its inheritance with `setLevel(null)`. `applyLevel` is
  synchronised. The root is `ROOT`, as on Logback and JUL.
- **A limit: `FATAL`.** `FATAL` (and a custom level) reads back as the nearest API level, `ERROR`, so
  a reset that writes the captured baseline back turns a declared `fatal` into `error`. The adapter
  cannot fix it alone: a reset and a deliberate `set logger x error` reach `applyLevel` as the same
  call, and remembering the native level (tried, reverted) turned the deliberate `error` into
  `FATAL`. Exact restoration needs the baseline to carry the native level:
  [#312](https://github.com/ddeuchert/logaperture/issues/312).
- **Contexts.** Taken in order of config location, then name, because the selector keeps them in a
  hash map in no stable order. A web path (`/appA`) is the name; a repeat of one becomes `/appA#2`.
  Only contexts without a web path are numbered (`log4j2`, `log4j2-2`), so a web context more or less
  never renames them. A default context name is a class-loader identity hash, so two contexts in one
  Log4j copy with the same config location and no web path cannot be named stably across runs. `open()` registers the shutdown
  listener before checking that a context is still started, and fails if none is, so the detector
  reports it and no instance is left bound to nothing.
- **Shutdown (TC5).** Log4j's own `addShutdownListener` calls the detector's callback; the listener
  cannot be removed, so it goes inert once the entry has been closed.

**Not in this slice, each its own PR under #302:** re-applying after a reconfiguration and
re-asserting after a native change (A1, A2; built in Slice 2 below); the gate; the slot wrapper and trim; handler levels; the three `doctor` checks (A12) and
`handler.unbounded-growth`; the version matrix, the Spring Boot, Tomcat and WildFly setups; the
benchmark; and the guide pages and changelog entry, which land with the last slice because
the feature is not usable as a whole before it.

**A limit to know about (C4):** the detector gives up three seconds after it finds a framework with
no started context, once, and does not look at that loader again for the life of the JVM. A Log4j
copy whose first context has not started three seconds after its `LoggerContext` class is loaded,
for example because an appender is slow to initialise, is warned about (C8) and then stays
uncontrolled. Nothing in the setups tried comes near it (the spike bound at 0.56 to 0.75 s), so C4
stands; there is no retry and no workaround other than a faster start. Recorded under "Not
established".

**A regression in a corner, accepted for now (C1):** with `-Djava.util.logging.manager` naming
`log4j-jul` the `none` integration does not install the JUL context, whether or not the Log4j bind
succeeds. An application on `log4j-jul` with a Log4j older than 2.20.0 (refused, A10), or one that
gives up (above), therefore has no level control at all, where before this slice it had the JUL
context. Binding JUL as a fallback when the Log4j bind fails is a follow-up
([#311](https://github.com/ddeuchert/logaperture/issues/311)).

### Slice 2: re-apply on reconfiguration (built, 2026-11-02)

The second PR of the adapter under #302. A configuration swap puts every override back, and a level
changed natively against an override is put back at the moment it happens.

- **A swap** (`old != new`: `reconfigure()`, a `monitorInterval` reload, Spring's swap) runs the
  `LoggingAdapter.onReset` listener that every other adapter already has: core's whole re-apply
  (baselines, overrides, and later the gate, wrappers and handler levels), as for Logback. Core wires
  it for every context the detector hands over, so nothing was added there. The adapter forgets the
  `LoggerConfig`s it created (they belonged to the configuration that is gone) before calling.
- **A change from elsewhere** (`old == new`: Spring's `logging.level` after its swap, an Actuator
  `POST`, application code) runs a new, optional SPI method, `LoggingAdapter.onNativeChange`. Core
  wires it to `LevelControlService.reassertAfterNativeChange` (Slice 3 below), which is the override half of the check the 30 s
  verification sweep already runs (`verifyAndReapply` is that half plus the vendor layer): compare each
  active override with the level now in force, put back any that differ, and record each in the audit
  trail as `verification-sweep` (so a suppressed change is never silent, §9.6). The vendor layer is
  left out: a vendor default sits under the application's own configuration, so a native change to a
  vendor-defaulted logger with no override is the application's value and wins (A2); the periodic
  sweep still re-applies a drifted vendor level. **A deviation from the Scope text above:** running the whole re-apply on every `old == new`
  event would re-apply every override, and re-install the rule pipeline, top and storm detection,
  on every `updateLoggers()` anyone makes; only the override check is needed for a level change, and a
  swap is the only event that wipes the rest. The default is a no-op, so Logback, JUL and the others
  are unchanged and keep the sweep alone.
- **No recursion (R0).** The adapter's own `updateLoggers()` (in `applyLevel`) and any listener it is
  running set a thread-local; events on that thread are ignored. Events once the context is not
  `STARTED` are ignored (SB10). A listener that throws a `RuntimeException` is a `Diagnostics` line,
  never an exception into Log4j (an `Error` is not swallowed). A context's adapter lets go of its
  property listener when that context stops, and the entry's `close()` does so for the rest ("Scope" 7);
  `NoneContainer.close()` clears both listeners.
- **The created-config map follows the live configuration.** `LoggerConfig`s LogAperture created
  belong to one configuration. They move to "created on a gone configuration" when the live configuration
  is no longer the one they were created on, not when a swap event is seen: SB5 puts the `old == new`
  event, where a re-assert may create one, before the swap event, and a reset after both must still
  remove it. For a name created on a configuration that is gone, a reset leaves the application's own
  config of that name alone; if an override was written onto it meanwhile, the reset gives back the
  level the application declared there. The marks are dropped, at each configuration change, for names
  the new configuration does not declare.
- **The event before the swap event (SB5).** The first event after a swap is an `old == new` one on the
  new configuration, fired before the swap event. The adapter recognises it (the live configuration is
  not the one it last saw) and does not run the native-change callback for it: the swap event does the
  full re-apply, and running the override check as well would put every override back twice and write
  a misleading `verification-sweep` audit record per override on every reload. Both callbacks are still
  idempotent, so nothing depends on the order.

Verified in a real process (`BundledLog4j2EndToEndIT`): `reconfigure()` and then a native
`Configurator.setLevel(WARN)` against a `DEBUG` override leave `DEBUG` in force, `list loggers` still
shows the override, and `reset` then removes it. The test fails without the `onNativeChange` wiring.

**Not in this slice:** the rest of A1 and A2, built in Slice 3 below.

### Slice 3: the A1 warning and the A2 baseline (built, 2026-11-02)

The rest of A1 and A2, in `core`, for any backend that reports a native change
(`LevelControlService.reassertAfterNativeChange`, called by `NoneContainer` from `onNativeChange`).

- **A2: the baseline follows the native source.** Just before an override is put back, the logger's own
  configured level is read (what Spring's `logging.level` or an Actuator call set, or "inherited") and
  becomes its native baseline (`BaselineRegistry.rebaseNative`). A later `reset` gives that level back,
  not the one from before Spring ran. The value is read before the override overwrites it, so it is
  never one LogAperture wrote. The vendor layer is untouched.
- **A1: the warning.** A `Diagnostics` line, at most once a minute per logger (`warnThrottled`), names
  the logger, what it was changed to, the override that was put back, how it was set (`jmx`) and why, and
  `logctl reset logger <name>`. Not written within 5 s of a reconfiguration (`reapplyActiveOverrides`
  stamps the time, from the same clock the check uses): a change that soon is the application's start-up
  configuration (SB6), put back and audited and rebased, but not worth a warning. Both numbers are
  constants. The warning does not say who set the override: the service knows only the process's own
  principal, which is what the audit trail records.
- **Done after the pass, not inside it.** The rebase and the warning run once every override has been
  checked, so the framework's reconfiguration listener (which runs under the framework's locks) first
  does the least it can. Each is skipped if the override is no longer the one in force (a reset or a new
  `set logger` landed meanwhile), and the rebase is skipped when the level read equals the override's own
  (a value this process wrote, never the native source's) and never creates a baseline nobody captured.
  The first check narrows a race it cannot close without a lock around the control plane.
- **The audit record is unchanged:** `source=verification-sweep`, as for any override a framework undid
  (the `audit-log.md` guide already describes it that way), so no new value appears in the guide.
- **The sweep's own check does neither.** It cannot tell who changed a level, so it neither rebases nor
  warns; WildFly's management CLI is as before (A1 leaves it unchanged).

Verified in a real process (`BundledLog4j2EndToEndIT`): a native `WARN` within 5 s of a reload is put
back with no warning; a native `ERROR` 5.5 s later is put back and the warning names the logger; after
`reset` the logger is at `ERROR`, the last level the native source set. Nine core tests cover the
rules (`NativeChangeReassertionTest`).

Guide: the warning goes on the Log4j 2 pages with the last slice, with output captured from a real run.

## Out of scope

`top` on Log4j 2 (A6); Log4j 1 and reload4j; the warning for names no backend can reach (#303);
Spring Boot with Logback and a Spring-specific integration (1.2.0); WildFly domain mode and Jakarta
EE 10 servers (27+) as tested targets; Log4j 2 configured only by `log4j-to-slf4j` (events go to
Logback, which is controlled there); a Log4j 2 shared by several webapps from Tomcat's `lib/`.

## Not established

From the spike, carried here because they bound what is promised:

- The gate's real cost with the level fast path (needs the benchmark above).
- How long the swap-to-re-apply window lasts.
- An app whose `log4j-api` and `log4j-core` versions differ.
- JDK 25; Spring Boot's `log4j2-spring.xml`, devtools restart and structured logging; Tomcat 10.1
  and a webapp without `log4j-jakarta-web` (the context name is then a hash, A7); WildFly 27+;
  more than one context in one Log4j copy.
- Several webapps sharing one Log4j copy (Tomcat's `lib/`, out of scope): the entry has one shutdown
  callback, so stopping any one context unbinds every context it bound, and the bridge loader is
  pinned by the listener Log4j cannot remove for as long as another context lives. Both need a
  per-context callback in `BridgedFramework`; neither arises with one context per Log4j copy.
- A `LoggerContext` stopped and then started again: a shutdown is the detector's "let go" (TC5, WF4), the
  bridge is closed and that loader is not looked at again, so a restart of the same context is not
  re-bound. A redeploy brings a new copy, which is.
- The cost of `verifyOverrides` on every `updateLoggers()` anyone makes: one pass over the active
  overrides with a level lookup each, normally fewer than ten, run on the calling thread. Spring Boot's
  startup applies a handful of `logging.level` entries. No debounce until someone measures a need.
- A second context in the same Log4j copy created after the first was bound (the entry binds the contexts that are started when it opens), and a first context created more than 3 s after the class is loaded (C4).
- The one extra `ContextDataProvider` call on the async-logger thread on 2.17.2 (moot under A3(a)).

## Estimate and slot

About 2 to 3 weeks after the detector (spike estimate): levels, baselines, re-apply and re-assert,
about 300 lines, 3 days; gate, about 250 lines, plus the `core` change for A3, 3 days; slot wrapper
and the proxy subclass, about 300 lines, 3 days; handler levels and diagnostics, about 200 lines,
2 days; the version matrix, the async tests and the setups' ITs, 4 to 5 days; guide and spec, 1
day. The A3 prototype comes first and is a day. Release `1.1.0`; `1.1.0-alpha.1` needs it merged
by mid December.
