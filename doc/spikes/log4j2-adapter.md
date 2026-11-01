# Spike — Log4j 2 adapter

Status: **done.** All four containers probed; open items are under "Not established". Spring Boot, Tomcat and WildFly pending.

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

- Linux (Fedora, kernel 7.2), Temurin JDK 21.0.12 for the plain JVM, Spring Boot and Tomcat; WildFly ran
  in its image's OpenJDK 17.0.5. Each container section names its own versions.
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

### 3. Tomcat webapp with its own `log4j-core` — done

Tomcat **11.0.27** (unpacked from the Maven Central distribution, checksum verified), JDK 21. Two
WARs, each bundling `log4j-api`, `log4j-core` and `log4j-jakarta-web` and its own `log4j2.xml`,
which writes to its own file: **`appA` on 2.26.1, `appB` on 2.24.3**. A `@WebListener` logs the same
four lines every second as the Spring Boot app. The probe overrides `app.demo` to DEBUG in `appA`
only, keyed by the context's name. The script deploys both, redeploys `appA` (touches the WAR)
after 12 s, undeploys `appB` (removes the WAR) 22 s later, and stops Tomcat.

**Result: one adapter instance per webapp works, including across a redeploy, and nothing
LogAperture holds keeps a webapp's class loader alive.** It needs one change from the Spring Boot
probe: the bridge class loader must be narrow (TC6).

| # | Probe | Result |
|---|---|---|
| TC1 | Detection | The detector saw two `LoggerContext` classes, one in each webapp's `ParallelWebappClassLoader`, and bound an adapter instance to each, 1.9 s and 2.5 s after launch. Each instance's `LogManager.getFactory()` resolves to its own webapp's copy of Log4j, and lists exactly one context. |
| TC2 | Two versions side by side | 2.24.3 and 2.26.1 ran in the same JVM, each with its own adapter instance compiled against one API. No linkage errors; the trim proxy (`LabelProxy`) worked under both. |
| TC3 | Context identity | `log4j-jakarta-web` names each context after the webapp's context path (`/appA`, `/appB`). That's a stable key across a redeploy (`ContextHandle.stableKey()`, §15.2). `appB` bound first to a `DefaultConfiguration`, and the listener re-applied when the webapp's own config replaced it; `appA` bound straight to its own config. |
| TC4 | Isolation | `appA`: 49 `debug` lines; `appB`: none. Each webapp's gate and trim counted only its own events. In both logs: no `DROPME`, and every `TRIMME` trimmed (49/49 and 41/41), with no synthetic class name. |
| TC5 | Redeploy | `appA`'s context shutdown listener (`LoggerContext.addShutdownListener`, present from 2.20 at least) fired. The adapter instance removed its listener, ended its thread and closed its loader. About 0.6 s later the detector found the new webapp loader's `LoggerContext` class, bound a fresh instance, and re-applied the override by the context's name. |
| TC6 | Stopped-loader trap | The first run's bridge loader (a plain `URLClassLoader` with the webapp's loader as parent) failed during shutdown with `NoClassDefFoundError: java/net/URLClassLoader`, and Tomcat logged `Illegal access: this web application instance has been stopped already`. A stopped `WebappClassLoader` refuses to load **any** class, JDK classes included. Fixed by a bridge loader whose parent is the platform loader, which sends only `org.apache.logging.log4j.*` to the webapp's loader: then zero "Illegal access" messages. |
| TC7 | Leaks | The detector holds the loaders it has bound in a `WeakHashMap`, and checks with `System.gc()` and weak references every 5 s. After the redeploy, the old `appA` loader was collected within one check, and so was `appB`'s after the undeploy. Tomcat logged no leak warnings (no "failed to stop thread", no memory-leak notice). The adapter's threads take their context class loader from the detector thread, the system loader, so Tomcat doesn't attribute them to the webapp. |

**Design consequences for Tomcat**

1. **The bridge loader is narrow by design:** platform parent, adapter classes from the adapter
   jar, only `org.apache.logging.log4j.*` delegated to the loader that owns Log4j. This applies to
   Spring Boot too. There it didn't matter only because the fat-jar loader never stops before the
   JVM exits.
2. **Lifecycle comes from Log4j, not from Tomcat.** The context's shutdown listener tells the
   adapter instance to let go, and the detector's next sweep binds the new instance. No Tomcat API
   is needed. This works only if the detector re-scans indefinitely (`plain-jvm-jul.md` decision #2
   already never gives up) and keys the loaders it has bound weakly.
3. **Backend choice in the `none` integration.** `plain-jvm-jul.md` decision #1 binds **one**
   backend chosen up front. Tomcat's own logging is JUL (JULI), and the webapps here log through
   Log4j 2. Supporting both means binding JUL and every Log4j context in the same JVM. That's
   option (b) of that decision ("bind both, each when it is initialised"), and §15.4's "one JVM,
   several frameworks, at once". The Log4j 2 adapter spec has to revisit the decision. *Open.*
4. **Not tested yet:** Log4j in Tomcat's shared `lib/`, one copy serving every webapp (contexts
   then come from one `ClassLoaderContextSelector` keyed by webapp loader); `log4j-jakarta-web`
   absent (no context name, and no clean shutdown on undeploy); Tomcat 10.1 / `javax`-era
   `log4j-web`.

### 4. WildFly deployment with its own `log4j-core` — done

WildFly **26.1.3.Final** on **JDK 17.0.5** (the `quay.io/wildfly/wildfly:26.1.3.Final-jdk17` image the
dev environment and `WildFlyContainerIT` use), started under Podman with **both** the real
LogAperture agent (`1.1.0-SNAPSHOT`, built from this branch) and the probe agent. Three
`javax.servlet` WARs with the same ticker as Tomcat, logging to `app.<war>.Worker`.

**Scope correction.** The issue named `use-deployment-logging-config`. That attribute covers a
deployment's `logging.properties` and Log4j 1 configuration, **not `log4j2.xml`**. WildFly routes a
deployment's Log4j 2 API calls into JBoss LogManager through its own
`org.apache.logging.log4j.api` module (log4j-api **2.17.2** plus `org.jboss.logmanager.log4j2` in
this image). A deployment's own `log4j-core` runs only when the deployment excludes that module in
`jboss-deployment-structure.xml`. So the three WARs are:

| WAR | Contents | What happened |
|---|---|---|
| `w1` | log4j-api `provided` | `LogManager` from WildFly's module, context `JBossLoggerContext`; events in `server.log`. The shipping agent's JBoss LogManager adapter covers it: `logctl list loggers 'app.*'` showed `app.w1.Worker`, and `set logger app.w1.Worker DEBUG session` produced `debug` lines. **No Log4j 2 adapter needed.** |
| `w1b` | bundles log4j-api, log4j-core, log4j-web 2.26.1 and `log4j2.xml`, **no** exclusion | WildFly's log4j-api 2.17.2 wins over the bundled one. The bundled core fails to start (`log4j-web`: `NoSuchMethodError … LoaderUtil.newCheckedInstanceOfProperty`, then "LogManager returned an instance of …JBossLoggerContextFactory … Unable to initialize Log4j"). Events go to JBoss LogManager as for `w1`, and `logctl` sees them. The bundled `log4j2.xml` is ignored. |
| `w2` | the same bundle **plus** a `jboss-deployment-structure.xml` excluding `org.apache.logging.log4j.api` | The deployment's own Log4j 2.26.1 runs (`LoggerContext` from `vfs:/content/w2.war/WEB-INF/lib/log4j-api-2.26.1.jar`), writing to `w2.log`. **This is the case the Log4j 2 adapter is for.** |

| # | Probe (`w2`) | Result |
|---|---|---|
| WF1 | Detection and binding | log4j-core seen in `ModuleClassLoader for Module "deployment.w2.war"`; bound through the narrow bridge loader (TC6), with no `jboss.modules.system.pkgs` change. The adapter's classes are never seen by the deployment's module. Context named `/w2` (by `log4j-web`). |
| WF2 | Hooks | Override, gate and trim all held: `w2.log` had 22 `debug` lines, no `DROPME`, and 22 of 22 `TRIMME` trimmed. |
| WF3 | Next to the real agent | Both agents ran in one JVM without interfering. The real agent's `logctl list loggers 'app.*'` shows `app.w1.Worker` and `app.w1b.Worker`, **not `app.w2.Worker`**. And `logctl set logger app.w2.Worker DEBUG session` **reports success** (`app.w2.Worker → DEBUG`), because it pre-sets a level on a JBoss LogManager name that `w2` never logs through. Today a `w2`-style deployment gets a reassuring answer that does nothing. |
| WF4 | Redeploy | As on Tomcat: shutdown listener, adapter instance exits, the detector binds the new loader about 0.7 s later, override re-applied by the context's name. |
| WF5 | **`ModuleClassLoader` equality** | JBoss Modules' `ModuleClassLoader` overrides `equals` and `hashCode` (final), and the old and new loaders of a redeployed `deployment.w2.war` compare equal. A `WeakHashMap`-backed "already bound" set therefore kept the old loader as the key, never recorded the new one, and lost both once the old one was collected. The detector must track bound loaders **by identity** (weak references compared with `==`). Checked: no shipping code keys a weak map by class loader today. |
| WF6 | **Dead loaders brought back by the scan** | After a redeploy and after the undeploy, the detector "saw" log4j-core again in the **old** loader. A `System.gc()` had already cleared the weak reference to it, but `Instrumentation.getAllLoadedClasses()` still returned its `LoggerContext` class: the JVM clears weak references before it unloads the classes. That gives the dead loader a strong reference again, and an adapter instance bound to it waited forever for a context that never came, which kept the loader alive (two loaders leaked in that run). Fix: the adapter instance binds only once its copy of Log4j has a context, and **gives up after 3 s with none**, closing its bridge loader. Then each dead loader came back once, was released, and every loader was collected (4 of 4). |
| WF7 | Errors | With the fixes, no probe failures, no linkage errors and no stopped-loader messages in the server log. |

**Design consequences for WildFly**

1. **The Log4j 2 adapter only matters for deployments that exclude WildFly's Log4j API module.**
   Everything else is already JBoss LogManager's. That makes WildFly a lower-priority target than
   Spring Boot or Tomcat.
2. **`doctor` and `set logger` should say when a name isn't reachable.** With no Log4j 2 adapter,
   a deployment that excludes `org.apache.logging.log4j.api` should be detectable: a
   `jboss-deployment-structure.xml` exclusion, or a `LoggerContext` class in a deployment module. A
   `doctor` finding could name it, and `set logger` on a name LogAperture has never seen could warn.
   This is independent of the adapter and could come earlier. *Open.*
3. **WF5 and WF6 apply to every redeploying container**, Tomcat included; the Tomcat run just
   didn't trip them. Identity-tracked loaders and a bounded wait belong in the shared detector,
   not in a container integration. The same resurrection risk applies to any detector that scans
   `getAllLoadedClasses()` for an application-loaded class (the Logback gate in a webapp, later). The
   JUL detector looks for a JDK class, so it isn't affected.
4. **Not tested:** WildFly 27+ (Jakarta EE, `log4j-jakarta-web`), domain mode, and a Log4j 2 shipped
   as a global or static module shared by several deployments.

## Answers

1. **Level control and reload: yes, through the public API.** Levels live on `LoggerConfig` and are
   set on the context the adapter holds, not through `Configurator`'s static helpers. A level can
   be pre-set on a name that doesn't exist yet. Explicit vs. inherited needs `getExplicitLevel()`
   (2.19+). Every reconfiguration wipes LogAperture's state. The context's `"config"` property
   listener re-applies it: real swaps have `old != new`, and `old == new` events come from every
   `updateLoggers()`. The same events show drift, which lets the adapter re-assert overrides
   against Spring's `logging.level` and Actuator. **Floor: 2.20.0** (decided 2026-11-01).
2. **Gate and render hooks: yes, but not the ones §4.3 named.** Gate: one context-wide `Filter`
   (`config.addFilter`). It denies before the message or event is built, it runs **before** the
   level check, so it needs a level fast path, and it must never return `ACCEPT`. Render: the
   layout can't be replaced, so wrap each appender in its `LoggerConfig` slot (answers §13 open
   question 1). The trimmed event carries the synthetic `Throwable` and a `ThrowableProxy`
   subclass, because `%xEx` renders the class name on 2.20–2.24. Trim must be a no-op on
   **2.25.0–2.25.3**, where no public hook reaches the rendering.
3. **Async: works.** The gate always runs on the caller thread. The slot wrapper runs on the
   async-logger thread or, with an `<Async>` appender, before the queue. A `ContextDataProvider`
   can carry the verdict on the event, with a per-thread stack for nested logging. But it only
   links when the agent jar can see Log4j: not in a fat jar or a webapp, and not under `logctl
   attach`. **Recommended:** the gate computes the full verdict once and does all the counting; the
   wrapper recomputes only the trim choice, without counting.
4. **Discovery: a detector, then one adapter instance per Log4j copy.** A daemon thread scans
   `getAllLoadedClasses()` for `org.apache.logging.log4j.core.LoggerContext`, indefinitely. For each
   new owning loader, compared by identity, it creates a narrow bridge loader (platform parent,
   adapter jar, `org.apache.logging.log4j.*` from the owner) and starts an adapter instance in it.
   The instance binds once a context is `STARTED`, gives up after a few seconds with no context,
   and lets go when Log4j's shutdown listener fires. Nothing may touch Log4j, or JUL when
   `log4j-jul` is the JUL manager, before that. Contexts are named stably by `log4j-web`
   (`/appA`, `/w2`); without it the name is a hash (not tested). Every copy tested had exactly one
   context.

## Recommendation

**Build the Log4j 2 adapter**: nothing found blocks it. Its parts, with a rough size from the
existing adapters (Logback is about 410 lines; JUL is larger, with handler work):

| Part | Rough size |
|---|---|
| Levels, baselines, re-apply and drift re-assert | ~300 lines |
| Gate: `Filter`, level fast path, `RuleCandidateEvent` from pattern, params and throwable | ~250 |
| Slot wrapper: trim, `ThrowableProxy` subclass, version gate for 2.25.0–2.25.3; later `top` byte counting | ~300 |
| Handler levels through `AppenderRef` levels | ~200 |
| Shared detector, bridge loader, identity tracking, bounded wait, shutdown handling (`logaperture-agent` / `logaperture-container-none`) | ~300 |
| Tests: a version matrix (2.20, 2.24.x, 2.25.0–3, 2.25.4+, latest), async modes, class-loader leak tests | the largest part |

Very roughly **2–3 weeks**, most of it in the version-matrix and leak tests, on the scale of the
Quarkus JVM-mode estimate (§17.1). The split matters more than the number:

- **The detector and bridge loader are not Log4j-specific.** Logback in a Spring Boot fat jar or a
  Tomcat webapp, which is `1.2.0` work (§17.1), needs exactly the same machinery, WF5 and WF6
  included. **Recommended:** spec and build that machinery with the 1.2 Spring Boot / Tomcat work,
  and keep the Log4j 2 adapter itself at `1.3+`, where it then costs mostly the adapter rows
  above. Alternatively, pull Log4j 2 into 1.2 next to Logback if demand shows up first.
- **Independently and earlier:** a `doctor` finding and a `set logger` warning for names that no
  bound backend can reach (WildFly consequence 2).

**What this feeds:**

- A Log4j 2 adapter spec, `doc/specs/log4j2-adapter.md`, carrying these decisions: the 2.20.0 floor;
  re-assert on drift; the gate-counts / render-recomputes split; trim off on 2.25.0–2.25.3.
- §11's version table: Log4j 2 `2.20+`, replacing `2.17+`.
- §4.3's hook table: Log4j 2's render stage is slot wrapping, not `Layout` or `RewriteAppender`.
- A shared class-loader detector spec (or a section of the 1.2 Spring Boot spec): identity
  tracking, the narrow bridge loader, the bounded wait.
- Open decisions for those specs: Actuator vs. an override (Spring Boot consequence 2); binding JUL
  and Log4j 2 in one JVM (`plain-jvm-jul.md` decision #1, Tomcat consequence 3); the unreachable-name
  warning (WildFly consequence 2).

## Not established

- `top`'s byte counting through the slot wrapper.
- The gate's cost with the level fast path, against a bare Log4j 2 baseline (`logaperture-bench`).
- How long the reconfiguration window lasts (events between a config swap and the re-apply).
- An app whose `log4j-api` and `log4j-core` versions differ (I-F).
- `logctl attach` into a running Log4j 2 app: the provider can't be registered then (hand-off).
- The single extra provider call on the async-logger thread on 2.17.2.
- JDK 25. Plain JVM, Spring Boot and Tomcat ran on JDK 21, WildFly on JDK 17.0.5.
- Spring Boot: an app's own `log4j2-spring.xml`, devtools restart, structured logging.
- Tomcat: Log4j in the shared `lib/`, a webapp without `log4j-jakarta-web`, Tomcat 10.1.
- WildFly: 27+ (Jakarta EE), domain mode, Log4j 2 as a shared module.
- A context name without `log4j-web` / `log4j-jakarta-web`, and more than one context per Log4j copy.

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

Tomcat: the Tomcat 11.0.27 distribution with its default webapps removed and its ports moved, and
two WARs built from one Maven project with `-Dapp.name` and `-Dlog4j.version`. The probe is the
Spring Boot one with three changes: a weakly keyed detector with a leak check, the narrow bridge
loader (TC6), and a shutdown listener that ends each adapter instance. A script deploys,
redeploys, undeploys and stops, and the run is judged on the probe's lines, Tomcat's
`catalina.<date>.log` and each app's own log file.

WildFly: the dev environment's image run directly under Podman (`--userns=keep-id`, so the
deployment scanner can read a scratch directory), with both agents on `JAVA_OPTS` in
`standalone.conf`, the way `docker-compose.yml` does it. Three WARs from one Maven project
(`-Dlog4j.scope`, and a webapp directory with or without `jboss-deployment-structure.xml`). The
probe is the Tomcat one with identity-tracked loaders (WF5) and the bounded wait (WF6), with
identity hashes printed so a rebind can be traced to its loader. `logctl` ran inside the
container (`java -jar /opt/logctl.jar …`), as `wildflyctl logctl` does.
