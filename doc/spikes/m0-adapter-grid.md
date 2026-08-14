# M0 spike — adapter/container SPI grid

Status: **2 of 3 points done** (plain JVM + Logback; WildFly + JBoss LogManager).
Spring Boot + Logback not yet run.

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

## Point 3: Spring Boot + Logback — NOT STARTED

Needed: confirm the *same* Logback adapter code from point 1 works unmodified inside a
Spring Boot fat jar's `LaunchedURLClassLoader` (§4.4) — this point exists specifically to
separate adapter-behavior concerns (already validated in point 1) from
container-discovery-and-classloader concerns (§15.1), so it should be a much smaller
delta than point 2 if the module split is right.

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

## Next step

Point 3 (Spring Boot + Logback) — expected to be the smallest delta of the three, since
it reuses the same Logback adapter code validated in point 1 and mainly exercises the
classloader/discovery axis (§15.1) rather than new adapter mechanics.
