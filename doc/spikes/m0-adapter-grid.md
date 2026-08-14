# M0 spike — adapter/container SPI grid

Status: **1 of 3 points done** (plain JVM + Logback). WildFly + JBoss LogManager and
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

## Point 2: WildFly + JBoss LogManager — NOT STARTED

Needed: a running WildFly instance, the agent attached via `-javaagent:` at boot per
§15.6, and confirmation that WildFly **boots correctly with the agent attached** — the
explicit M0 exit criterion in §17. This is the hardest point on the grid and the one most
likely to surface a real blocker (classloader model, §4.4; JBoss LogManager's `Formatter`
wrapping; no reconfiguration-listener API per §4.3, meaning poll-or-instrument is the only
option).

## Point 3: Spring Boot + Logback — NOT STARTED

Needed: confirm the *same* Logback adapter code from point 1 works unmodified inside a
Spring Boot fat jar's `LaunchedURLClassLoader` (§4.4) — this point exists specifically to
separate adapter-behavior concerns (already validated in point 1) from
container-discovery-and-classloader concerns (§15.1), so it should be a much smaller
delta than point 2 if the module split is right.

## Harness

Not committed (per the spike standard in CLAUDE.md). Reproducible from this doc: a
single-module Maven project (`logback-classic` 1.5.13 as the only dependency, compiled
for Java 21) with one class that (1) prints `getLoggerList()` before and after
instantiating two test loggers, (2) sets a level on an existing and a not-yet-created
logger, (3) installs a `TurboFilter` that denies one test logger at INFO+, (4) wraps the
root logger's console appender's `Encoder` to prefix every line, then logs a handful of
events at various levels to observe all four effects at once.

## Next step

Point 2 (WildFly) before point 3 (Spring Boot) — it's the one the roadmap calls "the
hardest container" and the one most likely to invalidate an architectural assumption, so
it should fail early if it's going to fail at all.
