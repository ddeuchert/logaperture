# M0 spike — adapter/container SPI grid

Status: **3 of 3 points done.** All three grid points complete.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §17 (roadmap, M0 row),
§13 open question 1 ("is render-stage wrapping durable?"), §15.1 (the two orthogonal
axes this grid is meant to separate).

This is a spike per [`CLAUDE.md`](../../CLAUDE.md): the deliverable is this findings
document, not the harness that produced it. The harness code is not committed — see
"Harness" below for how to reproduce.

## Point 1: plain JVM + Logback — DONE

**Question answered:** can level control, gate-stage filtering, and render-stage
wrapping all be done through Logback's public API alone, with no bytecode
instrumentation, in the simplest possible container?

**Result: yes, cleanly, for all four M0 operations.**

| Operation | API used | Result |
|---|---|---|
| Enumerate contexts | `LoggerContext.getLoggerList()` | Only returns instantiated loggers — confirms the Known/Live split in §8.5 is a real constraint, not a hedge. `getLevel()` is `null` on inherited loggers vs. an explicit value on ones with their own setting — this is exactly the baseline-capture distinction §5 needs ("user set INFO" vs. "app was already INFO"). |
| Set a level | `LoggerContext.getLogger(name).setLevel(Level)` | Works on existing loggers. Also works on a name that has **never been instantiated** — set it first, then create the logger via SLF4J afterward, and the level was already in effect. This confirms the §8.5 "Known" state (pre-setting a level on a logger that doesn't exist yet) is supported by Logback natively, not something LogAperture has to fake. |
| Install a gate-stage filter | `LoggerContext.addTurboFilter(TurboFilter)` | Context-wide, decides `DENY`/`NEUTRAL`/`ACCEPT` before the event is built. Denied exactly the expected 2 of 3 test events; the third (below the filter's own threshold) passed through, which is the correct decide-before-formatting semantics §7.1 relies on for storm collapse to reclaim CPU, not just disk. |
| Wrap a render-stage formatter | Swap `Encoder` on an `OutputStreamAppender` (`appender.stop()` → `osAppender.setEncoder(wrapped)` → `appender.start()`) | The replacement `Encoder` delegates to the original and post-processes its output; every subsequent line came out transformed. No subclassing of framework internals, no reflection into private fields — `Encoder` and `OutputStreamAppender` are both public, documented extension points. |

**Bearing on §13 open question 1:** for Logback specifically, encoder wrapping is a
first-class, public-API operation (`Appender.stop()`/`setEncoder()`/`start()`), not a
workaround. Nothing here required instrumentation. This is one data point, not a proof —
the same question still needs answering for Log4j 2's `Layout`/`RewriteAppender` path and
JBoss LogManager's `Formatter`, which is what points 2 and 3 exist to check, and it needs
re-checking across Logback's 1.2.x/1.3.x/1.4.x/1.5.x range (§11) before the answer is
"yes" project-wide rather than "yes, on 1.5.13."

**Secondary finding — reconfiguration risk not yet exercised.** This point only tests a
single Logback `LoggerContext` that never reconfigures. §6.5 ("reconfiguration
re-application") and the idempotent-wrapping concern it raises are untested here — a spike
against a config-reload trigger should happen before M1 commits to the wrapping approach
as durable, not just installable.

## Point 2: WildFly + JBoss LogManager — DONE

**Question answered:** does the same class of operation work against JBoss LogManager's
`java.util.logging`-based API, and — the explicit M0 exit criterion in §17 — **does
WildFly boot correctly with the agent attached?**

**Result: yes, on both counts.** WildFly 26.1.3.Final booted cleanly with a `-javaagent:`
attached (3.6s–5.0s across two runs, "298 of 538 services" started, no errors or warnings
attributable to the agent). All four M0 operations worked, and were confirmed **stable
for at least 12 seconds post-boot** via a delayed recheck — no evidence that WildFly's own
logging-subsystem boot processing (which parses `standalone.xml`'s `<logger>` /
`<root-logger>` config) resets a level set at runtime before it finishes.

| Operation | API used | Result |
|---|---|---|
| Enumerate contexts | `LogManager.getLoggerNames()` | Confirms §4.3's "single global LogManager" note — one flat enumeration, no per-context/per-classloader split the way Logback has. 32–42 loggers registered by the time the agent ran, the majority `org.jboss*`. |
| Set a level | `Logger.getLogger(name).setLevel(Level)` | Worked both on a fresh logger (`spike.demo.Worker`) and on a **real WildFly-owned logger** (`org.jboss.as.server`), raised from its configured `INFO` (via `<root-logger><level name="INFO"/>`) to `FINE`. The raised level then surfaced WildFly's own internal `DEBUG`-level boot messages (e.g. "Parsed standalone configuration in [791] ms") that are invisible at the default level — this is the diagnostic value proposition working exactly as intended, on the framework's own boot noise. |
| Install a gate-stage filter | `Logger.setFilter(java.util.logging.Filter)` | Denied exactly the 2 expected events (INFO/WARN on a test logger); the below-threshold event passed through, same decide-before-formatting semantics as point 1. |
| Wrap a render-stage formatter | Swap `Formatter` on a `Handler` (`FILE`, the `PeriodicRotatingFileHandler`) via `Handler.setFormatter()` | Every subsequent line written to `server.log` came out prefixed, durably, through to the end of the boot sequence and the 12-second recheck window. Also confirmed the **`CONSOLE` handler is a separate `Handler` with its own level** (`INFO`, fixed in `standalone.xml`) independent of the `FILE` handler's — wrapping one handler's formatter has no effect on another's output. |

**Important secondary finding — handler-level thresholds are a second gate, independent
of logger level.** `standalone.xml` configures `CONSOLE` at a fixed `level="INFO"` and
`FILE` with no explicit level (defaults to `ALL`). Raising a logger's level to `FINE` is
necessary but **not sufficient** — the `FINE` messages only reached `server.log` (the
`FILE` handler) and never appeared on the console, because `CONSOLE`'s own handler-level
floor silently discards anything below `INFO` regardless of what the logger allows
through. This is a real operational trap for Feature 1 (§5): `listLoggers` reporting an
"effective level" of `FINE` would be actively misleading if the operator's actual output
sink — often the console in a support/troubleshooting scenario — has a stricter handler
floor underneath it. **The level-control API for this adapter needs to surface handler
levels alongside logger levels**, not just the logger's own effective level, or `setLevel`
needs an option to also lower the relevant handler(s). Neither Logback nor JUL/Log4j2 have
an exact equivalent of this two-tier gate in quite the same shape (Logback's appenders
don't have a separate level filter by default), so this is JBoss-LogManager-specific
behavior worth documenting explicitly in the adapter, not assumed to generalize.

**One investigative wrinkle, noted for honesty:** the first of the two runs appeared to
show `FINE` events missing entirely, which briefly looked like a reconfiguration-reset
problem (§6.5). It turned out to be investigator error — checking `console.log` (the
`CONSOLE` handler, fixed at `INFO`) instead of `server.log` (the `FILE` handler, where the
wrap and the lowered level actually applied). Worth keeping in mind for real testing: it's
an easy mistake to make, and it's a small preview of exactly the "configured vs. effective,
side by side, per sink" visibility problem §8.5 already calls out.

**Bearing on §13 open question 1:** a second data point, same conclusion as point 1 —
render-stage wrapping via the public `Handler`/`Formatter` API is durable and needs no
instrumentation, at least across a single boot-to-12s-post-boot window on WildFly
26.1.3.Final / JBoss LogManager. Longer-running durability (across an actual `:reload` or
management-triggered logging reconfiguration) is still untested and should happen before
M1 treats this as settled — this spike never exercised WildFly's `/subsystem=logging`
management API at all, only the direct `java.util.logging` path.

**Classloader model (§4.4):** not meaningfully exercised by this point — a `-javaagent:`
premain on a fresh boot with no deployments doesn't touch per-webapp classloaders at all.
Genuinely testing §4.4's "no assumption of exactly one logging context" claim needs a
deployed application, which this point deliberately didn't include (kept minimal to
isolate the container-boot question first). Flag this as a real gap, not a covered case.

## Point 3: Spring Boot + Logback — DONE

**Question answered:** does the same class of operation work when Logback is loaded
inside a Spring Boot fat jar's own classloader (`LaunchedClassLoader`, the JDK 17 line's
name for what §4.4 calls `LaunchedURLClassLoader`) rather than the system classloader —
and does an agent with **zero compile-time dependency on Logback or SLF4J** manage to
reach across that boundary at all?

**Result: yes, but only after finding and fixing a real timing bug along the way** — the
most valuable outcome of this point, and worth walking through rather than just stating.

| Operation | Approach | Result |
|---|---|---|
| Enumerate contexts | `Instrumentation.getAllLoadedClasses()` to find `ch.qos.logback.classic.LoggerContext` once loaded, then pure reflection (`LogManager.getLoggerNames()`-equivalent via `getLoggerList()`) | Worked once binding was correctly detected (see below). |
| Set a level | `Logger.setLevel(Level)` via reflection, `Level.DEBUG` obtained via `Class.getField("DEBUG").get(null)` on a `Level` class loaded through the app's own classloader | Worked, including on a logger the app hadn't created yet, same as points 1 and 2. |
| Install a gate-stage filter | A `TurboFilter` subclass, compiled separately against Logback as `provided`-scope, loaded via a **child `URLClassLoader` parented on the app's own classloader** so its superclass resolves to the exact same `TurboFilter` Class object the app's `LoggerContext` expects | Worked — `TurboFilter` is an abstract class, not an interface, so a JDK dynamic `Proxy` can't stand in for it; parenting a loader on the target classloader is the actual mechanism, not a shortcut. |
| Wrap a render-stage encoder | JDK dynamic `Proxy` implementing `ch.qos.logback.core.encoder.Encoder` (an interface, so `Proxy` applies directly), `InvocationHandler` delegating every method to the original encoder via reflection except `encode()` | Worked, and confirms the render-stage wrap doesn't need the child-classloader trick when the type being replaced is an interface — only abstract classes need it. |

**The real finding: detecting "the framework is ready" is harder than it looks, and
getting it wrong fails silently.** First attempt used class-load detection
(`getAllLoadedClasses()` seeing `LoggerContext`) as the readiness signal, exactly as point
2 did for JBoss LogManager. It compiled, ran, and threw no exception — and every single
operation was silently undone within a few seconds, because:

1. **The class being loaded is not the same signal as SLF4J having bound to it.** The
   first run's `LoggerFactory.getILoggerFactory()` call returned
   `org.slf4j.helpers.SubstituteLoggerFactory` — SLF4J 2.x's placeholder that queues calls
   during its own init window — not the real `LoggerContext`, even though the
   `LoggerContext` class was already loaded and visible to `getAllLoadedClasses()`. Fixed
   by polling the actual return value of `getILoggerFactory()` until it's an `instanceof`
   the discovered `LoggerContext` class, not just checking whether the class exists.
2. **Even after SLF4J is genuinely bound, Spring Boot's own `LoggingSystem` hasn't
   necessarily finished configuring it.** With only the fix above, every operation
   *appeared* to succeed (no exceptions, correct log output at the moment of the call) —
   and then silently reverted: `worker.setLevel(DEBUG)` read back as `null` two seconds
   later, the wrapped appender was a completely different object instance, the installed
   `TurboFilter` stopped denying anything. Spring Boot's `LoggingSystem` resets and
   reconfigures the `LoggerContext` (new appenders, cleared levels) as part of its own
   bootstrap, and that reset happens **after** SLF4J's first binding, not before. A fixed
   3-second wait after binding was enough to land after that reset in this setup, and
   every operation then held steady for the full 10-second recheck window — level stayed
   `DEBUG`, the filter's deny count climbed by 2 every 2 seconds (matching the app's
   1-second tick), the appender instance and the wrapped encoder both stayed put, and the
   actual log output showed `[WRAPPED]` prefixes and suppressed `Noisy` lines from that
   point on.

**This is not a Spring-Boot-specific curiosity — it's the general case §6.5 already names,
demonstrated concretely.** A fixed delay is not an acceptable answer for the real product;
it's a coin flip against however long a given container's startup happens to take on a
given machine. The correct architecture, already implied by §6.5's "register for
reconfiguration events... on reconfiguration: re-capture baseline, re-install filters and
wrapped encoders, re-apply live overrides. Be idempotent," is to **treat every install as
provisional until a `LoggerContextListener` confirms the context has stabilized**, and
react to every subsequent reset the same way, rather than trying to find a safe moment to
install once. This point makes that requirement concrete rather than theoretical — for at
least one real, popular container, the reset is not a rare edge case, it is the default
sequence of events on every single boot.

**Bearing on §4.4 (classloader model):** the zero-compile-dependency approach works, and
the two techniques needed — reflection for concrete calls, a child classloader parented on
the target for subclassing an abstract framework type, `Proxy` for interfaces — are exactly
the toolkit §4.4's "per-context adapter instances, holding reflective or direct references
into the classloader that owns the framework classes" describes. Confirmed concretely
rather than assumed.

**Bearing on §13 open question 1:** third data point, same encoder/formatter-wrapping
conclusion as points 1 and 2 — durable, public-API-only, no instrumentation — *once
installed after the framework has actually settled*. All three points now agree on the
mechanism; none of them yet cover a version other than the one tested, or a live
`:reload`/redeploy after the initial boot has fully completed.

## Harness

Not committed (per the spike standard in CLAUDE.md).

**Point 1**, reproducible from this doc: a single-module Maven project
(`logback-classic` 1.5.13 as the only dependency, compiled for Java 21) with one class
that (1) prints `getLoggerList()` before and after instantiating two test loggers, (2)
sets a level on an existing and a not-yet-created logger, (3) installs a `TurboFilter`
that denies one test logger at INFO+, (4) wraps the root logger's console appender's
`Encoder` to prefix every line, then logs a handful of events at various levels to
observe all four effects at once.

**Point 2**, reproducible from this doc: a single `-javaagent:` jar with no dependencies
(pure `java.util.logging` API, JDK 17 compile target), `Premain-Class` pointing at a
class whose `premain` spawns a daemon thread that **polls `System.getProperty(
"java.util.logging.manager")` only — never touching `java.util.logging` itself — until
it reads `org.jboss.logmanager.LogManager`**, then waits an extra 1.5s and runs the same
four checks as point 1, adapted to the JUL API (`LogManager.getLoggerNames()`,
`Logger.setLevel()`, `Logger.setFilter()`, swapping a `Handler`'s `Formatter`), against
both a fresh test logger and the real `org.jboss.as.server` logger. Then rechecks state
every 3s for 12s. Launched against a stock WildFly 26.1.3.Final install (JDK
17.0.20-tem), with an isolated `jboss.server.base.dir` (copied `configuration/` only, no
deployments) and `jboss.socket.binding.port-offset=100`, so it never touched an existing
instance's data or config.

That poll-before-touching-JUL discipline is itself a finding worth keeping, not just
scaffolding: a `-javaagent:premain` that touches `java.util.logging` before WildFly sets
`java.util.logging.manager` would permanently lock in the JDK's default `LogManager`
instead and break WildFly's own logging bootstrap. Any real adapter-install/framework-
detection logic (§4.1) for this container needs the same discipline — detect readiness
via a side channel first, never via the API you're about to take over.

**Point 3**, reproducible from this doc: three small Maven modules. (a) A Spring Boot 3.3.4
fat jar (`spring-boot-starter` only, JDK 17) with a `CommandLineRunner` that ticks once a
second for 20s logging at `spike.demo.Worker` (INFO+DEBUG) and `spike.demo.Noisy` (INFO),
then exits. (b) A one-class adapter jar, compiled with `logback-classic` as `provided`
scope only, containing the `TurboFilter` subclass. (c) The agent — zero compile-time
dependency on Logback/SLF4J, `Premain-Class` pointing at a class whose `premain` spawns a
daemon thread that polls `Instrumentation.getAllLoadedClasses()` for
`ch.qos.logback.classic.LoggerContext`, then polls `LoggerFactory.getILoggerFactory()`
until it's actually an instance of that class (not the SLF4J substitute), waits 3s more,
then runs all four operations via reflection plus the child-classloader/`Proxy` techniques
described above, then rechecks every 2s for 10s. Launched as
`java -javaagent:agent.jar=/path/to/adapter.jar -jar app.jar`.

## M0 conclusion

All three grid points pass their exit criteria (§17): plain JVM + Logback, WildFly +
JBoss LogManager, and Spring Boot + Logback all support enumerate/set-level/gate-filter/
render-wrap through public APIs alone, no bytecode instrumentation required anywhere, and
WildFly boots cleanly with the agent attached. That's the M0 exit criterion, satisfied.

The three points did **not** produce the same finding, and that's the actual value of
running all three rather than stopping at one:

- Point 1 (plain JVM) found the mechanism sound with nothing to complicate it — the
  simplest case, deliberately.
- Point 2 (WildFly) found a **second, independent gate** (handler-level thresholds) that
  the level-control model needs to account for, and confirmed no reset problem in that
  container over a 12s window.
- Point 3 (Spring Boot) found the opposite of point 2's stability result: a **reliable
  reset-after-install** race that a fixed delay works around in testing but that no fixed
  delay can be trusted to work around in production, on arbitrary hardware and arbitrary
  application startup work.

That divergence is itself the headline finding for M1 planning: **reconfiguration
re-application (§6.5) is not an edge case reserved for `logctl reload` or a rare config
hot-swap — it is the ordinary sequence of events on a default Spring Boot boot.** Layer 0
(§17) needs a `LoggerContextListener`-driven re-apply path from the start, not as a
later hardening pass, for at least the Logback adapter. Whether the same is true for
JBoss LogManager's boot sequence under other WildFly configurations, or for Log4j 2's
`ConfigurationListener` path, is still open — this spike only found the Spring Boot case
because point 3 happened to go looking for it.

**Recommended next step:** fold the reset-detection requirement into
[`doc/specs/level-control.md`](../specs/level-control.md) before implementation starts —
specifically, baseline capture and override re-application need to be triggered from a
`LoggerContextListener`, not from a one-time install routine, even in the M1 slice that
is otherwise scoped to the `none` container only. A "no reconfiguration ever happens"
assumption, which the current spec's semantics section is close to making, does not
survive contact with the Spring Boot case found here.
