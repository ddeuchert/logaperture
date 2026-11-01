# Spike — Log4j 2 adapter

Status: **in progress.** Plain JVM and Spring Boot done (containers 1 and 2 of 4). Tomcat and WildFly pending. Spring Boot, Tomcat and WildFly pending.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.3 (the per-framework hook
table), §13 open questions 1 and 2 and item 7, §15.1 (backend × container axes), §17.1 (Log4j 2
at `1.3+`). Related: [`m0-adapter-grid.md`](m0-adapter-grid.md) (left the Log4j 2 render-stage
question open), [`rule-pipeline.md`](rule-pipeline.md).
Issue: [#299](https://github.com/ddeuchert/logaperture/issues/299).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produced it. The harness is not committed. See "Harness" below.

## Scope

Log4j 2, 2.17 and later, as §11 states; the spike recommends a 2.20.0 floor (plain JVM,
consequence 2). Log4j 1 and reload4j are out of scope; they follow separately (§13
item 7).

## Questions

1. **Level control and reload.** Can levels be read and set through Log4j 2's public API alone?
   Does the API tell an explicit level apart from an inherited one, which baselines need (§5)?
   Can a level be set on a logger that doesn't exist yet (§8.5 Known)? Do overrides survive a
   reconfiguration (`monitorInterval`, `Configurator.reconfigure`, a `Configuration` swap), and
   which public listener re-applies them (§4.3, §15.5)?
2. **Gate and render hooks.** Does a context-wide filter deny events before the layout runs, so
   that `drop` and storm collapse save CPU (§7.1)? For `trim`, does wrapping `Layout` on each
   appender hold up, or is `RewriteAppender`/`RewritePolicy` the better route (§13 open question
   1)?
3. **Async.** Under `AsyncAppender` and disruptor-backed async loggers, does a decision made at the
   gate reach the render stage on another thread (§4.2, §13 item 2)? Do the hooks stay correct
   when `MutableLogEvent` is reused (§9)?
4. **Discovery and effort.** How does the agent find every `LoggerContext` in the JVM
   (`ContextSelector`, `ClassLoaderContextSelector`, more than one context per JVM)? How big is
   the adapter, and which milestone should it land in?

## Environment

One machine. Every result below comes from it.

- Linux (Fedora, kernel 7.2), Temurin JDK 21.0.12.
- Log4j 2.17.2 (the §11 floor) and 2.26.1 (current), each with `disruptor` 3.4.4. A version
  difference is called out where there is one; otherwise both versions gave the same result.
- Each version run three ways: synchronous loggers; all-async loggers
  (`log4j2.contextSelector=…AsyncLoggerContextSelector`); and an `<Async>` appender on the root.
- One config: `app` at DEBUG, `com.vendor` declared with **no** level, root at INFO with three file
  appenders. `XEX` uses `%xEx`, `EX` uses `%ex`, and `WARNONLY` has `level="WARN"` on its root
  `AppenderRef`. `monitorInterval="1"`.

## Findings by container

### 1. Plain JVM — done

**Result: everything LogAperture needs is reachable through log4j-core's public API, with no
instrumentation**, using three hooks: `LoggerConfig` levels, one context-wide `Filter`, and a
wrapper appender in each `LoggerConfig` appender slot. A reconfiguration wipes all three, and a
public listener re-applies them. The traps are listed after the table.

| # | Probe | Result |
|---|---|---|
| D1 | Find the context | `ClassLoaderContextSelector` (default) or `AsyncLoggerContextSelector`; `Log4jContextFactory.getSelector().getLoggerContexts()` lists one context. |
| L1 | Enumerate | `ctx.getLoggers()` lists **instantiated** loggers only (§8.5 Live). `config.getLoggers()` lists the `LoggerConfig`s (`""`, `app`, `com.vendor`). The two sets are different and both are needed. |
| L2 | Explicit vs. inherited | A logger with no `LoggerConfig` of its own resolves to its nearest ancestor's (`config.getLoggerConfig("app.svc.Orders").getName()` is `app`), so "has its own config" is a name comparison. A `LoggerConfig` declared without a level reports the **parent's** level from `getLevel()`. `getExplicitLevel()` returns `null` for it, but that method only exists from **2.19.0**. On 2.17–2.18 the only source is the private field `LoggerConfig.level`. |
| L3a | Set a level | `Configurator.setLevel("app", WARN)` takes effect at once for `app.svc.Orders`. |
| L3b | Pre-set an unknown name | `Configurator.setLevel("app.late.Thing", TRACE)` before the logger exists creates a `LoggerConfig` (additive, no appenders of its own). The logger created afterward is at TRACE, and its events reach the root's appenders exactly once. §8.5 Known works natively. |
| L3c | Undo a pre-set | `config.removeLogger(name)` + `ctx.updateLoggers()` returns the logger to its ancestor's config and level. |
| L3d | `core.Logger.setLevel` | Takes effect, then is **wiped by the next `updateLoggers()`**, which every `Configurator.setLevel` performs. Not usable for overrides. |
| L3e | Level-less config | `setLevel("com.vendor", ERROR)` works. `setLevel("com.vendor", null)` restores inheritance (`getLevel()` back to the parent's INFO), so reset-to-native can restore a level-less config faithfully. |
| H1 | Handler levels | The handler-level analogue is the **`AppenderRef` level** on each `LoggerConfig`. No setter: `lc.removeAppender(name)` + `lc.addAppender(appender, level, filter)` changes it, and INFO then reaches `WARNONLY`. `lc.getAppenderRefs()` still reports `WARN` afterward, so the adapter must track the current value itself. |
| G1 | Gate | `config.addFilter(filter)` takes effect at once, even without `updateLoggers()`. A `DENY` stops plain, parameterised and with-throwable events before any `Message` or `LogEvent` is built, so the layout never runs (§7.1). The filter sees logger, level, the **unformatted pattern** and params (`"param {} {}"`, `[a, 7]`), and the `Throwable`: through the `(Object, Throwable)` overload, or as the trailing param for `"… {}", x, ex`. A `Message` object comes through the `(Message, Throwable)` overload. `filter(LogEvent)` was never called: one evaluation per log call. Runs on the caller thread in all three modes. |
| G2 | Gate cost | The context-wide filter runs **before the level check**: 1000 `trace()` calls on an INFO logger invoked it 1000 times. A filter that returns `ACCEPT` **skips the level check**: with one installed, `trace("… {}", 1)` on an INFO logger was written to the file, on both versions. |
| T1 | Trim | `AbstractAppender` has no `setLayout`; the layout is final, so §4.3's "wrap `Layout`" isn't possible without rebuilding appenders. What works is wrapping each appender **in its `LoggerConfig` slot**, keeping the original's name, with the `AppenderRef`'s level and filter carried over. On a matching event the wrapper rebuilds it with `new Log4jLogEvent.Builder(e).setThrown(synthetic).setThrownProxy(null)`. On 2.17.2 and 2.26.1 both `%xEx` and `%ex` print the synthetic throwable's `toString()` (`…: outer failure [stack trace trimmed: 5 frames omitted]`). **That does not hold on every version in between**; see "Trim rendering across versions" below. `config.getAppenders()` still lists the originals, so the handler listing is unaffected. `setThrownProxy` is deprecated in 2.26 but works. |
| T1 | Trim, async | Sync: wrapper on `main`, event type `MutableLogEvent`. Async loggers: wrapper on the `AsyncLogger` thread, event type `RingBufferLogEvent`; output identical. `<Async>` appender: the root's slot holds `ASYNC`, so the wrapper sits in front of the queue and trims on the caller thread before enqueueing. Output identical in all three. |
| R0 | Listener noise | `ctx.addPropertyChangeListener` gets `"config"` events from **every `updateLoggers()`**, with `oldValue == newValue`, so every `Configurator.setLevel` produces one. A re-apply listener that doesn't skip those recurses forever (seen: stack overflow). |
| R1 | Reconfigure | `ctx.reconfigure()` drops all four kinds of state: level overrides, the gate, the trim wrappers and changed `AppenderRef` levels. The listener fires once with `old != new`, after the new config is started and installed, on the reconfiguring thread. Re-applying all of it inside the listener works: override back, gate back, wrappers back. |
| R2 | File reload | `monitorInterval` reload behaves the same, on the `ConfigurationFileWatcher` thread; the re-applied override held. |

**Design consequences for the adapter**

1. **Levels live on `LoggerConfig`, not `Logger`.** Override with `Configurator.setLevel`. Record
   in the baseline whether the name had its own `LoggerConfig` and its explicit level (possibly
   `null`). Reset either restores that level or removes a `LoggerConfig` that LogAperture created.
   Pre-setting creates a `LoggerConfig`, so `list logger` must not report a created config as a
   native one.
2. **Version floor.** Two gaps below 2.20: no `getExplicitLevel()` before 2.19 (L2), and the
   fluent API skipping the gate before 2.20 (I-F below). The first has a reflective fallback; the
   second has none from the public API. **Decided 2026-11-01: the Log4j 2 adapter's floor is
   2.20.0**, not §11's 2.17. Below it, `drop` and counted rules can't see fluent-API events, and
   the explicit-level fallback would need reflection. The adapter spec and §11's version table
   take this up. The 2.17.2 results above stay as evidence for the decision.
3. **The gate needs a fast path.** Because it runs ahead of the level check on every call,
   including disabled `debug()` in hot loops, its first test has to be a level compare against
   `logger.getLevel()` before any rule lookup. It must never return `ACCEPT` (G2: that skips the level
   check, so a rule would let through events the level should have stopped). Needs a benchmark in `logaperture-bench`
   before the adapter ships (§10).
4. **Trim = slot wrapping, not `Layout` or `RewriteAppender`.** This answers §13 open question 1
   for Log4j 2. `RewriteAppender` is the same idea routed through config and named appenders,
   with nothing gained. The same wrapper is the natural home for `top`'s byte counting (not
   probed yet). The trimmed event also needs its own `ThrowableProxy` (below), and trim has to be
   off on 2.25.0–2.25.3.
5. **The reset listener must filter `old == new`**, and re-apply levels, gate, wrappers and handler
   levels in one place. Between the swap and the re-apply, events can pass unfiltered. The
   window is the listener's own run time; not measured.

#### Trim rendering across versions

Found under Spring Boot 3.5 (Log4j 2.24.3): its `%xwEx` printed `Impl$Synthetic: boom 3`. The
frames were cut, but the original exception type and the trim marker were gone. Rerun on the
plain-JVM harness, first line of the trimmed `T1` event:

| Log4j | `%xEx` (and Spring Boot's `%xwEx`) | `%ex` | With a `ThrowableProxy` subclass |
|---|---|---|---|
| 2.17.2 | correct | correct | correct |
| 2.20.0, 2.23.0, 2.24.0–2.24.3 | **synthetic class name** | correct | **correct** |
| 2.25.0–2.25.3 | **synthetic class name** | **synthetic class name** | still wrong |
| 2.25.4, 2.25.5, 2.26.1 | correct | correct | correct |

- Through 2.24.x and from 2.25.4, `%xEx` renders from `event.getThrownProxy()` and calls
  `ThrowableProxy.formatExtendedStackTraceTo`. `ThrowableProxy` is not final, so the trim wrapper
  sets a subclass on the rebuilt event (`Builder.setThrownProxy`). The subclass calls `super`,
  then rewrites the first line to the synthetic throwable's `toString()`. With it, every version
  outside 2.25.0–2.25.3 renders `java.lang.IllegalStateException: … [stack trace trimmed: …]` under
  both converters. Confirmed on Spring Boot 3.5.16 (2.24.3) and 4.1.2 (2.25.5): 10 of 10 trimmed,
  no synthetic name in the output.
- **2.25.0–2.25.3** render both converters straight from the `Throwable`'s class, with no proxy
  in the path, so there is no public hook. The adapter should make trim a **no-op on those four
  versions** and say so in `doctor`. That's the same fail-safe as the JUL adapter's no-op on
  structured formatters (#83).
- The proxy subclass is a deprecated-API dependency in 2.26 (`setThrownProxy`) and patches text, so it
  is the most version-sensitive piece of the adapter. The adapter spec should give it a
  per-version rendering test against every supported minor.

#### Init timing: when the agent may touch Log4j

A `-javaagent` jar with four premain strategies, against an app that sets
`log4j2.configurationFile` and `log4j2.contextSelector` (async) in `main()` before its first
logger. Same result on 2.17.2 and 2.26.1 unless noted.

| # | Agent does in premain | App's config file | App's async selector |
|---|---|---|---|
| I-1 | `LogManager.getContext(false)` | **lost** (`Default@…`, INFO output gone) | **lost** |
| I-2 | `LogManager.getFactory()` + `getLoggerContexts()` only | **lost on 2.17.2**, kept on 2.26.1 | **lost** |
| I-3 | Touches JUL while `-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager` | **lost** | **lost** |
| I-4 | Daemon thread polls `Instrumentation.getAllLoadedClasses()` for `org.apache.logging.log4j.core.LoggerContext` every 20 ms, then waits for `ctx.isStarted()` | kept | kept |

- I-4 first saw the context in state `INITIALIZED` (class loaded, config not yet read), so
  "class loaded" alone is not the signal: bind only once the context is `STARTED`. It bound 560–750
  ms after premain, after the app's own config was in effect.
- I-3 matters because the agent's own MBean registration initialises JUL (`plain-jvm-jul.md`).
  The shipped agent (`1.1.0-SNAPSHOT`, built from this branch) left the app alone in all four
  combinations (2.17.2 and 2.26.1, with and without `log4j-jul` as the JUL manager), because its
  JUL use comes after the app has started. So 1.0 has no bug here. The constraint is on the
  adapter: **no `org.apache.logging.log4j` class, and no JUL when `log4j-jul` is the JUL manager,
  may be touched before the readiness check passes.** Log4j types belong in classes loaded only
  after it.
- The detector is the same mechanism `plain-jvm-jul.md` decision #2 chose for JUL, watching a
  different class.

#### Gate-to-render hand-off (question 3)

The rule pipeline needs **one verdict per event** across every handler (`RuleService.evaluateGate`
caches by record identity so a rule never double-counts). Log4j 2 offers no record identity at the
gate: the gate runs before any `LogEvent` exists. Keying on the event object later is unsafe,
because `MutableLogEvent` and `RingBufferLogEvent` are reused across events (§9). Re-evaluating
at render would double-count. So the verdict has to be computed once at the gate and carried on
the event.

**What works:** a `ContextDataProvider` (log4j-core SPI, present in 2.17.2 and 2.26.1). The gate
pushes one frame onto a per-thread stack for every call that will build an event: the verdict, or
an empty marker. It pushes nothing for an `isXxxEnabled()` guard (`msg == null`) or a level-disabled
call. The provider pops the top frame when the event is built, on the caller thread, and returns it
as a reserved context-data key. Async loggers copy context data into the ring buffer, so the trim
wrapper reads the verdict from `e.getContextData()` on whichever thread renders. The wrapper then
strips the key before delegating.

| # | Case | Result (2.17.2 and 2.26.1; sync, async loggers, `<Async>` appender) |
|---|---|---|
| H1 | Marked event | Trimmed. |
| H2, H4, H5, H7, H9, H12 | Plain event after a marked one, a level-disabled one, two `isEnabled` guards, a logger-wide rule, a nested call, a fluent call | Not trimmed; no stale verdict. |
| H6 | Logger-wide rule | Trimmed. |
| H8 | Marked outer event whose param's `toString()` logs an inner event | Outer trimmed, inner not. A **single** thread-local slot got this wrong (the inner event took the outer's verdict), because Log4j formats the message while building the event, before it attaches context data. The LIFO stack gets it right. |
| H11 | Fluent API, `atError().withThrowable(ex).log("TRIMME …")` | Trimmed on 2.26.1. **Not seen by the gate at all on 2.17.2**; see I-F. |
| — | Verdict key in output | Visible in `%X` (`{logaperture.verdict=rule-7, user=bob}`) unless stripped; with stripping, absent, and the app's own `user=bob` kept. |
| — | Leftovers | Stack empty on the caller thread after every run; zero format mismatches. |
| — | Cost | One provider call per event built (100 for 100 events), plus one push per enabled call. |

**Registration has to be by service file.** Adding the provider at runtime to the public
`ThreadContextDataInjector.contextDataProviders` had no effect on either version, even followed
by `ctx.reconfigure()`. A `META-INF/services/org.apache.logging.log4j.core.util.ContextDataProvider`
entry on the class path worked. For a `-javaagent` jar on a plain JVM that's free: the agent jar
is on the system class path when Log4j initialises. The provider must stay inert until the
adapter binds. **`logctl attach` (#273) can't register it**, because Log4j has already read its
providers. An attached agent would get levels, `drop` and stateless `trim`, but no counted
rules. Not probed further.

**I-F. The fluent API skips the gate before 2.20.0.** With a `DENY` filter installed,
`log.atError().log("DROPME …")` was still written on 2.17.2, 2.18.0 and 2.19.0: the filter is
called once at `atError()` with a `null` message and never again. From 2.20.0 (checked 2.20.0 to
2.25.0 and 2.26.1) it is called with the message at `log()`, and the event is denied. Each version
was run with matching `log4j-api` and `log4j-core`. The fluent API lives in `log4j-api`, so an app
mixing versions may differ; not tested. Below 2.20, `drop` and counted rules cannot see
fluent-API events.

**Not available in a Spring Boot fat jar.** The agent jar's service entry is found but can't be
linked there. Log4j lives in Spring's `LaunchedClassLoader`, so the provider class (on the system
class path) can't see the `ContextDataProvider` interface. With `-Dlog4j2.debug=true` this shows as
`WARN Unable to load implementation for service …ContextDataProvider` /
`NoClassDefFoundError`. Without that flag it's silent, and the app runs normally. The same will
apply to any container where log4j-core is not on the system class path.

**Recommended instead: count at the gate, recompute trim at render.** In `RuleService`, the only
counted state is in the drop pass (hit counters, sampling, tallies). Choosing a trim
(`mostRestrictiveTrim`) is a pure function of the rules and the event, apart from its hit counter.
So the Log4j 2 gate can run the full verdict once per call and do all the counting, trim hit
included. The slot wrapper then recomputes only the trim choice, without counting, from the same
logger, level, throwable and message. No hand-off, so it works in a fat jar, under `logctl
attach`, and with async loggers, and nothing reserved appears in `%X`. Costs: trim matching runs
at the gate and again in each wrapper for events that have a throwable and are on loggers with a
trim rule. The gate has to format the message itself (`ParameterizedMessage.format`) when a rule
matches on message text. A rule change between gate and render can split one event's decisions.
This is a design recommendation from reading `RuleService`, not a probe. The Spring Boot runs used
a stateless trim in the wrapper, which is its render half.

**Hand-off safeguards, if the provider route is kept:** the verdict carries a hash of the message format,
and the wrapper fails open (no trim) on a mismatch, so a desynced stack can't trim the wrong event.
On 2.17.2 with async loggers the provider ran once on the background thread (101 calls for 100
events). The cause wasn't identified. It popped an empty stack, so it did no harm.

### 2. Spring Boot fat jar (`spring-boot-starter-log4j2`) — done

Spring Boot **4.1.2** (Log4j 2.25.5) and **3.5.16** (Log4j 2.24.3). A Spring MVC app with
Actuator, `spring-boot-starter-log4j2` in place of `spring-boot-starter-logging`, Spring's
built-in Log4j 2 configuration, and `logging.level.app.demo=INFO` and `logging.level.com.vendor=WARN`
in `application.properties`. A scheduled task logs a `debug`, an `info`, an `info("DROPME …")` and an
`error("TRIMME …", ex)` every second. Run as `java -javaagent:… -jar app.jar`. Both versions behaved
the same except for trim rendering (above).

**Result: the plain-JVM hooks work unchanged, once the adapter code is loaded on top of Spring's
class loader. Spring itself adds two things to handle:** it swaps the configuration during
startup, and it sets levels through Log4j's own API.

| # | Probe | Result |
|---|---|---|
| SB1 | Who owns Log4j | `org.springframework.boot.loader.launch.LaunchedClassLoader`; the system class loader can't see log4j-core. The jar also bundles `log4j-jul` and `log4j-slf4j2-impl`; `java.util.logging.manager` is not set. |
| SB2 | Loading adapter code | The detector (system class path, no Log4j types) saw `LoggerContext` load in `LaunchedClassLoader`. It then created a `URLClassLoader` over a separate jar, **not on the system class path**, with `LaunchedClassLoader` as parent, and loaded the Log4j-facing code from it. That code links against Spring's copy of Log4j and installs levels, the gate and the wrappers. This is §4.4's per-context adapter instance. The adapter jar must not be on the system class path, or parent-first delegation would load it there, where it can't link. |
| SB3 | `Configurator` | `Configurator.setLevel` finds its context from the **caller's** class loader, the adapter's child loader, which only reaches the right context because `ClassLoaderContextSelector` walks up to the parent. The adapter should set levels on the `LoggerContext` it holds (`getLoggerConfig` / `addLogger` / `updateLoggers`), never through `Configurator`'s static helpers. |
| SB4 | Startup sequence | Log4j starts on its `DefaultConfiguration` (seen `STARTING` twice, then `STARTED`) about 0.9–1.15 s after launch, and the detector binds to that. About 0.8 s later Spring's `Log4J2LoggingSystem` swaps in its own `log4j2.xml` (from inside `spring-boot-<v>.jar`). The listener fires on `main` and re-applies everything: wrappers, gate and override back. One context throughout. |
| SB5 | Swap ordering | `setConfiguration` runs `updateLoggers()` on the **new** config before it fires the swap event. So the first event after a swap is an `old == new` event already on the new config. Harmless with an idempotent re-apply; the adapter must not assume the swap event comes first. |
| SB6 | Spring's `logging.level` | About 30 ms after the swap, Spring applies `logging.level.*` through `updateLoggers()`. It sets the level **on LogAperture's own `LoggerConfig`** for `app.demo` (DEBUG → INFO), and creates a `LevelSetLoggerConfig` (Spring's subclass) for `com.vendor`. Without a counter-measure the override is lost: zero `debug` lines for the whole run. |
| SB7 | Re-assert on drift | On every `old == new` event, compare each overridden name with its override and re-apply it if it differs. Spring's change is then undone within the same call: no loop, no errors, `debug` lines from the first tick on (10–11 per run). This is the core's existing re-establish-at-any-moment invariant (§15.5), triggered by Log4j's event instead of a sweep. |
| SB8 | Actuator | `POST /actuator/loggers/app.demo {"configuredLevel":"WARN"}` returned `204`, and showed up as an `old == new` event on the HTTP thread. With SB7's re-assert, a following `GET` reports `DEBUG`: **the Actuator change is undone with no message to its caller.** `com.vendor`, which LogAperture doesn't override, kept Spring's `WARN`. |
| SB9 | Gate and trim | Gate: no `DROPME` line in either version. Trim: 10/10 on 4.1.2; on 3.5.16, 0/10 rendered correctly until the `ThrowableProxy` subclass was added, then 10/10 (see "Trim rendering across versions"). |
| SB10 | Shutdown | Spring's shutdown hook calls `updateLoggers()` while Log4j is stopping (root at `OFF`), and SB7 re-asserted into a stopping context. The adapter must skip re-assert once the context is stopping. |
| SB11 | Service file | The `ContextDataProvider` entry from the agent jar fails to link (see "Gate-to-render hand-off"). |

**Design consequences for a Spring Boot integration**

1. **Spring is a native configuration source.** `logging.level.*` and Actuator both write
   Log4j's `LoggerConfig`s, which is the same surface LogAperture overrides. Re-asserting on drift
   (SB7) is what keeps LogAperture's existing contract, that an override holds until reset or
   expiry. What Spring wrote at that moment should become the target's **baseline**, so that
   `reset` restores Spring's value rather than the pre-Spring one.
2. **Actuator conflicts need a decision** (*open, for the Spring Boot integration spec*). With
   SB7, an Actuator change to an overridden logger returns success and is silently undone. Options:
   keep re-asserting and log a WARN naming the override; treat an Actuator change as a native
   change and follow it, like `reset --to-native`; or let Actuator win and drop the override. The
   WildFly integration faces the same question with the management CLI. Its answer should apply
   here.
3. **Binding to Log4j's default configuration is fine, given the re-apply.** No Spring-specific
   readiness signal is needed. An override set from a sticky state file lands on the default
   config, is wiped by Spring's swap, re-applied by the listener, overwritten by `logging.level`,
   and re-asserted by SB7. A lot of churn, but it converges within about 30 ms of Spring's swap.
4. **Not tested yet:** an app's own `log4j2-spring.xml`, devtools restarts (a second class loader),
   Spring Boot's structured logging (`logging.structured.format.console`), and the WAR form of a Spring Boot app
   (Tomcat, next).

### 3. Tomcat webapp with its own `log4j-core`

_Pending._

### 4. WildFly deployment with `use-deployment-logging-config`

_Pending._

## Answers

_One per question above, with the evidence behind it._

## Recommendation

_Adapter shape, size estimate, milestone, and the spec it feeds._

## Not established

- `top`'s byte counting through the slot wrapper.
- The gate's cost with the level fast path, against a bare Log4j 2 baseline (`logaperture-bench`).
- How long the reconfiguration window lasts (events between a config swap and the re-apply).
- An app whose `log4j-api` and `log4j-core` versions differ (I-F).
- `logctl attach` into a running Log4j 2 app: the provider can't be registered then (hand-off).
- The single extra provider call on the async-logger thread on 2.17.2.
- JDK 17 and 25: every run so far used JDK 21.
- Spring Boot: an app's own `log4j2-spring.xml`, devtools restart, structured logging.

## Harness

Not committed. A single `Main.java` run with the source launcher
(`java -cp "lib-<version>/*" -Dlog4j2.configurationFile=… Main.java <mode>`) against jars
fetched with `mvn dependency:copy-dependencies`. Each probe logs a unique token and reads the
appender's output file back to check where it landed. The gate is an `AbstractFilter` that
denies messages starting `DROPME` and records what each overload receives. The trim wrapper is
an `AbstractAppender` named like the appender it wraps.

Init timing: a small `-javaagent` jar whose agent argument picks the premain strategy, and an
app that sets its Log4j properties in `main()`. The real agent was checked with
`logaperture-agent/target/logaperture-agent.jar` built from this branch. Hand-off: a second
program with the stack-based gate, a `ContextDataProvider` registered by a service file, and the
slot wrapper reading and stripping the reserved key; the layout prints `%X` so a leaked key shows.
The fluent-API check ran one class against `log4j-api` + `log4j-core` 2.17.2 to 2.26.1.

Spring Boot: an app built with `spring-boot-maven-plugin` against 4.1.2 and 3.5.16. The probe agent
is two jars. `agent.jar` holds only the detector; `impl.jar`, passed as the agent argument and kept
off the class path, is loaded through a `URLClassLoader` whose parent is the loader that owns
log4j-core. A script starts the app, waits for `Started DemoApp`, posts the Actuator level
change, and reads it back. `-Dprobe.reassert=true` turns SB7 on.
