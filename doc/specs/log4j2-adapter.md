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
| A11 | CI cost of the version matrix | (a) The full matrix on every PR. (b) A short set per PR, the full matrix nightly. | **(b).** Per PR: 2.20.0, 2.24.3, 2.25.3, 2.25.5, each synchronous, and 2.25.5 also in the two asynchronous modes (Slice 8, M8-3). Nightly: 2.20.0, 2.23.0, 2.24.0 to 2.24.3, 2.25.0 to 2.25.5, latest, each in three modes (synchronous, all async loggers, `<Async>` appender). The nightly picks up a new Log4j release without a code change. The per-PR set includes both ends of the trim exception (2.25.3 and 2.25.4+, A4). |
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
  says so (at process level, #322: [`surface-on-bind-failure.md`](surface-on-bind-failure.md)).
- **Async:** the three modes on every version nightly, and on 2.25.5 per PR (A11, M8-3); `MutableLogEvent`
  reuse produces no stale trim (Slice 8's burst test).
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
- **`list loggers` shows a Log4j 2 logger only while the application holds it** (from 2.24.3: 2.24.0 and 2.23.1 keep
  them, 2.24.3, 2.25.2 and 2.25.5 do not; checked by forcing a GC on a logger nothing references). The registry holds
  loggers weakly, so one that was instantiated and dropped is gone from the context's logger list after a collection;
  a logger a class keeps in a `static final` field stays. `set logger` works for any name regardless (A8), and a
  `LoggerConfig` from the configuration file is always listed. Found when the version matrix failed in CI (Slice 8).
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
  the logger, what it was changed to, the override that was put back (its level, tier, how long ago and how it was set, and why), and
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
- **Vendor-named loggers.** For a logger the vendor defaults file names, `reset` gives back the vendor
  level (the vendor layer is that logger's baseline, as everywhere), so the rebase has no visible effect
  there; `reset --to-native` gives back the level the native source set.
- **A logger the adapter cannot read** (its `configuredLevel` throws) is still put back and audited and
  does not stop the rest of the pass; no baseline is written for it from a value nobody read.
- **One pass at a time.** The 30 s sweep and the native-change callback share one pass, serialized by a
  lock, so two threads that both see a drift do not both apply it and both write the audit record. A
  limit this does not close: a sweep that reaches a change in the microseconds between someone's
  `setLevel` and their `updateLoggers()` puts the override back without the rebase and the warning,
  which belong to the native-change callback.
- **The command in the warning is one that works:** `--include-sticky` is added for a `STICKY` override.
- **The audit record is unchanged:** `source=verification-sweep`, as for any override a framework undid
  (the `audit-log.md` guide already describes it that way), so no new value appears in the guide.
- **The sweep's own check does neither.** It cannot tell who changed a level, so it neither rebases nor
  warns; WildFly's management CLI is as before (A1 leaves it unchanged).

Verified in a real process (`BundledLog4j2EndToEndIT`): a native `WARN` within 5 s of a reload is put
back with no warning; a native `ERROR` 5.5 s later is put back and the warning names the logger; after
`reset` the logger is at `ERROR`, the last level the native source set. Nine core tests cover the
rules (`NativeChangeReassertionTest`).

Guide: the warning goes on the Log4j 2 pages with the last slice, with output captured from a real run.

### Slice 4: the gate (built, 2026-11-02)

The fourth PR of the adapter under #302: drop rules and storm detection on Log4j 2.

- **`GateFilter`**, one context-wide `Filter` added with `config.addFilter` by the adapter's
  `installRulePipeline` and `installStormDetection` (both attach the same filter; installing again is
  idempotent). It never returns `ACCEPT` (G2): a drop is `DENY`, everything else `NEUTRAL`.
- **Order (drop-rule.md decision #3):** the rule pipeline first, then storm detection, so a denied call
  never reaches the storm counters. Either seam alone works.
- **The level fast path (G2).** Before anything else it compares the call's level with the logger's
  and leaves if the call would not be written; an `isXxxEnabled()` guard (no message) leaves too, and
  so does a logger no rule can touch while storm detection is off (R3). The fixed-arity overloads
  (`p0` to `p9`, which `AbstractFilter` would route through a freshly allocated `Object[]`) do this
  *before* allocating it. All fifteen `Filter` entry points are covered, the fluent API included.
- **What a rule sees:** the logger name, the level, the throwable (an explicit one, the message's, or a
  trailing one no placeholder consumed), and the message with its parameters filled in, formatted only if
  a rule reads it, so a parameter's `toString()` runs one extra time then (A3). Storm detection gets the
  *unformatted pattern*, not the line, as it does on JUL.
- **The trailing throwable is Log4j's own decision.** It is read from a `ParameterizedMessage` built for
  the call, not counted by the gate, because Log4j's answer differs by version for an escaped
  placeholder (`"escaped \{} only"` with one `Throwable` argument: no throwable on 2.20.0, the argument
  on 2.25.5), and the gate has to agree with the event Log4j then builds, whichever version runs. A test
  compares the two.
- **Suppliers and arbitrary objects.** `logger.info(() -> ...)` and a `MessageSupplier` reach the gate as
  an `Object`. They are evaluated at the gate only when a rule reads the text or storm detection is on
  (and once), and Log4j evaluates them again when it writes the call, so a supplier with side effects
  repeats them. The throwable inside a supplied `Message` is not looked for: that would evaluate the
  supplier for every gated call.
- **`RuleGate.evaluateOnce`** (core, new): the same verdict and the same hit counting as `evaluate`,
  without the per-record decision cache. That cache exists so sibling handler filters share one verdict
  for one record; a context-wide filter runs once per call, before any record, and would only fill the
  cache with garbage. `RuleService` overrides it; the default evaluates against a fresh identity.
- **A null message.** With a throwable (`logger.error(null, ex)`) it is a real call and is gated; with no
  throwable it cannot be told from an `isXxxEnabled()` guard (same signature) and is treated as one, so
  `logger.info((String) null)` is not gated. A message with no pattern (`getFormat()` null) is fingerprinted
  by its text.
- **One clock read per call**, shared by both seams and the storm's sample. The adapter holds the configuration it attached
  the filter to weakly, so a replaced configuration is not pinned, and `detach()` removes the filter from
  that one and from the live one.
- **Reconfiguration.** A swap drops the filter with the old configuration; core's reset hook already
  calls `installRulePipeline` and `installStormDetection` again, which attach it to the new one. Between
  the swap and that call events pass unfiltered (accepted, "Scope" 3). `detach()` removes the filter and
  lets go of both seams.
- **Fails open (§9):** a rule or detector that throws is a throttled `Diagnostics` line and the event
  passes.

Verified: unit tests on real Log4j (every overload, the level fast path, a guard, deny, storm order and
the raw pattern, idempotence, a swap, detach, fail-open), and `BundledLog4j2EndToEndIT` in a real
process: a drop rule removes its lines and keeps removing them after a reload.

**Not in this slice:** the slot wrapper and trim; until it lands, a trim rule on a Log4j context is
*counted* at the gate (`trimChoice` is what the wrapper will use, without counting) but not applied. The
benchmark of the gate's cost against the budgets in `overhead-benchmarks.md` is not done either: the
design allocates nothing on the fast path, but nothing has been measured, and "Acceptance" makes a gate
over budget a release blocker.

### Slice 5: the slot wrapper and trim (built, 2026-11-02)

The fifth PR of the adapter under #302: a trim rule cuts the stack trace of a Log4j 2 event.

- **`TrimAppender`**: an appender in a `LoggerConfig` slot wearing the original's name, layout and error
  policy. For an event with a throwable on a logger a rule applies to, it asks `RuleGate.trimChoice`
  (never counts: the gate counted the hit once, before the event existed, A3), and if a trim applies
  writes a private rebuilt copy of the event with the trimmed throwable. The original event, which may be
  a reused `MutableLogEvent` or a ring-buffer slot, is never touched. It fails open (a throwing gate is a
  throttled `Diagnostics` line and the event is written as it was). Stopping it does not stop the
  appender behind it: the configuration owns that.
- **`TrimSlots`** puts one in every slot of the live configuration, idempotently, and again after a swap
  (core's reset hook calls `installTrimRendering` first). A slot is read from its `AppenderRef`, the only
  place its level and filter can be found, and both are carried over, so an appender added to a
  `LoggerConfig` in code with no reference is left alone. `config.getAppenders()` still lists the
  originals. Swapping takes a `remove` and an `add` (the set is keyed by name), so a call on that logger
  can miss that appender for the microseconds between them.
- **Text only (trim-rule.md "Text formatters only", #83).** An appender is wrapped only if its layout is a
  `PatternLayout`; an `<Async>` appender (no layout of its own) only if everything behind it is. A JSON,
  HTML, CSV or custom layout, and any appender with no layout (`Routing`, `Failover`), is left untouched.
  Behind an `<Async>` appender the wrapper is in front of the queue, so the cut happens on the calling
  thread, before the event is queued.
- **`TrimmedProxy`** (A4): from 2.20.0 to 2.24.x `%xEx` and Spring Boot's `%xwEx` render from the event's
  `ThrowableProxy`, and one built from the synthetic throwable names the synthetic class. This subclass
  answers with the synthetic throwable's own `toString()` (the original's, plus the marker) as its name,
  at every level of the cause chain, which is what `%ex` prints from the throwable itself, and trims the
  `: null` those versions append to the top line. From 2.25.4 the converters ignore the proxy and print
  correctly anyway.
- **A deviation from A4's wording, with the same outcome: a probe, not a version table.** A4 says trim is
  off on 2.25.0 to 2.25.3. `TrimProbe` asks the running Log4j instead: it renders a trimmed event through
  `%ex` and `%xEx` and checks the first line is the throwable's text and not a synthetic class name. It
  fails on exactly those four versions (checked on 2.25.2), and it also covers a version this was never
  tried on. When it fails, nothing is wrapped, one `Diagnostics` line says why, and the reason is kept
  for `doctor` (`log4j2.trim`, A12, a later slice).
- **`TrimmedThrowables`** (core, moved from the JUL adapter): the builder of the synthetic chain, now
  shared. JUL keeps its names and its tests.
- **The appender's own filter is carried too.** Log4j checks an appender's own filter on the object in the
  slot, and `delegate.append` never does, so the wrapper takes the delegate's filter (a `ThresholdFilter`
  inside a `<Console>`, a `BurstFilter` on a file appender), not only the reference's. Without it every
  event such a filter denies would be written, in any configuration with a filtered text appender, with or
  without a trim rule. The delegate keeps ownership: stopping the wrapper stops neither it nor the filter.
- **A failed swap puts the appender back.** If adding the wrapper throws after the original was removed,
  the original is added again and wrapping goes on with the other slots; a logger that has silently lost
  an appender is worse than one that is not trimmed.
- **Detach takes the wrappers out**: each slot gets its original appender back, with the reference's level
  and filter, while the context lives. A context that is stopping takes its wrappers with it. A wrapper still in
  flight passes its event straight through.
- **The probe runs once per adapter**, not on every reset-hook call. `TrimmedProxy` strips the `: null` tail
  right after its known name, so a multi-line exception message renders as `%ex` does. A styled
  `TextRenderer` (ANSI colour around the name) defeats that match on 2.20 to 2.24 and leaves the `: null`
  tail: cosmetic, not fixed.
- **The proxy is built only where a layout can read it.** `ThrowableProxy`'s constructor is eager (a stack
  walk and the whole cause chain), so building one per trimmed event for every appender would be the cost a
  storm is worst placed to pay. An appender whose pattern has an explicit plain converter (`%ex`) and no
  extended one (`%xEx`, `%rEx`, `%xThrowable`, `%rootException` and their kin, Spring Boot's `%xwEx` among
  them) reads the throwable itself and gets none. A pattern with *no* throwable converter at all gets one:
  `PatternLayout` adds its own (`alwaysWriteExceptions` defaults to true) and that one reads the proxy, which
  the end-to-end test's own pattern (`%level %logger %msg%n`) showed; an `<Async>` appender gets one if anything behind it might read it; when in doubt,
  yes. The cause proxy inside it is built when a converter first walks to it.
- **The wrapper's reach ends at the standard converters.** `rendersFromProxy` reads the pattern with a
  regular expression for Log4j's own throwable converters. A plugin converter that reads the proxy, or a
  composite such as `%replace{%xEx}{...}` next to a plain `%ex`, can pass as "no proxy needed"; the proxy is
  then built by Log4j from the synthetic throwable and the output names the synthetic class. Decided once,
  at wrap time (an `<Async>` appender's inner appenders added to the configuration later are not seen).
  When in doubt it builds the proxy; this is the case it cannot be in doubt about.
- **The wrapper answers as the appender it stands for.** Log4j asks the slot object, not the appender, for
  the error handler, `requiresLocation()`, whether it is started and its filter, so the wrapper forwards
  all four; a wrapper in front of an appender the configuration stopped does not claim to be started, and a
  filter added to or removed from the appender later is followed (it is asked of the appender each time, not
  copied). A wrapper left in a slot by an adapter that has gone is taken over by the next one, not skipped.
- **The probe covers `%ex`, `%xEx`, `%rEx` and `%ex{short}`**, which render correctly on every version
  where the first two do (checked on 2.20.0, 2.23.1 and 2.25.5). Spring Boot's `%xwEx` is not in Log4j and
  is not probed; it was checked on real Spring Boot 3.5 and 4.1 applications (spike SB9) and renders as `%xEx`.
- **What a swap costs (real, and not avoidable with `LoggerConfig`).** The set is keyed by name, so wrapping,
  and unwrapping on detach, is a remove and an add. A call on that logger landing between the two is not
  written to that appender, and nothing counts it; it happens at install, after each reconfiguration
  (when core re-installs) and on detach. The add also puts the wrapper after the logger's other appenders,
  so the order in which they write one event can change.
- **Per exception, per wrapped appender.** The trim choice, the synthetic chain and the proxy are built
  once for each wrapped text appender, not once per event: caching by event identity is unsafe because
  events are reused. It scales with the number of text appenders, not with the number of rules; a trim
  pre-check on `RuleGate` is for the gate benchmark to ask for. Wrapping starts at install, not at the
  first trim rule, as every other always-on seam does (JUL, top, storm detection, the rule pipeline), so a
  rule added later needs no swap.

Verified on 2.20.0, 2.23.1, 2.25.2 and 2.25.5 (`-Dlog4j2.version`, the same tests on each): the trim tests pass on
2.20.0, 2.23.1 and 2.25.5, on 2.25.2 they are skipped and a test asserts nothing is wrapped and the
reason is kept. `BundledLog4j2EndToEndIT`, in a real process: a trim rule cuts the trace and its cause,
no synthetic class in the output, hits counted once per trimmed event, and the same after a reload.

**Not in this slice:** `top` on a trimmed Log4j event (A6, #306); handler levels (A5, Slice 6); the `doctor` check;
the full version matrix in CI (A11).

### Slice 6: handler levels (built, 2026-11-03)

The sixth PR under #302: `logctl list handlers`, `set handler`, `reset handlers` and the handler-floor
warning of `set logger` work on Log4j appenders. A5 decided *what* a handler level is; this section
settles how it is built and the questions A5 left open. Diagnostics (A9, A12) are the next slice, not
this one.

**What the adapter answers** (the SPI of [`handler-floor-control.md`](handler-floor-control.md)):

- `hasHandlerLevels()` is `true`.
- **A handler is an appender that at least one `LoggerConfig` references**, named by its `log4j2.xml`
  name (`HandlerRef(name)`, no resolver, no identity tokens). `realHandlers()` lists them in
  configuration order; `knownHandlers()` is the same plus `ALL_HANDLERS`, as for plain JUL.
  `config.getAppenders()` still lists the originals, so a trim wrapper (Slice 5) does not change a name.
- **`handlerLevel(ref)`** is the most restrictive level among the appender's references (A5), empty when
  none has a level (**`ALL`, not empty, when none has: see "As built" below**). **`setHandlerLevel(ref, level)`** sets it on every reference in every `LoggerConfig`
  and returns the previous one; `null` takes the level off (what `reset` and "back to inherited" mean).
  An unknown name, or one no longer in the live configuration, throws `UnknownHandlerException`.
- **`handlersOnRoot()`, `handlersOnPath(logger)` and `handlerFloorsBelow(logger, target)`** walk from the
  logger's own `LoggerConfig` up through its parents, stopping after a `LoggerConfig` with
  `additivity="false"`, which is Log4j's own rule for where events stop, like JUL's `useParentHandlers`.
  A floor is reported for each reference on the walk whose level is stricter than `target`. The walk
  starts at the `LoggerConfig` the logger resolves to (`config.getLoggerConfig(name)`), which is the
  nearest one that exists, not necessarily one of its own.

**Changing a reference level** is the swap of A5 and Slice 5: `removeAppender`, then
`addAppender(appender, level, filter)` on that `LoggerConfig`, carrying the reference's filter and the
appender's own. Everything Slice 5 says about the swap applies: a call in the window is not written to
that appender, and the appender goes to the end of the logger's list.

**The adapter keeps the levels itself (H1), and Slice 5 must read them from there.** `getAppenderRefs()`
goes on reporting the old level after a swap. `TrimSlots` reads `reference.getLevel()` when it wraps or
unwraps a slot, so left as it is, a detach or a re-wrap after `set handler` would put the stale level
back. One small table, keyed by (`LoggerConfig`, appender name; `LoggerConfig` has no `equals`, so identity), is the single source: written
by `setHandlerLevel`, read by `handlerLevel`, the floor walks and `TrimSlots`, and dropped with the
configuration on a swap. A slot with no entry falls back to the reference's own level. The slot holds
whatever is there now, wrapper or original: `setHandlerLevel` swaps *that* object, never the original
behind it, so the two mechanisms do not undo each other.

**Re-apply.** A reconfiguration builds a new `Configuration`, so the table starts empty and every
reference has the file's levels. Core's `onReset` hook already re-applies handler overrides and
re-captures handler baselines (as on JUL); this slice adds no new hook, and an IT checks a handler
override survives `reconfigure()` (Acceptance, "Reconfiguration"). A handler level set in
`log4j2.xml` is the baseline `reset` returns to.

**Not handlers, deliberately:**

- An appender no `LoggerConfig` references (programmatic slots with no `AppenderRef`, as in Slice 5) is
  not listed: there is no reference to carry a level.
- An appender referenced only from an `<Async>` appender (`<AppenderRef>` inside it) has no reference in
  any `LoggerConfig`. The `<Async>` appender is the handler; the ones behind it are not listed.
- The `Routing`, `Failover` and `Rewrite` appenders are handlers like any other, by their own name;
  what they hold inside is not.

**Decisions (agreed 2026-11-03):**

| Id | Question | Options | Recommendation |
|---|---|---|---|
| S6-1 | An appender behind `<Async>` | (a) Not listed; the `<Async>` is the handler (above). (b) List it and apply its level to the `<Async>` reference. (c) List it, read-only. | **(a).** (b) shows two names that control one thing; (c) invites `set handler` to fail on something that is listed. A user who wants the file appender quiet raises the `<Async>`. Say so in the guide. |
| S6-2 | `ALL_HANDLERS` fans out over every referenced appender | (a) As JUL. (b) Exclude `<Async>`-wrapped ones. | **(a);** nothing is wrapped that way here (S6-1). |
| S6-3 | The level table shared with `TrimSlots` | (a) The table above (one source). (b) Read the live `AppenderControl` level by reflection. (c) Re-read `getAppenderRefs()` and accept the stale value. | **(a).** (b) breaks across 2.20 to 2.25; (c) is the bug this section exists to prevent. |
| S6-4 | An appender referenced from several `LoggerConfig`s at different levels | (a) Report the most restrictive (A5), set all to the one value. (b) Per-reference control. | **(a), with a limit found in the build.** The SPI has one level per handler. `reset` writes the baseline (the most restrictive level) to every reference, so an appender at different levels in the file is flattened by a reset: see "As built". |

**Tests.** Unit tests on each version in the usual set (`-Dlog4j2.version`: 2.20.0, 2.23.1, 2.25.2,
2.25.5): list; set and reset on one reference and on several; floors on a path with and without
`additivity="false"`; `ALL_HANDLERS`; an unknown name; set then trim detach and re-wrap keep the level
(the interaction above); a swap failure puts the appender back and rolls the other references back (all or nothing); a
reference already at the level is not swapped; reset flattens an appender at two levels (the limit
below, pinned by a test); a reconfiguration drops the table and core re-applies. `BundledLog4j2EndToEndIT` in a real process: `set handler Out ERROR` hides the
`INFO` lines, `reset handler` brings them back, and the same after a reload.

**Docs.** The Functional summary already names the behaviour. Guide pages: `how-to/find-disk-usage.md`
and `concepts/rules.md` (what `set handler` means on Log4j: the `AppenderRef` level, the `<Async>`
rule) when the guide slice lands.

**As built (deviations from the section above):**

- **An appender whose references have no level reports `ALL`, not "no level".** Core captures a handler's
  baseline only from a level that is *present* (`HandlerBaselineRegistry.captureIfAbsent`, so a handler
  that does not resolve yet is retried); with an empty answer the baseline of every unset appender was
  never captured and `reset handler` had nothing to go back to (found by `BundledLog4j2EndToEndIT`). `ALL`
  is true (nothing is filtered) and is what a JDK handler reports by default. A reset then writes `ALL`
  to each reference, which filters nothing, the same as no level.
- **So the S6-4 promise is cut to what the SPI can carry.** `reset` writes the *baseline* level, one value, to
  every reference. For an appender whose references have different levels in the file (`WARN` under one
  logger, none under the root), the baseline is the most restrictive (`WARN`), and a reset leaves it at
  `WARN` on both: the reference that had no level now has one. `setHandlerLevel(ref, null)` still gives each
  reference its file level, and is tested, but core does not call it for a reset. Rare (one appender at two
  levels in one file); a documented limit, pinned by a test, tracked as [#319](https://github.com/ddeuchert/logaperture/issues/319) (core calling
  `setHandlerLevel(ref, null)` on a reset, or the baseline carrying per-`LoggerConfig` levels).

- **A swap that fails is not reported as success, and the call is all or nothing.** The references already
  changed go back (also when something other than a failed swap throws), the table records nothing until the
  whole call succeeded, and `setHandlerLevel` throws. A reference already at the requested level is not swapped at all (no lost call,
  no reordering): core re-applies overrides after every reload and `reset` fans out over every appender, so
  most calls are no-ops. The handler reads are synchronized with the swaps, so a floor walk never sees an
  appender in the remove-and-add window.
- **A handler declared at `FATAL` reads as `ERROR`** (`LevelMapper`), so it under-reports its floor and a
  reset writes `ERROR` back. Same limit as #312; not fixed here.
- **Guide pages are not in this PR**, by the earlier decision to write them in the last slice.

**Verified** on 2.20.0, 2.23.1, 2.25.2 and 2.25.5 (`-Dlog4j2.version`); `BundledLog4j2EndToEndIT` in a real
process: `set handler Out ERROR` hides INFO, the same after a reload, `reset handler` brings it back.

**Not in this slice:** `handlerDiagnostics` and `handler.unbounded-growth` (A9); the three `doctor`
checks (A12); `installByteCounting` (A6); the version matrix in CI (A11).

### Slice 7: diagnostics (built, 2026-11-03)

The seventh PR under #302: `logctl doctor` knows Log4j 2. A9 and A12 decided *what* it says; this section
settles where each fact comes from, which A9 and A12 left open because the three A12 checks are not
alike. Two need no bound adapter at all (Log4j was found and could not be bound), one is per context,
and the file checks are per handler.

**1. The file checks on a Log4j appender (A9): `handlerDiagnostics`.** Core's checks already exist
(`handler.unbounded-growth`, `disk.headroom`, `handler.duplicate-output`, `handler.autoflush`); the adapter
only has to answer for each handler (the original appender in `config.getAppenders()`, never the trim
wrapper in the slot):

| Appender | `targetPath` | other facts | What the checks do |
|---|---|---|---|
| `Console` | none | `isConsole` | nothing to flag |
| `File`, `RandomAccessFile`, `MemoryMappedFile` | `getFileName()` | no size cap, no backups | `handler.unbounded-growth` fires, `disk.headroom` runs |
| `RollingFile`, `RollingRandomAccessFile` | none | persistent, file unknown (`fileWithoutPath`) | `top` counts it; the file checks leave it alone, as for the JDK `FileHandler` (A9) |
| `Async` | what its one appender behind it answers | | an `<Async>` over exactly one appender answers with that appender's facts, so a plain file behind it is still found; none or several behind it: no facts |
| anything else (`Routing`, `Failover`, `Rewrite`, custom) | none | no facts | not inspected |

**2. `handler.autoflush`.** Log4j's `immediateFlush` defaults to `true`, so reporting it for every appender
would put a `WARNING` on nearly every Log4j application. A plain file appender reports its `immediateFlush`; a
rolling appender, a console appender and an `<Async>` appender report nothing (a console has no file to flush, and an `<Async>`
appender flushes per batch). So the finding appears for a file appender someone left at the default, which is
the case the check exists for.

**3. Found, not bound (A12 `log4j2.version`, `log4j2.not-bound`): a process-wide record in the detector.** These
two have no context, so no `DoctorService` of a context can say them. `BoundInstance.fail` already knows the
framework, the owning loader and the reason (C8). It now also hands that to a small process-wide list that
`AggregateLevelControl.diagnose()` reads, next to the vendor-defaults and `-javaagent` ordering findings,
which are process-wide in the same way, with no context on the row.

- The check id is the framework id plus a suffix: `log4j2.version` when the framework said it is too old (a
  typed reason, not a text match), otherwise `log4j2.not-bound` (opened and threw, gave up after 3 s, no
  started context). The ids are constants in core, so `GuideReferenceDriftTest` sees them.
- **What `doctor` prints (as built; the spec's first wording was tighter).** The summary carries the framework's
  own reason ("older than 2.20.0", "binding it threw ..."), the detail is generic ("see the line the application
  printed", "usually a log4j2 the application never used"), and the `log4j2.version` fix says to upgrade to a
  supported version. The subject is the loader's class and name plus its identity hash, so two webapp loaders of
  the same class are two rows.
- `WARNING`, except `INFO` when the detector gave up waiting for a started context (often a Log4j the
  application never used, found in review); subject the owning loader's class and name, detail the reason as `Diagnostics` printed it.
  `log4j2.version` suggests upgrading to 2.20.0 or later (A10); `log4j2.not-bound` says what happened and
  suggests nothing, because the reason is what a user needs.
- One row per (framework, loader), kept while that loader is alive (weakly: a redeployed webapp's old loader
  drops its row when it is collected), at most 16. A later successful bind from another loader does not
  remove an old row: each names its own loader.

**4. `log4j2.trim`: a new optional SPI method.** Trim is off on 2.25.0 to 2.25.3 and the adapter keeps the
reason (`trimDisabledReason()`, Slice 5). `LoggingAdapter` gets `default List<DoctorFinding> doctorFindings()`
(empty), which `DoctorService.diagnose()` appends to its own, so every row is stamped with its context like
the others. The Log4j adapter answers one `INFO` row when trim is off: *trim rules are counted but not
applied on Log4j 2.25.2; upgrade to 2.25.4 or later*. `INFO` and not `WARNING`: the adapter cannot tell
whether anyone has a trim rule, and for an application with none it is only worth knowing. Nothing before
the probe has run. The SPI is internal (the MXBean and `--json` surface do not change; the new rows use the
existing finding shape).

**Decisions (agreed 2026-11-03):**

| Id | Question | Options | Recommendation |
|---|---|---|---|
| D7-1 | A rolling appender | (a) Persistent, file unknown (quiet on the file checks, as A9 says). (b) Report its path with made-up limits. (c) Read the policy and strategy by reflection. | **(a).** It costs `disk.headroom` for rolling files, which is the check most worth having on one; core's `HandlerDiagnostics` has no "path known, caps unknown" state. File that as a follow-up rather than invent caps (b) or read unstable classes (c). |
| D7-2 | An `<Async>` appender | (a) Answer with the one appender behind it; none or several: no facts. (b) No facts. | **(a).** `<Async>` in front of a `File` is the common production setup; (b) would make it invisible to `handler.unbounded-growth`. |
| D7-3 | `handler.autoflush` | (a) File appenders only, from `immediateFlush`. (b) Every appender. (c) None. | **(a).** (b) warns on every default Log4j application; (c) drops a real check. |
| D7-4 | Where the not-bound rows come from | (a) A process-wide list read by `AggregateLevelControl.diagnose()`. (b) A new service per failed bind. | **(a).** There is no context to hang a service on. Generic, so the next framework adapter gets its two checks for free. |
| D7-5 | `log4j2.trim` severity | (a) `INFO`. (b) `WARNING`. | **(a)**, since the adapter does not know whether a trim rule exists. |
| D7-6 | How long a not-bound row lives | (a) While its loader lives (weak), at most 16. (b) For the process's life. (c) Cleared when the framework binds elsewhere. | **(a).** (b) keeps a row for a webapp that has since gone; (c) hides a failure that is still true for its own loader. |

**Tests.** Unit: each appender type in the table through `handlerDiagnostics` on 2.20.0, 2.23.1, 2.25.2 and
2.25.5 (a real file in a temp directory; the trim wrapper installed in the slot makes no difference);
`doctorFindings` on 2.25.2 (trim off) and 2.25.5 (nothing); `BoundInstance` failing for a too-old
framework and for a throwing one, and what the list answers (weak drop, the cap, one row per loader);
`AggregateLevelControl.diagnose` with and without rows, and a context-less row; `DoctorService` appending an
adapter's findings with the context stamped. Real process (`BundledLog4j2EndToEndIT`): a plain `File` appender
makes `doctor` print `handler.unbounded-growth` for it by its `log4j2.xml` name. The too-old and gave-up
paths need a Log4j older than 2.20.0, or a context that never starts, as a fixture: those wait for the
version-matrix slice (A11) and are covered at unit level here.

**Docs.** `guide/reference/doctor-checks.md` gets a section for each new id (`log4j2.version`,
`log4j2.not-bound`, `log4j2.trim`) **in this PR**: `GuideReferenceDriftTest` fails the build otherwise, so
this is the one guide page the deferral to the last slice cannot cover. `handler.unbounded-growth` and
`handler.autoflush` get a line each on what they see on Log4j. The other guide pages wait, as decided.

**As built (small differences from the section above):**

- **A rolling appender reports no flush** (decided in review): like `handler.unbounded-growth`, it stays quiet, so
  `handler.autoflush` appears only for a plain file appender left at Log4j's default.
- The typed reason is `UnsupportedFrameworkVersionException` in core's `loader` package; `Log4j2Framework` throws
  it for a version below 2.20.0 and for a Log4j with no `getExplicitLevel()`. `BoundInstance.fail` finds it in
  the cause chain, so the `log4j2.version` row carries the framework's own sentence.
- `BindFailures` keeps the rows; `BoundInstance` records the bind failure after its `Diagnostics` line, and
  holds its owner weakly, so a row never keeps a loader alive. The two check ids are constants
  (`FRAMEWORK_VERSION_CHECK`, `FRAMEWORK_NOT_BOUND_CHECK`) so `GuideReferenceDriftTest` sees them, built as
  framework id plus suffix for any other framework.
- The new SPI method is `LoggingAdapter.doctorFindings()`. `log4j2.trim` is `INFO` with the probe's reason as
  detail.
- **From review:** a scanner-level failure (the listener threw before an instance existed) is recorded too; a
  later successful bind of the same loader forgets its row; a file name the platform rejects gives no facts
  (`pathOrNull`) instead of failing `doctor`; the `log4j2` ids come from the constants; a memory-mapped appender
  reports its own flush (always false).
- `BoundInstance` marks itself closed before it prints its line and records its row, so a test that waits for
  `isClosed` can read too early; the new and one older test wait for the line or the row instead.

**Verified** on 2.20.0, 2.23.1, 2.25.2 and 2.25.5 (`-Dlog4j2.version`); `BundledLog4j2EndToEndIT` in a real
process: `doctor` reports a plain `File` appender (`Disk`) as `handler.unbounded-growth` and not the console.

**Not in this slice:** `disk.headroom` for rolling appenders (D7-1 follow-up); `top` (A6); the version matrix
(A11) and the setup ITs; the gate benchmark; the guide pages other than `doctor-checks.md`.

### Slice 8: version matrix and async modes (built, 2026-11-03)

The eighth PR under #302: the adapter is tested on the Log4j versions A11 names, in the three async modes, in CI,
and the two "found but not controlled" paths (A10, A12) get a real process. A11 decided the *lists*; this section
settles how they are run, what each cell asserts, and four things A11 left open. The setup ITs (Spring Boot,
Tomcat, WildFly) are **not** here: each needs a sample application and a harness of its own, so they are Slice 9,
specified when this one has merged (M8-8).

**What exists today.** The Log4j version is one Maven property, `log4j2.version` (root `pom.xml`, **2.20.0**, the
floor), read by the adapter module's tests and by the agent module's `BundledLog4j2EndToEndIT`. The `build` job
therefore already runs everything on 2.20.0, by accident of the default. No async mode is tested: the unit tests and
the IT use a synchronous configuration. Central has 2.20.0, 2.23.0, 2.23.1, 2.24.0 to 2.24.3, 2.25.0 to 2.25.5,
2.26.0 and 2.26.1, and `3.0.0-beta3` (checked 2026-11-03).

**What a cell is.** One Log4j version in one mode runs the adapter module's unit tests and `BundledLog4j2*IT` in a
real process:

```
./mvnw -B verify -pl logaperture-adapter-log4j2,logaperture-agent -am \
    -Dlog4j2.version=<V> -Dlog4j2.mode=<MODE> \
    -Dtest='org/logaperture/adapter/log4j2/*Test' -Dit.test='BundledLog4j2*IT' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

(the same shape as the Quarkus job's selection: it does not re-run the other modules' tests in every cell).

**The three modes** (`log4j2.mode`, passed by failsafe to the fixture as a system property):

| Mode | Fixture setup |
|---|---|
| `sync` (default) | Today's: `<Console>` and `<File>` appenders, synchronous loggers. |
| `async-loggers` | `-Dlog4j2.contextSelector=...AsyncLoggerContextSelector` and LMAX Disruptor 3.4.4 on the fixture's Log4j jars (a test-scoped dependency of the agent module, never bundled). Events go through the ring buffer, `MutableLogEvent` is reused. |
| `async-appender` | The configuration puts an `<Async>` appender in front of the console and the file. |

**What the IT asserts, in every cell:** levels and reset; a native change put back (A1); `set handler` and its reset;
a `drop` rule; a `trim` rule on every converter, with no synthetic class name in the output, counted once; all of
it again after a reload; `doctor` reports a plain `File` appender. **New, for the async modes:** a burst of events
alternating a matching and a non-matching message, logged as fast as the fixture can, asserting that no
non-matching event is trimmed and every matching one is (the stale `MutableLogEvent` case of "Acceptance"), and that
the counts agree with what was written. **New, per version:** trim is expected **off** exactly on 2.25.0 to 2.25.3
and on everywhere else, so the cell asserts the `log4j2.trim` row *and* an untrimmed trace on those four and a trimmed
one on the rest, instead of skipping (M8-6). A Log4j that breaks the probe, or one that fixes it, fails the cell.

**The two paths that need an older or a stranger Log4j (A10, A12): moved out of this slice.** The plan was two
`BundledLog4j2EndToEndIT` tests: a fixture on Log4j **2.19.0** (`doctor` has a `log4j2.version` `WARNING`, nothing is
controlled) and one that loads Log4j's `LoggerContext` class and never starts a context (`doctor` has a
`log4j2.not-bound` row at `INFO` after 3 s). Building them showed `doctor` cannot reach those rows when nothing else
is installed, so they wait for [#322](https://github.com/ddeuchert/logaperture/issues/322) ("As built" below), where they are built
([`surface-on-bind-failure.md`](surface-on-bind-failure.md)).

**CI.**

| Where | What | Cells |
|---|---|---|
| `ci.yml`, a new `log4j2` job, every PR and push | the four versions, synchronous (A11), plus 2.25.5 in the two async modes (M8-3) | 6 |
| `.github/workflows/log4j2-nightly.yml`, daily and by hand | every version A11 names, **and the newest 2.x release found on Central** (M8-2), each in the three modes | about 39 |

The nightly resolves its version list in a first job (a fixed list plus the newest `2.x` from Central's
`maven-metadata.xml`, ignoring `3.x` and any `-alpha`, `-beta`, `-rc`; the metadata's own `<release>` is
`3.0.0-beta3` today and must not be used). A version that no longer exists on Central fails that job loudly rather
than dropping out of the matrix. Its summary says whether the newest version is above the highest one the project
has tested by name (2.25.5), so a new minor is noticed even when it passes. Failing cells upload their reports, as the
other jobs do.

**Decisions (agreed 2026-11-03):**

| Id | Question | Options | Recommendation |
|---|---|---|---|
| M8-1 | The floor in the per-PR matrix | (a) List 2.20.0 explicitly. (b) Leave it to the `build` job, which runs on the default. | **(a).** The floor must never be implicit: bumping the default property would silently drop it. It costs one cheap cell, and says what is covered. |
| M8-2 | "Latest" | (a) The newest `2.x` on Central, resolved each night. (b) A pinned list that someone updates. | **(a),** excluding `3.x` and pre-releases (A11's intent: pick up a new release without a code change). Not `<release>`: that is a 3.0 beta. |
| M8-3 | Async modes per PR | (a) A11 as written: every per-PR cell synchronous, async nightly only. (b) Add 2.25.5 in `async-loggers` and `async-appender`. | **(b).** An async regression (reused `MutableLogEvent`, a trim on the wrong thread) is the likeliest to break and the cheapest to catch: two cells. A11 is changed to say so, and the "Acceptance" bullet that says "all three modes in every version of the per-PR set" is corrected to match (it disagreed with A11). |
| M8-4 | How the fixture is told the mode | (a) A Maven property `log4j2.mode`, handed to the fixture by failsafe. (b) Three test classes. | **(a).** One set of tests, three runs; (b) triples the code for no extra coverage. |
| M8-5 | The old-Log4j and never-started fixtures | (a) ITs in the existing class, once, in the default cell. (b) In every cell. | **(a)**, **when they are built, in #322, not in this slice** ("As built"). They do not depend on the cell's version; running them 39 times adds nothing. |
| M8-6 | Trim on 2.25.0 to 2.25.3 | (a) The cell asserts "off" there and "on" elsewhere. (b) Skip where off (today's unit tests). | **(a).** A skip hides a Log4j that fixed the bug, or a new one that has it; an assertion by version range makes the nightly say so. The unit tests keep their `assume`. |
| M8-7 | Telling a human a nightly failed | (a) The workflow fails, GitHub notifies as for any failing run. (b) Open an issue automatically. | **(a).** Nothing to maintain. |
| M8-8 | The setup ITs | (a) Their own slice (9), specified after this one. (b) In this slice. | **(a).** Spring Boot (3.5 and 4.1), Tomcat with two Log4j copies, and a WildFly deployment each need a sample application and a harness (a `springboot` profile like Quarkus's, Testcontainers like WildFly's); with the version matrix that is two PRs of review, not one. |

**Tests.** The deliverable is the tests and the workflows, so the check is: every cell of the per-PR job green
locally for the four versions in `sync` and for 2.25.5 in both async modes; the nightly's resolved version list read (it cannot be run by hand until it is on `main`, "As built"); and a deliberate
break (the probe's expected range changed in a scratch branch) shown to fail exactly the 2.25.0 to 2.25.3 cells.
No guide page changes: the matrix is the project's, not the user's. `DEVELOPMENT.md` gets the command for one cell,
so a contributor can reproduce a failing one.

**As built:**

- **The too-old (2.19.0) and never-started tests are not in this slice.** Building them raised a gap in how the
  Slice 7 findings are reached: the agent publishes its control surface, and sets the marker `logaperture.version`
  that `logctl`'s discovery filters on, only once a context is installed, so for a JVM whose only framework fails to
  bind, `logctl doctor` with no `--pid` finds no candidate and says "Start the application with `-javaagent`" for an
  application that has it. (An earlier version of this note said there was "no MBean to ask". That is wrong for an
  explicit pid: attaching starts the JDK's JMX agent, whose JUL loggers install the `none` container's JUL context, so
  the surface comes up as a side effect and `doctor` shows the row; measured on 2.19.0, 2026-11-03.) Decided with the
  owner on 2026-11-03 as its own issue and spec, [#322](https://github.com/ddeuchert/logaperture/issues/322),
  [`surface-on-bind-failure.md`](surface-on-bind-failure.md) (1.1.0), which also adds these two tests (done there: the
  Functional summary's promise holds for that JVM without `--pid`). The
  `maven-dependency-plugin` copy of 2.19.0 planned for them was left out.
- Handler names depend on the mode: behind an `<Async>` appender the handler is the `<Async>` one (S6-1), so the
  `async-appender` fixture names two (`AsyncOut`, `AsyncDisk`) and the tests ask for the console and disk handler by
  mode.
- **A sentinel makes the output complete.** `LOGGED` is printed by the application as soon as it has logged,
  which an asynchronous appender or logger may not have written yet. The three logging commands end with an error
  line `SENTINEL <tag>`, written after everything before it, and the tests wait for it.
- **A test-harness bug the matrix found, not an adapter one:** the fixture JVM took the adapter's classes onto its
  system class path through the reactor, which production never does (the adapter's classes are stored under a
  prefix inside the agent jar). On 2.25.x, `%xEx` resolves the classes of the probe's own stack trace by name, found
  the adapter's `TrimProbe` on the system loader without Log4j, threw `NoClassDefFoundError`, and the probe reported
  trim off on 2.25.5. The fixture's class path now leaves the adapter module out.
- Checked, as the spec asked: all six per-PR cells green locally; 2.26.1 (the newest) in `sync` and
  `async-loggers` and 2.23.0 in `async-appender` green; the nightly's version list resolved by hand (12 named plus
  2.26.1, 13 versions, 39 cells); and narrowing the trim range to 2.25.0 to 2.25.2 in a scratch change fails the
  2.25.3 cell (trim test and burst test), then restored.
- **The nightly cannot run from `develop` by schedule or by hand.** GitHub runs a `schedule` (and offers
  `workflow_dispatch`) only for a workflow on the default branch, `main`, which gets 1.1 changes only through a
  release. So the workflow also triggers on `push` to `develop` for the paths it tests (the adapter, the agent, the
  detector, `RuleGate`, the workflows): the full matrix runs whenever that code changes. What this does not catch is
  a new Log4j release that breaks the adapter with no change here; the schedule does, from the first release that
  carries the file to `main`. M8-7 is amended accordingly.
- **The floor is compiled at, always, by the `build` job:** a cell's `-Dlog4j2.version` also moves the version the
  adapter compiles against, but the `build` job's full `verify` runs at the default 2.20.0, so a use of an API above
  the floor fails to compile there.
- **CI failure after the merge (found by CI, not locally): the fixture's logger was garbage collected.** Five of
  the six per-PR cells failed with "the agent never listed fixture.Worker" (2.24.3 and 2.25.x; 2.20.0 passed). Log4j's
  registry holds loggers weakly from 2.24.3, and the fixture created its logger and kept no reference; on a runner
  with a small heap a GC dropped it from the context's list. The bind itself was fine. Reproduced locally by forcing a
  GC; fixed by holding the logger in a static field, as an application does. #323 was merged with these checks
  failing; the fix is #324. See "Behaviour to document".
- The sentinel is logged on its own logger (`fixture.Sentinel`), so no rule or native level set on the worker
  touches it. On 2.25.0 to 2.25.3 the trim test goes on past the first rule: a reload keeps the trace whole and
  `doctor` keeps saying why, and the rule's hit count is asserted there too.

**Not in this slice:** the Spring Boot, Tomcat and WildFly setup ITs (Slice 9); the gate benchmark; the guide
pages; mixed `log4j-api` and `log4j-core` versions (A10, still "Not established"); JDK 25.

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
- An application's own context-wide filter that accepts a call (a `MarkerFilter` or
  `DynamicThresholdFilter` with `onMatch=ACCEPT`) ends the composite filter's evaluation before the gate,
  which is added last, so such a call is invisible to drop rules and storm detection (and the level
  fast path would skip a call below the logger's level that the application's filter lets through).
- Two `AppenderRef`s to the same appender in one `LoggerConfig` (with different levels or filters): which
  one Log4j keeps live was not checked, and handler levels read the first. Not a configuration the
  adapter was written for.
- A `LogEvent` built elsewhere and handed straight to a `LoggerConfig` (a bridge that does not go through
  the logger API) does not pass the `Filter` overloads, and Log4j does not call `filter(LogEvent)` for a
  context-wide filter on the normal path (spike G1); such a call is not gated.
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
