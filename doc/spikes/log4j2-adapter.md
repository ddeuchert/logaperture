# Spike — Log4j 2 adapter

Status: **in progress.** Plain JVM done (container 1 of 4), including init timing and the async hand-off. Spring Boot, Tomcat and WildFly pending.

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
| T1 | Trim | `AbstractAppender` has no `setLayout`; the layout is final, so §4.3's "wrap `Layout`" isn't possible without rebuilding appenders. What works is wrapping each appender **in its `LoggerConfig` slot**, keeping the original's name, with the `AppenderRef`'s level and filter carried over. On a matching event the wrapper rebuilds it with `new Log4jLogEvent.Builder(e).setThrown(synthetic).setThrownProxy(null)`. Both `%xEx` and `%ex` print the synthetic throwable's `toString()` (`…: outer failure [stack trace trimmed: 5 frames omitted]`) on both versions, so the JUL adapter's synthetic-`Throwable` approach carries over. `config.getAppenders()` still lists the originals, so the handler listing is unaffected. `setThrownProxy` is deprecated in 2.26 but works. |
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
   probed yet).
5. **The reset listener must filter `old == new`**, and re-apply levels, gate, wrappers and handler
   levels in one place. Between the swap and the re-apply, events can pass unfiltered. The
   window is the listener's own run time; not measured.

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

**Remaining hand-off safeguards for the spec:** the verdict carries a hash of the message format,
and the wrapper fails open (no trim) on a mismatch, so a desynced stack can't trim the wrong event.
On 2.17.2 with async loggers the provider ran once on the background thread (101 calls for 100
events). The cause wasn't identified. It popped an empty stack, so it did no harm.

### 2. Spring Boot fat jar (`spring-boot-starter-log4j2`)

_Pending._

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
- JDK 17 and 25: every plain-JVM run used JDK 21.

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
