# Overhead Benchmarks — Published Numbers for 1.0

Status: signed off, not implemented. Decisions #1–#8 signed off 2026-09-30, all on the recommended
option. Tracked as [#128](https://github.com/ddeuchert/logaperture/issues/128).
Decisions #9–#14 (issue #129 follow-up: normalization cache, message variants, revised
budgets, published-run machine) signed off 2026-09-30, all on the recommended option.
Decisions #15–#20 (published run on the development box, percentages, a run-it-yourself suite;
supersedes #14) signed off 2026-10-05, all on the recommended option.
Decision #21 (rule budget scales with the logarithm of the effective rule count; amends §10,
supersedes #8's `drop-hit` / `trim` row) agreed 2026-10-05 after the full run, on #148.
Decision #22 (scenarios for the curve and for what a match saves) agreed 2026-10-05 and implemented.
Decision #23 (the storm filter's per-character allowance is 4 ns, not 3; amends #13) agreed
2026-10-06, on #147.
Decision #24 (storm detection is off by default since #151: `idle` is the shipped default with the
storm filter installed but disabled; disabled storm costs at most 10 % of the enabled budget,
checked by a component benchmark) agreed 2026-10-07.
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
- Read each number as a percentage: how much longer a log call takes with LogAperture than
  without it, on the same machine.
- Re-run the same measurements on their own hardware with one command (Linux, macOS or
  Windows), and get the same percentage table for their machine.

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

Each runs with three messages (Decision #12):

- `template`: the same `String` instance on every call, which is what the storm filter sees for
  parameterized logging (JBoss Logging's `infof`, message loggers), since it reads the
  record's unformatted template. With #129's normalization cache this is the cache-hit path.
- `concatenated`: a new `String` per call with a changing number in it (`"Processed order " +
  n + …`), which is what the storm filter sees for messages built by string concatenation.
  This is the cache-miss path. Building the string is also in `baseline`, so it subtracts out.
- `throwable`: `template` plus a 20-frame exception, because the storm normalizer, `trim` and
  `top` all behave differently with one.

| Id | Setup | Answers |
|---|---|---|
| `baseline` | No LogAperture pieces installed | The reference every other row subtracts |
| `idle` | Everything context install puts on the hot path, no rules, as shipped: rule filter, storm filter installed but disabled (#151), trim wrap, `top` byte counting | §17.1 "idle agent" and "`top` counting", §10 "near-zero" |
| `idle+storm` | `idle` with storm detection enabled (Decision #24) | The top of the layer chain below; #23 (throwable variant) |
| `drop-miss` | 20 `drop` rules on other loggers, none can match this one | §10 "near-zero for loggers no rule can match" |
| `drop-hit` | 20 rules, one `drop` matches (record denied) | §10's rule budget at *n* = 20 (204 ns, Decision #21) |
| `trim` | 20 rules, one `trim` (5 frames) matches | §17.1 "a `trim` rule" |
| `trim-typed` | 20 `trim` rules, each bound to a throwable type; only the last matches | Decision #22: a type-bound rule costs ~nothing on records without a throwable |
Since every scenario includes `top` counting, there is no separate `top` scenario.

**The 20 rules.** `drop-miss` puts its 20 `drop` rules on 20 unrelated loggers, so none is an
effective rule of the logging logger and the gate is skipped. `drop-hit` and `trim` put all 20 on
the logging logger's category: 19 `drop` rules with a message-contains matcher that never matches
(each makes the gate read the message, the most a non-matching rule costs), then the matching
rule last, so every call evaluates all 20. The matching `drop` keeps the default periodic full
sample; the `trim` matches every record and cuts to 5 frames, which only the `throwable` message
gives it anything to do. A denied record is never formatted, so `drop-hit` can come in under
`baseline`; the gate's own cost is `gate-n` below.

**Idle layers.** `idle` is run as cumulative layers, installed in the container's own order, so
each piece's share is the difference between two adjacent rows of the same run: `baseline`,
`rule`, `rule+storm`, `rule+storm+trim`, `idle+storm` (the last adds `top`). Storm detection is
enabled throughout the chain. Two more rows have it installed but disabled, the shipped default
since #151 (Decision #24): `rule+storm-off`, reported against `rule`, and `idle`.

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
| `gate-n` | `RuleService.evaluateGate` with *n* = 1, 5, 20, 100 rules on the logger's parent, none matching (message-contains matchers, as above); Decision #22 |
| `storm-observe` | `StormDetector.observe`, through the per-event fields path the JUL filter uses (#147): a template it has seen, and a new `String` per call |
| `storm-hash` | `StormMessageNormalizer.hash` alone: the per-event normalizing scan, which hashes without building text (#147) |
| `storm-normalize` | `StormDetector.normalize` alone: the normalized text, built only for a new fingerprint since #147 |
| `top-record` | `TopCounters.record` |
| `trace-bytes` | `ByteCountingFormatter`'s second stack-trace render (#23) |
| `storm-off`, `storm-off-delegate` | The storm filter installed but disabled, and the filter it wraps called directly; the difference is disabled storm's cost (Decision #24) |

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

- **#23** goes into 1.0 if, with a throwable, the `top` layer (`idle+storm` minus `rule+storm+trim`; `idle` before Decision #24)
  adds more than **25 %** of the `baseline`-with-throwable score. Below that, the second `printStackTrace` is a known cost, not a
  1.0 problem.
- **#24** goes into 1.0 if `idle` at 8 threads on separate handlers takes more than **1.5×** its
  single-thread per-call figure, when `baseline` at 8 threads on separate handlers does not.
- **#18** moves to `1.x` without a benchmark (it's not on the logging path; see Findings).

### Where the numbers go

`doc/overhead.md` (Decision #5), linked from §10. When the `guide/` docs site exists it moves
there. Each run's raw JMH JSON, `machine.txt`, generated `report.md` and the async-profiler
flame graphs are committed next to it, one folder per run (`doc/overhead/<date>-<jdk>/`), so a
later run can be diffed against it.

### Running it

```
logaperture-bench/run-bench.sh --quick     # Linux, macOS; run-bench.ps1 -Quick on Windows
logaperture-bench/run-bench.sh             # the full, published settings
```

Both scripts build the jar, run the clock check (#18), record the machine and run the same
published set, defined once in the benchmark jar (`Suite`) so the two can't drift apart:

| Run | What | Feeds |
|---|---|---|
| `components` | every component benchmark | the cross-check table |
| `idle-t1` | `IdleBenchmark`, every layer and message, 1 thread, shared handler | the overhead table, #13's budgets, #23 |
| `idle-per-thread-t1` | `baseline` and `idle`, `template`, 1 thread, a handler per thread | #24's single-thread reference |
| `rules-t1` | `RulesBenchmark`, every scenario and message, 1 thread | the rule rows and budgets |
| `savings-t1` | `SavingsBenchmark`, every scenario and message, 1 thread, through a real file handler | "What a match saves" (Decision #22) |
| `idle-t4`, `idle-t8` | `baseline`, `rule+storm` and `idle`; `template` and `concatenated`; both handler shapes | the concurrency rows, #24 |
| `flamegraphs` | `idle`, every message, under async-profiler (only with `--async-profiler`) | the flame graphs; not in the report's numbers |

A thread count above the machine's hardware threads is skipped, not oversubscribed. A report
step in the same jar (`Report`) turns the JSON into `report.md`. The rule scenarios' budgets are
checked against `idle` of the same message, not `baseline`: `idle`'s own layers carry #13's
budgets already, and what §10's rule budget (204 ns at *n* = 20, Decision #21) and "near-zero for loggers no rule can
match" describe is what the rules add on top.

The CI smoke pass (Decision #4) is the `bench-smoke` job in `.github/workflows/ci.yml`: every
benchmark once (1 fork, 1 short iteration, `-foe`), then the report step over its JSON, with the
report in the job summary.

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
threads. `drop-hit` / `trim`: ≤ 200 ns (§10's number as written; superseded by #21). `top`: no budget, report only.
B. §10's wording only ("near-zero", "< 200 ns"), with no number for idle.
Either way, a budget is checked against the difference plus its combined error, not the bare
difference (see "Measurement method").
**Decided: A** (2026-09-30). "Near-zero" can't pass or fail. 50 ns is roughly what one uncontended lock plus
a map lookup costs, so it's achievable without heroics.

**Revised (2026-09-30):** #8 was first agreed while this spec still had `idle` exclude `top`
counting, which it can't (see Findings). The ≤ 50 ns budget applies to the two filters, the
`rule+storm` layer over `baseline`. The trim and `top` layers of `idle` are reported, not
budgeted, as option A intended for `top`.

**Second revision (Decision #13, signed off 2026-09-30).** The first smoke runs (#129) showed a
flat 50 ns for both filters can't hold while every message is normalized: normalization costs
something per character, and on the smoke-run machine just copying a 52-character message into
a new `String` costs about 94 ns. #129 adds a normalization cache, so the storm filter has a
cheap path (a template it has seen) and a length-dependent one (a new string). The budgets:

| Layer over the layer below it | Budget |
|---|---|
| `rule` (rule filter), any message | ≤ 50 ns |
| `rule+storm` minus `rule` (storm filter), `template` | ≤ 100 ns |
| `rule+storm` minus `rule` (storm filter), `concatenated` | ≤ 100 ns + 3 ns × message length in chars (length counted up to 500); **4 ns** since Decision #23 |

For the benchmark's 52-character message, the `concatenated` budget is 256 ns. The numbers are
set now, before the run: 100 ns covers the storm filter's fixed work (one map lookup, one
short lock, a timestamp, two small objects); 3 ns per character is the one-pass normalizer's
measured ~8.6 ns per character on the 2012-era smoke-run CPU at 2.6 GHz, scaled to a current
one.

**Third revision (Decision #23, agreed 2026-10-06, issue
[#147](https://github.com/ddeuchert/logaperture/issues/147)).** The per-character allowance
becomes **4 ns**, so the `concatenated` row reads ≤ 100 ns + 4 ns × message length (length
counted up to 500): 308 ns for the benchmark's 52-character message. The other rows are
unchanged.

The 3 ns was scaled for a current CPU, but #15 moved the published run to a 2019 one (Ryzen 5
3400G, run without boost). After #147 the storm filter hashes the normalized message in one
scan and builds the text only for a new fingerprint. On that machine the scan costs about
3 ns per character on its own (`storm-hash`, 157 ns for the 52-character message) and about
4 ns per character inside a real log call, where the formatter around it evicts its data from
cache. What remains is the scan's floor: one dependent multiply per output character, and a
mispredicted branch wherever the character class changes. The template path, which most
WildFly logging takes, is unaffected and well inside its 100 ns (+35 ns on the #147 A/B run).

**The published-run machine (Decision #14, signed off 2026-09-30; superseded by #15
on 2026-10-05).** Absolute budgets only
mean something on known hardware. The published run uses a current x86-64 or ARM64 desktop or
server CPU (released within the last five years), with frequency scaling pinned as "Measurement
method" describes, and the results page names it. For 1.0 that is a Windows Alienware desktop,
run with `logaperture-bench/run-baseline.ps1` (High/Ultimate Performance power plan; Windows
has no pinned-frequency equivalent of Linux's `performance` governor, so the plan is recorded
with the results). async-profiler doesn't run on Windows, so the published flame graphs come
from a Linux run of the same commit. The smoke-run machine (Intel i7-3740QM, 2012) doesn't qualify; its numbers
serve for before/after comparisons only.

**Fourth revision (Decision #24, agreed 2026-10-07, issue
[#151](https://github.com/ddeuchert/logaperture/issues/151)).** Storm detection is off by default
in 1.0 ([`storm-detection-toggle.md`](storm-detection-toggle.md)). The storm filter stays
installed and, disabled, does one volatile read and calls the filter it wraps. So:

- `idle` means the agent as shipped, storm filter disabled; the headline percentages, the
  concurrency rows (#24) and the rule scenarios measure that. The enabled layer chain ends in
  `idle+storm`, which #23's rule now uses so that `top` stays the only layer between its two rows.
- The `rule+storm` rows keep their Decision #13/#23 budgets, measured with storm enabled, but a
  miss there no longer blocks 1.0 under Decision #7, which covers the idle agent. It is tracked
  by [#150](https://github.com/ddeuchert/logaperture/issues/150) (1.1.0).
- Disabled storm may cost at most **10 % of the enabled budget**: 10 ns against `template`'s
  100 ns, about 31 ns against `concatenated`'s 308 ns. The disabled path never reads the message,
  so one check against the stricter 10 ns covers both. Whole-pipeline differences carry error bars
  of about ±10 ns on the published-run machine, too wide for a budget this size, so the check is
  the component benchmark: `storm-off` minus `storm-off-delegate`, difference plus its error.
  `rule+storm-off` minus `rule` is still reported, as information, not as a check.
- 1 % was considered and rejected: 1–3 ns is below the disabled path's own floor (a filter call
  plus a volatile read) and below what even a component benchmark resolves reliably.

### Revision 2026-10-05: the development box, percentages, and a suite anyone can run

The Windows machine Decision #14 named isn't available for the 1.0 run. Rather than wait, the
published run moves to the development box and the headline becomes a ratio, which carries
across hardware far better than nanoseconds do. The same packaging lets anyone measure their
own machine.

**What "percentage" means.** For each scenario, *overhead* = (scenario − `baseline`) ÷
`baseline`, from the same run, same message variant and same thread count. Its error is the
usual one for a quotient: with *d* the difference and *e_d* its combined error from "Measurement
method", the ratio's relative error is `√((e_d/d)² + (e_b/b)²)`. A row reads, e.g., "`idle`, template,
1 thread: +12 % ± 2 %". Raw nanoseconds stay in the same table, one column over.

The percentage depends on the baseline, so the baseline is part of the claim: one `INFO` call
through JBoss LogManager, a short pattern formatter and a discarding stream. That is about the
cheapest a real log call gets; against a real file handler, or a longer pattern, the same
nanoseconds are a smaller percentage. The results page says so next to the table.

**#15 — Where the published numbers come from (supersedes #14).**
A. The development box: AMD Ryzen 5 3400G (Zen+, 4 cores / 8 threads, 2019), Fedora 44, Temurin
21, with the `performance` governor, boost off, and the TSC clocksource (see #18). The flame
graphs come from the same run. The results page names the machine and says it is a five-year-old
desktop CPU, not a server. B. Wait for the Windows machine.
**Decided: A** (2026-10-05). Nothing about the measurement needs the faster machine once the headline is a
ratio, and a Linux run is the only one that also gets async-profiler flame graphs.

**#16 — The headline number.**
A. Percentage over `baseline` with its error, and raw ns/op plus ns added in the same table.
B. Percentage only. C. Nanoseconds only, as first planned.
**Decided: A** (2026-10-05). The percentage is what a reader can carry to their own machine; the
nanoseconds are what the budgets (#8/#13) and the decision rule (#6) are written in, and what a
later run is diffed against. The nanoseconds describe this box only (a 2019 CPU; a newer one
reports fewer), and the results page says so next to those columns.

**#17 — What happens to the nanosecond budgets.**
A. Keep #8/#13 as written and check them against this box's run. It is older and slower than
the "current CPU" #14 asked for, so a pass here is conservative; a fail here is still a #7
blocker. B. Restate the budgets as percentages. C. Drop the budgets; publish only.
**Decided: A** (2026-10-05). The budgets were set before measuring; restating them now, with numbers in hand,
is the moving-goalposts #6 was written to prevent. #6's own thresholds (#23: 25 %, #24: 1.5×)
are already ratios and carry over unchanged.

**#18 — The clock must be fast.**
Every log record reads the clock (the record's timestamp, storm detection's window), so a slow
clocksource inflates both sides of the ratio and the result means nothing. This box shows it:
the kernel marked the TSC unstable at boot ("frequency skew" against HPET) and fell back to HPET,
where one `System.nanoTime()` costs about 1,440 ns. The fix is a kernel argument
(`tsc=reliable`) and a reboot.
A. The runner measures `System.nanoTime()` first and refuses to run if it costs over 100 ns,
saying why and what to check; `--allow-slow-clock` overrides, and the report then carries a
warning banner. B. Warn and run. C. Don't check.
**Decided: A** (2026-10-05). A slow clock is invisible unless something looks, and the numbers it produces
look plausible.

**#19 — The run-it-yourself suite.**
A. One runner per platform with the same behavior and the same output: `run-bench.sh` (Linux,
macOS) and `run-bench.ps1` (Windows; today's `run-baseline.ps1`, renamed). Each builds the jar
with the Maven wrapper, writes `machine.txt`, runs the clock check (#18), runs the published
set, and then runs a report step (a small Java class in the benchmark jar, so no Python or other
tool is needed) that turns the JMH JSON into `report.md`: the percentage table, the budgets and
decision-rule checks, the machine. Everything goes in one results folder and a zip. Two sizes:
`--quick` (1 fork, short iterations, about 15 minutes, numbers indicative only) and the full run
(the published settings, about 75 minutes on this box; about 85 with Decision #22's additions). The scripts check and record the
frequency settings (governor, boost, power plan on Windows) and print how to pin them, but
don't change them on Linux or macOS, since that needs root. B. Document the raw JMH commands
only.
**Decided: A** (2026-10-05). "One documented command" is the functional summary's promise, and the
percentage table is the part people want; nobody should have to compute it from JMH JSON.

**#20 — The "before fixes" run.**
A. Drop it from the published set. The published numbers describe the code that ships; the
#129/#130 before/after comparison, if wanted, goes on those issues as a one-off. B. Publish both.
**Decided: A** (2026-10-05). Two tables invite comparing a version nobody runs. The `bench/128-before`
branch can be deleted when this merges.

### Revision 2026-10-05: the rule budget scales with the rule count

The full run (#148) put `gate-20` at 779 ± 79 ns and `trim` − `idle` at about 850–900 ns, against
#8's flat 200 ns. Reviewing that result raised a gap in the budget itself rather than a reason
to loosen it: a single number at "~20 rules" says nothing about 5 rules or 100, says nothing
about rules bound to other categories or to a throwable type, and leaves out what a matched
record saves.

**#21 — The rule budget's shape (amends §10, supersedes #8's `drop-hit` / `trim` row).**
A. `50 ns + 35 ns × log₂(n + 1)` added per evaluated event, with *n* the rules effective on the
record's logger: 85 ns at 1, 140 at 5, 204 at 20, 283 at 100, 399 at 1000. B. Keep #8's flat
≤ 200 ns at ~20 rules. C. A linear per-rule allowance.
**Decided: A** (2026-10-05, on #148). This is not the after-the-fact loosening #17 warns about:
at *n* = 20, the only point measured, the curve gives 204 ns, which is §10's 200 ns within
rounding, so #148 is still over budget and still needs a fix. What A adds is the shape: a
logarithm can only be met by matching that is indexed rather than iterated (§10, *One pass over
the message*), which rules out C's linear growth by construction. *n* counts only rules
effective on the logger, so `drop-miss` keeps #8's near-zero ≤ 50 ns unchanged.

**#22 — Scenarios for the curve and for the savings.**
**Decided** (2026-10-05), as follows:
- `gate-n` (`GateCurveBenchmark`) at *n* = 1, 5, 20, 100 effective rules (message-contains
  `drop`s on the logger's parent, none matching), each checked as `gate-n` − `gate-empty`
  against #21's budget for its *n*. It replaces the single `gate-20` point.
- `trim-typed` (a `RulesBenchmark` scenario): 20 five-frame `trim` rules, each bound to a
  throwable type, only the last of which the benchmark's exception is. Without a throwable no
  trim is a candidate, so the `template` and `concatenated` rows get `drop-miss`'s ≤ 50 ns over
  `idle`; the `throwable` row gets #21's budget at *n* = 20.
- Matched-record savings (`SavingsBenchmark`, run `savings-t1`): `baseline` (no LogAperture),
  `drop-hit` and `trim` (the whole agent plus `RulesBenchmark`'s 20 rules) through a **real**
  JBoss `FileHandler` with autoflush on (WildFly's default), with `template` and a 100-frame
  exception ("deep stack"). Single thread. Reported in the report's "What a match saves" table
  as the change against `baseline`, not budgeted; negative means the matched record cost less
  than with no agent. This is the only group that touches the disk; Decision #3 still keeps it
  out of every other scenario. The file goes under the results folder (a real disk, not a tmpfs
  `/tmp`), is truncated at the start of every iteration so disk use stays bounded, and is
  deleted at trial end, after checking that its stack traces have 5 frames under `trim` and
  100 under `baseline`.

## Testing

- The CI smoke pass (Decision #4) runs every benchmark once, which catches a scenario that
  throws or silently installs nothing.
- Each scenario's `@Setup` asserts it installed what it claims (the handler's filter is a
  `JulRuleFilter`, the formatter is a `ByteCountingFormatter`, the rule count). A benchmark that
  quietly measures the baseline twice is the failure mode to rule out.
- `drop-hit` asserts after the run that the rule's suppressed count is non-zero; `trim` asserts
  one formatted record has the trimmed frame count.
