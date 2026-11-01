# Spike — Log4j 2 adapter

Status: **in progress.** Plain JVM done (container 1 of 4). Spring Boot, Tomcat and WildFly pending.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.3 (the per-framework hook
table), §13 open questions 1 and 2 and item 7, §15.1 (backend × container axes), §17.1 (Log4j 2
at `1.3+`). Related: [`m0-adapter-grid.md`](m0-adapter-grid.md) (left the Log4j 2 render-stage
question open), [`rule-pipeline.md`](rule-pipeline.md).
Issue: [#299](https://github.com/ddeuchert/logaperture/issues/299).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings document,
not the harness that produced it. The harness is not committed. See "Harness" below.

## Scope

Log4j 2, 2.17 and later. Log4j 1 and reload4j are out of scope; they follow separately (§13
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
2. **Explicit level below 2.19.** Either read `LoggerConfig.level` reflectively on 2.17–2.18
   (fail-open to "explicit" when that fails), or raise the floor to 2.19. *Open decision.*
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
   levels in one place. Between the swap and the re-apply, a handful of events can pass
   unfiltered; that matches Logback's window.

**Open from this point** (feeds question 3, Async):

- **No event identity at the gate.** The gate runs before any `LogEvent` exists, and with async
  loggers trim runs on another thread against a `RingBufferLogEvent`. The JUL formatter passes a
  record identity to `RuleGate.evaluate`; Log4j 2 has none to pass at gate stage. Either trim
  re-evaluates the gate statelessly at render time (fine for `trim`, but wrong for anything that
  counts, such as storms), or the decision is carried on the event (§4.2: ThreadContext/context
  data, which async loggers snapshot). Not probed yet.
- **Init timing.** `LogManager.getContext(false)` initialises Log4j if nothing has yet. An agent
  that calls it from `premain` would configure Log4j before the app has set its properties.
  Needs the readiness-gate treatment `plain-jvm-jul.md` gives JUL. Not probed yet.

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

_Pending._

## Harness

Not committed. A single `Main.java` run with the source launcher
(`java -cp "lib-<version>/*" -Dlog4j2.configurationFile=… Main.java <mode>`) against jars
fetched with `mvn dependency:copy-dependencies`. Each probe logs a unique token and reads the
appender's output file back to check where it landed. The gate is an `AbstractFilter` that
denies messages starting `DROPME` and records what each overload receives. The trim wrapper is
an `AbstractAppender` named like the appender it wraps.
