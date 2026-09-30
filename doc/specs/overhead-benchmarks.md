# Overhead Benchmarks — Published Numbers for 1.0

Status: signed off, not implemented. Decisions #1–#8 signed off 2026-09-30, all on the recommended
option. Tracked as [#128](https://github.com/ddeuchert/logaperture/issues/128).
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §10 (Performance: "< 200 ns added
per evaluated event … near-zero for loggers no rule can match … enforced by JMH benchmarks in
CI"), §17.1 ("What earns 1.0": "published overhead numbers (§10) for an idle agent, a `trim`
rule, and `top` counting"; "#24, #23 and #18 go in only if the overhead measurement shows they
matter").
Builds on: [`doc/specs/storm-detection.md`](storm-detection.md), [`doc/specs/drop-rule.md`](drop-rule.md)
"Evaluation", [`doc/specs/trim-rule.md`](trim-rule.md) "Evaluation", [`doc/specs/top.md`](top.md)
"Testing" (which names the missing JMH benchmark as a gap).

## Functional summary

After this feature, the user will be able to:

- Read, in the documentation, how much time LogAperture adds to each log call: with the agent
  loaded but nothing configured, with a `drop` or `trim` rule attached, and while `logctl top` is
  measuring.
- See those numbers alongside the machine and JDK they were measured on, and how they change
  when several threads log at once.
- Re-run the same measurements on their own hardware with one documented command.

## Motivation

§10 promises a budget and §17.1 makes publishing numbers against it a 1.0 gate. Nothing measures
it today: there is no benchmark anywhere in the build. Three open issues (#24, #23, #18) are
parked on "measure first", and the release plan can't decide them until something does.

## Findings from reading the hot path (before measuring)

Tracing what runs on a logging thread today, so the benchmark scenarios cover the right things.
A record only reaches LogAperture after it has passed the logger's own level check; anything a
logger level rejects never touches a handler and costs nothing extra.

**Idle agent (no rules).** "Idle" means nothing an operator did, not nothing installed. At
context install both containers (`NoneContainer`, and WildFly through `AggregateLevelControl`)
unconditionally install four pieces on every real handler: the trim `Formatter` wrap, `top`'s
byte-counting `Formatter` wrap on persistent (file-backed) handlers, the storm-detection `Filter`
and the rule `Filter`. `top` and storm detection are always-on by design (top.md, storm-detection.md:
"by the time an operator runs `logctl top`, the volume that mattered already happened"). So the
idle agent pays for `top` counting, and with it #23 and #24, on every record that reaches a
file handler. (An earlier draft of this spec had `idle` as "`top` not started"; there is no such
state.)

The two `Filter`s do this for every record that reaches a real handler:

1. `JulRuleFilter` → `RuleService.evaluateGate`: allocates a `RuleCandidateEvent` plus a message
   `Supplier`, then `decisionCache.computeIfAbsent(record, …)` where `decisionCache` is
   `Collections.synchronizedMap(new WeakHashMap<>())`, **one lock shared by every thread logging
   in that context**, taken even when no rule exists anywhere. It then resolves effective rules
   for the logger name and inserts a weak entry per record.
2. `JulStormFilter` → `StormDetector.observe`: allocates a `StormObservation`, runs **four regex
   `replaceAll` passes** over the raw message (UUID, hex run, digit run, whitespace), builds a
   fingerprint, then a `ConcurrentHashMap.computeIfAbsent` plus a per-fingerprint
   `synchronized` block.

Neither is "near-zero" or "zero allocation on the non-matching path" in §10's sense. The idle
agent is likely to be the largest number published, not the smallest. That's why the idle
scenarios below come first, and why Decision #7 exists.

The first smoke run (2026-09-30) confirmed it: the two filters add microseconds, not nanoseconds,
per call. Filed as 1.0 blockers under Decision #7:
[#129](https://github.com/ddeuchert/logaperture/issues/129) (storm normalization) and
[#130](https://github.com/ddeuchert/logaperture/issues/130) (the rule filter's shared cache).

**`trim` rule.** Same gate path, plus the rule match, plus `JulTrimFormatter` re-rendering the
throwable to N frames on records that match.

**`top` counting.** `ByteCountingFormatter` wraps the persistent handlers' formatters. Per
record: UTF-8 length of the formatted string, a second `printStackTrace` into a `StringWriter`
when the record has a throwable (#23), and `TopCounters.record` under one `synchronized` shared by
every persistent handler in the context (#24).

**#18 is not on the logging path.** `JulLoggingAdapter.resolveHandler` is reached only from
`handlerLevel`, `setHandlerLevel` and `handlerDiagnostics`, which are control operations driven
by `logctl`, never by a log call. No per-record benchmark can make it matter (Decision #6).

## Scope

**In scope:**

- A JMH benchmark module, `logaperture-bench`, covering the scenarios and component benchmarks
  below (Decision #1).
- A results page with the published numbers, the machine, JDK and JMH settings they came from,
  and the command to reproduce them (Decision #5).
- The measurement-based decision for #24, #23 and #18 (Decision #6), recorded in §17.1.
- Amending §10's "enforced by JMH benchmarks in CI" to whatever Decision #4 settles.

**Out of scope:**

- Fixing anything the numbers show. Any fix is its own issue and its own spec change; this
  feature only produces the numbers and the decision rule (see Decision #7 for the idle path).
- The 24-hour WildFly soak (§17.1 lists it separately).
- Logback. The Logback adapter has no rules, `top` or storm detection yet (Release 2).
- Memory overhead (heap retained by the agent). Worth doing, but not what §17.1 asks for.

## Design

### Harness

Benchmarks assemble the real production pieces in-process, the same way `NoneContainer` wires a
context: a `JulLoggingAdapter` over the benchmark JVM's LogManager, a `RuleService`,
`StormService` and `TopService`, with the filters and formatters installed through the same
`install*` calls the container makes. No mocks on the measured path. (Decision #2 covers which
LogManager.)

The logger has one handler whose formatter is a real `SimpleFormatter`-style pattern and whose
output goes to a discarding `OutputStream`. That includes formatting (which LogAperture's
`top` and `trim` wrap and so must be measured with) and excludes disk I/O (which would drown the
numbers in noise and isn't LogAperture's cost). Decision #3.

Every scenario is reported as **added time per log call**: the scenario's score minus the
`baseline` score from the same run, plus the raw scores.

### Scenarios

Each runs with a plain message and with a message carrying a throwable (20-frame stack), because
the storm normalizer, `trim` and `top` all behave differently with one.

| Id | Setup | Answers |
|---|---|---|
| `baseline` | No LogAperture pieces installed | The reference every other row subtracts |
| `idle` | Everything context install puts on the hot path, no rules: rule filter, storm filter, trim wrap, `top` byte counting | §17.1 "idle agent" and "`top` counting", §10 "near-zero", #23 (throwable variant) |
| `drop-miss` | 20 `drop` rules on other loggers, none can match this one | §10 "near-zero for loggers no rule can match" |
| `drop-hit` | 20 rules, one `drop` matches (record denied) | §10 "< 200 ns … ~20 rules" |
| `trim` | 20 rules, one `trim` (5 frames) matches | §17.1 "a `trim` rule" |
Since every scenario includes `top` counting, there is no separate `top` scenario.

**Idle layers.** `idle` is run as cumulative layers, installed in the container's own order, so
each piece's share is the difference between two adjacent rows of the same run: `baseline`,
`rule`, `rule+storm`, `rule+storm+trim`, `idle` (the last adds `top`).

**Storm state.** A benchmark logs one message shape millions of times a second, so storm
detection engages a storm for it within the first thousand calls and measures the engaged path
from then on. That's representative: the engaged and not-engaged paths differ by one counter
update under the same lock.

**Concurrency.** `idle` also runs at 1, 4 and 8 threads (JMH `-t`), each thread on its own child
logger:

- All threads through **one** handler: shows the shared gate lock and storm locks against JUL's
  own per-handler lock, which the baseline already pays.
- Each thread on **its own** handler: the #24 shape (independent handlers that JUL never
  serialized, now sharing `TopCounters`' lock).

### Component benchmarks

The scenarios above measure LogAperture's cost as a difference between two whole log calls.
Alongside them, each hot-path piece is benchmarked on its own, with nothing around it:

| Id | Measures |
|---|---|
| `gate-empty` | `RuleService.evaluateGate` for a context with no rules |
| `gate-20` | `RuleService.evaluateGate` with 20 rules, none matching |
| `storm-observe` | `StormDetector.observe`, plain message and a message with digits, hex and a UUID |
| `storm-normalize` | `StormDetector.normalize` alone |
| `top-record` | `TopCounters.record` |
| `trace-bytes` | `ByteCountingFormatter`'s second stack-trace render (#23) |

These land in the tens of nanoseconds with tight error bars, so they cross-check the
difference numbers: the pieces `idle` installs should add up to roughly what `idle` costs over
`baseline`. If they don't, something in the harness is wrong.

### Measurement method

Timing a single log call with `System.nanoTime()` can't resolve this: its granularity on Linux is
about 20–30 ns, the size of the overhead being measured. JMH times millions of invocations per
iteration and divides, so clock resolution drops out. It also handles JIT warmup, keeps results
alive through a `Blackhole` so the JIT can't eliminate a call, and forks fresh JVMs so one run's
JIT decisions don't decide the result.

- **Settings.** Fork 3, warmup 5 × 1 s, measurement 10 × 1 s, mode `AverageTime` in ns/op. Every
  score is published with JMH's 99.9 % confidence interval.
- **Differences carry combined error.** "Added time" is `scenario − baseline`, and both scores
  have error. The published figure is the difference with the two errors combined
  (`√(e₁² + e₂²)`), not the difference alone. A budget (Decision #8) passes only if the
  difference *plus* its error is under it.
- **Keep the baseline small.** The handler's formatter is a short fixed pattern (timestamp,
  level, logger, message), not a verbose one, so baseline error doesn't swamp a tens-of-nanoseconds
  difference.
- **Quiet, pinned machine.** The published run is on a dedicated Linux machine with CPU frequency
  scaling pinned (turbo off, `performance` governor) and nothing else running, never a CI
  runner. The results page records CPU, core count, OS, kernel, JDK vendor and version, JMH
  version and the frequency settings.
- **Profilers.** Every published run includes `-prof gc` (bytes allocated per call, which checks
  §10's zero-allocation claim exactly, free of timing noise). The `idle` scenario is also run
  under `-prof async` (async-profiler) and the flame graphs are published with the
  numbers; they are the evidence for Decision #7 and for #24. `-prof perfnorm` (cycles,
  instructions, cache misses per call) and `-prof perfasm` are for investigating a surprising
  number, not part of the published run.

**What the numbers don't say.** JMH measures a hot loop in steady state: code and data in cache,
the JIT specialized on this one call site. A real WildFly's log path is colder and its lock
contention depends on the application's threading. The published numbers are a lower bound for
the fast path, and the results page says so. The 24-hour soak (§17.1) is the realistic check.

### The decision rule for #24, #23, #18

Written down before measuring, so the numbers decide rather than get argued:

- **#23** goes into 1.0 if, with a throwable, the `top` layer (`idle` minus `rule+storm+trim`)
  adds more than **25 %** of the `baseline`-with-throwable score. Below that, the second `printStackTrace` is a known cost, not a
  1.0 problem.
- **#24** goes into 1.0 if `idle` at 8 threads on separate handlers takes more than **1.5×** its
  single-thread per-call figure, when `baseline` at 8 threads on separate handlers does not.
- **#18** moves to `1.x` without a benchmark (it's not on the logging path; see Findings).

### Where the numbers go

`doc/overhead.md` (Decision #5), linked from §10. When the `guide/` docs site exists it moves
there. Raw JMH JSON and the async-profiler flame graphs are committed next to it
(`doc/overhead/<date>-<jdk>.json`, `doc/overhead/<date>-<scenario>.html`) so a later run can be
diffed against it.

### Running it

```
mvn -Pbench -pl logaperture-bench -am package
java -jar logaperture-bench/target/benchmarks.jar -rf json
```

## Decisions

**#1 — Where the benchmarks live.**
A. A new reactor module `logaperture-bench`, built only under a `bench` Maven profile, never
deployed. B. `src/jmh` inside `logaperture-adapter-jul`. C. A separate repository.
**Decided: A** (2026-09-30). The scenarios cross core, the JUL adapter and the container wiring, which only a
module downstream of all of them can reach without widening visibility. A profile keeps it out
of the normal `mvn verify`.

**#2 — Which LogManager the benchmark runs on.**
A. JBoss LogManager 3.0.x (what WildFly users run, the 1.0 matrix). B. The JDK's own
`java.util.logging.LogManager`. C. Both.
**Decided: A** (2026-09-30). It's what every 1.0 user runs, and `ExtLogRecord` is what the gate actually sees
there. Plain JUL is Quarkus/plain-JVM territory (#114), not 1.0.

**#3 — What the handler writes to.**
A. Real formatter, discarding stream (formatting counted, I/O not). B. No formatter, no stream
(only filters counted). C. A real file.
**Decided: A** (2026-09-30). `top` and `trim` live inside formatting, so B can't measure them, and C
measures the disk.

**#4 — What "enforced in CI" means for 1.0.**
A. CI runs a short smoke pass of every benchmark (1 fork, 1 iteration) to keep them compiling and
running, with no threshold; the published numbers come from a quiet machine. B. CI fails on a
threshold. C. No CI involvement.
**Decided: A** (2026-09-30), and §10's sentence is amended to say so. Shared GitHub runners vary by ±30 % run
to run, so a threshold gate there either flaps or is set too loose to catch anything.

**#5 — Where the published numbers live.**
A. `doc/overhead.md` now, moved into `guide/` when the docs site exists. B. Wait for `guide/`.
C. The README.
**Decided: A** (2026-09-30). The docs site isn't up yet, and the numbers shouldn't wait on it.

**#6 — The #24 / #23 / #18 decision rule.**
As written in "The decision rule" above: 25 % for #23, 1.5× for #24, #18 to `1.x` now.
**Decided: as written** (2026-09-30). Thresholds are fixed; they don't move after the run.

**#7 — What happens if `idle` or `drop-miss` is over budget.**
A. Treat it as a 1.0 blocker bug: fixed before rc.1 (allowed after the freeze, as a fix), with
its own issue. B. Publish the number honestly, fix in 1.1. C. Decide after seeing the numbers.
**Decided: A** (2026-09-30), with the budget defined in #8. §10 is a promise the 1.0 docs will make; an
idle agent that takes a global lock on every log call contradicts it outright. The likely fixes
(skip the gate cache when a context has no rules; cheaper storm normalization) are local.

**#8 — The budgets the published numbers are measured against.**
A. `idle` and `drop-miss`: ≤ 50 ns added, single thread, and no worse than baseline's scaling at 8
threads. `drop-hit` / `trim`: ≤ 200 ns (§10's number as written). `top`: no budget, report only.
B. §10's wording only ("near-zero", "< 200 ns"), with no number for idle.
Either way, a budget is checked against the difference plus its combined error, not the bare
difference (see "Measurement method").
**Decided: A** (2026-09-30). "Near-zero" can't pass or fail. 50 ns is roughly what one uncontended lock plus
a map lookup costs, so it's achievable without heroics.

**Revised (2026-09-30):** #8 was first agreed while this spec still had `idle` exclude `top`
counting, which it can't (see Findings). The ≤ 50 ns budget applies to the two filters, the
`rule+storm` layer over `baseline`. The trim and `top` layers of `idle` are reported, not
budgeted, as option A intended for `top`.

## Testing

- The CI smoke pass (Decision #4) runs every benchmark once, which catches a scenario that
  throws or silently installs nothing.
- Each scenario's `@Setup` asserts it installed what it claims (the handler's filter is a
  `JulRuleFilter`, the formatter is a `ByteCountingFormatter`, the rule count). A benchmark that
  quietly measures the baseline twice is the failure mode to rule out.
- `drop-hit` asserts after the run that the rule's suppressed count is non-zero; `trim` asserts
  one formatted record has the trimmed frame count.
