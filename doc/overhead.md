# LogAperture overhead

How much time LogAperture adds to a log call, measured with JMH. The design, scenarios and
budgets are in [`doc/specs/overhead-benchmarks.md`](specs/overhead-benchmarks.md); the
promise these numbers are held to is [`doc/logaperture-spec.md`](logaperture-spec.md) §10.

**Status: the 2026-10-09 run, before 1.0.** It measures the code on `develop` plus the
benchmark module, at commit `39a80d4`, with storm detection off as shipped (Decision #24).
Everything an idle agent or a logger with message rules does is within budget. Three checks are
over, each with an open issue: a record a `drop` rule matches
([#158](https://github.com/ddeuchert/logaperture/issues/158)) and type-bound `trim` rules on a
record with an exception ([#177](https://github.com/ddeuchert/logaperture/issues/177)), both
accepted for 1.0 as measured and scheduled for 1.x (decided 2026-10-09), and storm detection
when it's turned on ([#150](https://github.com/ddeuchert/logaperture/issues/150), 1.1.0). The published numbers
describe the code that ships (Decision #20); this page is replaced by a rerun when any of those
land.

## At a glance

One `INFO` log call, single thread, on the machine below. **Overhead** is how much longer
the call takes with LogAperture than without it, in the same run.

| What's installed | Template message | Concatenated message | With a 20-frame exception |
|---|--:|--:|--:|
| Idle agent: nothing configured | **+13 %** (+93 ns) | **+9 %** (+67 ns) | **+13 %** (+517 ns) |
| 20 `drop` rules on other loggers | +8 % (+59 ns) | +9 % (+67 ns) | +12 % (+486 ns) |
| 20 rules on this logger, a `trim` matches | +31 % (+218 ns) | +35 % (+250 ns) | **−15 %** (−604 ns) |
| 20 rules on this logger, a `drop` matches | +56 % (+389 ns) | +61 % (+443 ns) | **−73 %** (−2,937 ns) |
| Idle agent, storm detection turned on | +18 % (+128 ns) | +57 % (+413 ns) | +13 % (+528 ns) |

How to read it:

- **Template vs concatenated.** A template is the same `String` on every call, which is what
  JBoss Logging, message loggers and `{0}`/`%s` parameterized logging pass. Most WildFly
  logging is in the first column. A concatenated message (`"order " + id + " failed"`) is a
  new string every call; it costs about the same as a template unless storm detection is on,
  which scans it every time.
- **A negative number** means the call took less time than with no agent: the matching rule
  dropped the record or cut its stack trace before it was written.
- **The baseline is about the cheapest real log call there is:** JBoss LogManager, a short
  pattern formatter, output thrown away, about 0.7 µs (4 µs with the exception). Against a
  real file handler the same nanoseconds are a smaller share (see "What a match saves").
- **Only calls that pass the logger's level check pay anything.** A disabled `DEBUG` call
  never reaches LogAperture.
- **The nanoseconds describe this machine only.** A 2019 CPU, run without boost; a newer one
  reports fewer. The percentages carry across hardware better.
- **Run to run, the idle agent moves by a few points.** The same code measured +8.5 % on a
  template on 2026-10-07 and +13.4 % here; between JVMs the JIT doesn't always inline the
  same way. That's also why `drop-miss` (idle plus rules elsewhere) reads below `idle` on a
  template in this run.
- **Errors** are JMH's 99.9 % confidence intervals, combined for differences and ratios.
  Full per-row errors are in the [run report](overhead/2026-10-09-jdk21/report.md).

Where the idle agent's time goes (template / concatenated / exception):

| Layer | Template | Concatenated | Exception |
|---|--:|--:|--:|
| Rule filter (no rules) | −2 ± 16 ns | +13 ± 18 ns | +42 ± 37 ns |
| Storm filter, installed but off | +8 ± 11 ns | +11 ± 21 ns | +42 ± 40 ns |
| `trim` rendering seam and `top` byte counting | +87 ± 12 ns | +43 ± 18 ns | +433 ± 89 ns |

With storm detection turned on, the chain measures each layer separately:

| Layer (storm on) | Template | Concatenated | Exception |
|---|--:|--:|--:|
| Storm detection | +93 ± 14 ns | +364 ± 20 ns | +79 ± 46 ns |
| `trim` rendering seam | −12 ± 11 ns | +11 ± 22 ns | +51 ± 57 ns |
| `top` byte counting | +49 ± 12 ns | +25 ± 21 ns | +356 ± 70 ns |

Each row is the difference between two adjacent layers of the same run. Rows near zero are
within their own error: those layers cost about nothing. With an exception, most of `top`'s
share is encoding the whole record, trace included, to count its bytes; finding the trace in it is about
150 ns (`trace-share`, below).

## Budgets

Checked as the difference **plus its error** against the budget (Decisions #8, #13, #21,
#23, #24). Single thread, one shared handler.

| Check | Message | Measured | Budget | Result |
|---|---|--:|--:|---|
| Rule filter, no rules | template | −2 ± 16 ns | 50 ns | pass |
| | concatenated | +13 ± 18 ns | 50 ns | pass |
| | exception | +42 ± 37 ns | 50 ns | over, on its error bar ¹ |
| Storm filter, off (component: `storm-off` − `storm-off-delegate`) | any | +1.2 ns | 10 ns | pass |
| No rule can match (`drop-miss` − `idle`) | template | −35 ± 12 ns | 50 ns | pass |
| | concatenated | 0 ± 13 ns | 50 ns | pass |
| | exception | −31 ± 91 ns | 50 ns | over, on its error bar ¹ |
| 20 rules, a `trim` matches | template | +125 ± 12 ns | 204 ns | pass |
| | concatenated | +183 ± 17 ns | 204 ns | pass |
| | exception | −1,120 ± 352 ns | 204 ns | pass |
| 20 type-bound `trim`s | template | +9 ± 21 ns | 50 ns | pass |
| | concatenated | +13 ± 9 ns | 50 ns | pass |
| | exception | +538 ± 376 ns | 204 ns | **over** ([#177](https://github.com/ddeuchert/logaperture/issues/177)) |
| 20 rules, a `drop` matches | template | +296 ± 139 ns | 204 ns | **over** ([#158](https://github.com/ddeuchert/logaperture/issues/158)) |
| | concatenated | +375 ± 133 ns | 204 ns | **over** ([#158](https://github.com/ddeuchert/logaperture/issues/158)) |
| | exception | −3,454 ± 158 ns | 204 ns | pass |
| Gate alone, 1 / 5 / 20 / 100 rules | template | +33 / +79 / +171 / +199 ns | 85 / 140 / 204 / 283 ns | pass |
| Storm filter, turned on | template | +93 ± 14 ns | 100 ns | over, on its error bar ² |
| | concatenated | +364 ± 20 ns | 308 ns | **over** ([#150](https://github.com/ddeuchert/logaperture/issues/150), 1.1.0) ² |

1. The measured difference is under budget; the check fails on the upper end of an error bar
   that the 4 µs exception render widens. In each case one fork of three ran about 100 ns
   slower than the other two (a JIT outcome that differs between JVMs, not a slower code path):
   `rule` 4,147 ns against 4,047 and 4,049; `idle` 4,735 ns against 4,449 and 4,483. Same as
   the 2026-10-06 run.
2. Storm detection is off unless turned on (`--storm-detection=on` or `logctl enable storms`,
   #151), so these don't apply to the agent as shipped (Decision #24).

**A matched `drop` ([#158](https://github.com/ddeuchert/logaperture/issues/158)).** A record a
rule matches still goes through the per-record cache that makes one event count once across
handlers, and its weak keys cost the collector work: iterations alternate between about 790 and
1,500 ns, hence the wide error. A dropped record still costs far less than writing it (next
section). **Type-bound `trim`s ([#177](https://github.com/ddeuchert/logaperture/issues/177)).**
Each rule walks the exception's class hierarchy by name, for all 20 rules, several times per
record; the rules' message-side index (#148) doesn't cover types.

## What changed since the first run

The first published run (2026-10-06, [raw results](overhead/2026-10-06-jdk21/report.md)) had
storm detection on and three 1.0 issues open. Same machine and settings:

| | 2026-10-06 | 2026-10-09 |
|---|--:|--:|
| Idle agent, template / concatenated | +18 % / +54 % | +13 % / +9 % (storm detection off by default, #151) |
| Idle agent, with an exception | +102 % | +13 % (`top` finds the trace instead of rendering it again, #23) |
| Gate alone, 1 / 5 / 20 / 100 rules | 582 / 681 / 778 / 1,486 ns | 33 / 79 / 171 / 199 ns (#148) |
| 20 rules, a `trim` matches, template / exception | +1,083 / +5,081 ns over baseline | +218 / −604 ns (#148; #157 for the exception) |
| 20 type-bound `trim`s, exception | +5,381 ns over baseline | +1,054 ns ([#177](https://github.com/ddeuchert/logaperture/issues/177)) |

## Several threads

`idle` at 1, 4 and 8 threads, each thread logging through its own logger. With one handler
shared by every thread, JUL's own handler lock dominates and LogAperture adds nothing
measurable: −3 % at 4 threads and −11 % at 8 on a template message, +2 % and 0 % on a
concatenated one (negative numbers are the lock's scheduling, not a speed-up). With a handler
per thread (no shared JUL lock), the agent adds +6.5 %, +16 % and +25 % on a template at 1, 4
and 8 threads.

The machine has 4 cores and 8 hardware threads, so the 8-thread runs share cores (SMT); the
#24 rule below compares against the baseline's own scaling for that reason.

## What a match saves

One `INFO` call through a real JBoss `FileHandler` (autoflush on), with 20 rules on the
logger, the last of which matches, against no agent through the same handler. The
exception has 100 frames. Reported, not budgeted (Decision #22).

| Matching rule | Message | Change vs no agent | Per call |
|---|---|--:|--:|
| `drop` | template | **−52 %** | 1,079 ns vs 2,235 ns |
| `trim` | template | +14 % | 2,551 ns vs 2,235 ns |
| `drop` | exception | **−96 %** | 1,099 ns vs 24,515 ns |
| `trim` | exception | **−77 %** | 5,763 ns vs 24,515 ns |

A dropped record skips formatting and the write. A trimmed one is written with 5 frames instead
of 100, which on a real handler saves far more than the rules cost. On a template message a
`trim` has nothing to cut (no exception), so it only costs its rules.

## Decision rules

Fixed before measuring (Decision #6):

- **#23 went into 1.0 and is fixed.** The 2026-10-06 run measured `top`'s second stack-trace
  render at +100 % of the baseline with an exception, over the 25 % threshold. `top` now finds
  the trace in the formatter's output (PR #156, and #157 for trimmed traces): its layer costs
  +356 ± 70 ns, +8.8 % ± 1.7 %.
- **#24 stays out of 1.0.** At 8 threads on separate handlers, `idle` takes 1.87 ± 0.04× its
  single-thread time and the baseline 1.59 ± 0.03×; the rule asks for `idle` over 1.5× while
  the baseline is not.
- **#18 moves to 1.x.** It is not on the logging path, so no benchmark can make it matter.

## Component benchmarks

Each hot-path piece alone, with nothing around it, to cross-check the differences above.

| Benchmark | ns per call | Allocated |
|---|--:|--:|
| `gate-empty`: rule gate, no rules | 2.2 | 0 B |
| `gate-n`: rule gate, 1 / 5 / 20 / 100 rules | 35 / 81 / 174 / 201 | 120 / 120 / 384 / 384 B |
| `storm-off`: storm filter installed but off | 2.1 | 0 B |
| `storm-off-delegate`: the filter it wraps, called directly | 1.0 | 0 B |
| `storm-observe`: storm detector, template seen before | 51 | 24 B |
| `storm-observe`: storm detector, new string per call | 347 | 120 B |
| `storm-hash`: the normalizing scan alone | 156 | 0 B |
| `storm-normalize`: the normalized text, built for a new fingerprint | 301 | 248 B |
| `top-record`: `top`'s per-record count | 18.8 | 0 B |
| `trace-share`: finding the stack trace in the formatted record (#23) | 149 | 96 B |
| `trace-bytes`: rendering it again, now only when it can't be found | 4,208 | 16,960 B |

`storm-observe` with a new string includes building that string, which the benchmark does
inside the measured call.

## Flame graphs

async-profiler, CPU, the idle agent on each message (interval-timer sampling, since this
machine's `perf_event_paranoid` is 2):

- [Template](overhead/2026-10-09-jdk21/flamegraphs/idle-template.html)
  ([reversed](overhead/2026-10-09-jdk21/flamegraphs/idle-template-reverse.html))
- [Concatenated](overhead/2026-10-09-jdk21/flamegraphs/idle-concatenated.html)
  ([reversed](overhead/2026-10-09-jdk21/flamegraphs/idle-concatenated-reverse.html))
- [Exception](overhead/2026-10-09-jdk21/flamegraphs/idle-throwable.html)
  ([reversed](overhead/2026-10-09-jdk21/flamegraphs/idle-throwable-reverse.html)): under
  `ByteCountingFormatter`, `traceStart` where the second `printStackTrace` used to be.

They open in a browser from a local checkout; GitHub shows them as source.

## What these numbers don't say

JMH measures a hot loop in steady state: code and data in cache, the JIT specialized on one
call site. A real application's log path is colder, and its lock contention depends on its
threading. These are a lower bound for the fast path. The 24-hour WildFly soak (§17.1) is the
realistic check.

## The run

| | |
|---|---|
| CPU | AMD Ryzen 5 3400G, 4 cores / 8 threads, `performance` governor, boost off |
| OS | Fedora Linux 44, kernel 7.2.8, clock source `tsc` (`System.nanoTime()` 24.6 ns) |
| JDK | Temurin 21.0.12 |
| JMH | 1.37; 3 forks, 5 × 1 s warmup, 10 × 1 s measurement; `-prof gc` |
| Code | `feature/128-overhead-benchmarks` at `39a80d4` (`develop` at `8481b13` plus the benchmarks) |
| Date | 2026-10-09 |

Raw results: [`doc/overhead/2026-10-09-jdk21/`](overhead/2026-10-09-jdk21/), the JMH JSON
for each run, `machine.txt`, and the generated [`report.md`](overhead/2026-10-09-jdk21/report.md)
with every row.

To run the same thing on your own machine (Linux, macOS or Windows), see
[`logaperture-bench/README.md`](../logaperture-bench/README.md):

```
logaperture-bench/run-bench.sh --quick     # about 10 minutes, indicative only
logaperture-bench/run-bench.sh             # the full run, about an hour
```
