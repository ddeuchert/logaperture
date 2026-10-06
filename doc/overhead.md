# LogAperture overhead

How much time LogAperture adds to a log call, measured with JMH. The design, scenarios and
budgets are in [`doc/specs/overhead-benchmarks.md`](specs/overhead-benchmarks.md); the
promise these numbers are held to is [`doc/logaperture-spec.md`](logaperture-spec.md) §10.

**Status: the 2026-10-06 run, before 1.0.** It measures the code on `develop` plus the
benchmark module, at commit `5ba0d6b`. Two things are still over budget, each with an open
1.0 issue: the storm filter on messages built by string concatenation
([#147](https://github.com/ddeuchert/logaperture/issues/147); fixed in part, see below) and
the rule gate once rules apply to a logger
([#148](https://github.com/ddeuchert/logaperture/issues/148)). This page is replaced by a
rerun once those land; the published numbers describe the code that ships (Decision #20).

## At a glance

One `INFO` log call, single thread, on the machine below. **Overhead** is how much longer
the call takes with LogAperture than without it, in the same run.

| What's installed | Template message | Concatenated message | With a 20-frame exception |
|---|--:|--:|--:|
| Idle agent: nothing configured | **+18 %** (+126 ns) | **+54 %** (+404 ns) | **+102 %** (+4,189 ns) |
| 20 `drop` rules on other loggers | +21 % (+148 ns) | +55 % (+407 ns) | +103 % (+4,217 ns) |
| 20 rules on this logger, a `trim` matches | +154 % (+1,083 ns) | +181 % (+1,344 ns) | +124 % (+5,081 ns) |
| 20 rules on this logger, a `drop` matches | +27 % (+192 ns) | +20 % (+146 ns) | −78 % (−3,193 ns) |

How to read it:

- **Template vs concatenated.** A template is the same `String` on every call, which is what
  JBoss Logging, message loggers and `{0}`/`%s` parameterized logging pass. Most WildFly
  logging is in the first column. A concatenated message (`"order " + id + " failed"`) is a
  new string every call and pays the storm filter's full scan each time.
- **The baseline is about the cheapest real log call there is:** JBoss LogManager, a short
  pattern formatter, output thrown away, about 0.7 µs. Against a real file handler the same
  nanoseconds are a smaller share (see "What a match saves").
- **Only calls that pass the logger's level check pay anything.** A disabled `DEBUG` call
  never reaches LogAperture.
- **The nanoseconds describe this machine only.** A 2019 CPU, run without boost; a newer one
  reports fewer. The percentages carry across hardware better.
- **Errors** are JMH's 99.9 % confidence intervals, combined for differences and ratios.
  Full per-row errors are in the [run report](overhead/2026-10-06-jdk21/report.md).

Where the idle agent's time goes (template / concatenated / exception):

| Layer | Template | Concatenated | Exception |
|---|--:|--:|--:|
| Rule filter (no rules) | −3 ± 9 ns | −25 ± 11 ns | +67 ± 56 ns |
| Storm detection | +125 ± 32 ns | +374 ± 14 ns | −37 ± 53 ns |
| `trim` rendering seam | −42 ± 34 ns | +9 ± 21 ns | +44 ± 48 ns |
| `top` byte counting | +46 ± 17 ns | +46 ± 23 ns | **+4,115 ± 75 ns** |

Each row is the difference between two adjacent layers of the same run. Rows near zero are
within their own error: those layers cost about nothing.

## Budgets

Checked as the difference **plus its error** against the budget (Decisions #8, #13, #21,
#23). Single thread, one shared handler.

| Check | Message | Measured | Budget | Result |
|---|---|--:|--:|---|
| Rule filter, no rules | template | −3 ± 9 ns | 50 ns | pass |
| | concatenated | −25 ± 11 ns | 50 ns | pass |
| | exception | +67 ± 56 ns | 50 ns | over, on its error bar ¹ |
| Storm filter | template | +125 ± 32 ns | 100 ns | **over** ² |
| | concatenated | +374 ± 14 ns | 308 ns | **over** ([#147](https://github.com/ddeuchert/logaperture/issues/147)) |
| No rule can match (`drop-miss` − `idle`) | template | +23 ± 9 ns | 50 ns | pass |
| | concatenated | +3 ± 19 ns | 50 ns | pass |
| | exception | +28 ± 92 ns | 50 ns | over, on its error bar ¹ |
| 20 rules, a `drop` matches | all three | +67 ns at most | 204 ns | pass ³ |
| 20 rules, a `trim` matches | all three | +890 to +960 ns | 204 ns | **over** ([#148](https://github.com/ddeuchert/logaperture/issues/148)) |
| 20 type-bound `trim`s | all three | +730 to +1,190 ns | 50 / 204 ns | **over** ([#148](https://github.com/ddeuchert/logaperture/issues/148)) |
| Gate alone, 1 / 5 / 20 / 100 rules | template | +582 / +681 / +778 / +1,486 ns | 85 / 140 / 204 / 283 ns | **over** ([#148](https://github.com/ddeuchert/logaperture/issues/148)) |

1. The measured difference is at or under budget; the check fails on the upper end of an
   error bar that the 4 µs exception render widens. Not treated as a regression; the rerun
   will show whether it repeats.
2. One of the three forks ran 886 ns where the other two ran about 790 ns: a JIT outcome that
   differs between JVMs, not a slower code path. On the two agreeing forks the storm filter
   adds about +90 ns, inside the budget. The check counts all three, so it reads over.
3. A denied record is never formatted, so `drop-hit` is mostly cheaper than the idle agent;
   the gate's own cost is the "gate alone" row.

**Storm filter, before and after [#147](https://github.com/ddeuchert/logaperture/issues/147).**
The storm filter now hashes the normalized message in one scan instead of building it, and
builds the text only for a new fingerprint. On this machine, pinned, it went from +148 to
+125 ns on a template and from +715 to +374 ns on a concatenated message (previous run,
2026-10-05, same settings). What's left on the concatenated path is that scan, about 3 ns per
character alone (`storm-hash`, 159 ns for the 52-character message) and more inside a real
call, where the formatter around it evicts its data from cache.

## Several threads

`idle` at 1, 4 and 8 threads, each thread logging through its own logger. With one handler
shared by every thread, JUL's own handler lock dominates and LogAperture adds little: −0.2 %
at 4 threads and −5.7 % at 8 on a template message (within error of zero), +7 % and +23 % on
a concatenated one. With a handler per thread (no shared JUL lock), the agent adds +20 %,
+33 % and +29 % on a template at 1, 4 and 8 threads.

The machine has 4 cores and 8 hardware threads, so the 8-thread runs share cores (SMT); the
#24 rule below compares against the baseline's own scaling for that reason.

## What a match saves

One `INFO` call through a real JBoss `FileHandler` (autoflush on), with 20 rules on the
logger, the last of which matches, against no agent through the same handler. The
exception has 100 frames. Reported, not budgeted (Decision #22).

| Matching rule | Message | Change vs no agent | Per call |
|---|---|--:|--:|
| `drop` | template | **−60 %** | 903 ns vs 2,251 ns |
| `trim` | template | +44 % | 3,235 ns vs 2,251 ns |
| `drop` | exception | **−96 %** | 921 ns vs 23,963 ns |
| `trim` | exception | +17 % | 27,945 ns vs 23,963 ns |

A dropped record skips formatting and the write, so even with today's gate cost it comes out
well below no agent at all. A `trim` still writes the record, and today's gate cost
([#148](https://github.com/ddeuchert/logaperture/issues/148)) outweighs the frames it saves on
this handler.

## Decision rules

Fixed before measuring (Decision #6):

- **#23 goes into 1.0.** `top` renders a record's stack trace a second time to count its
  bytes. With an exception, that layer costs +4,115 ± 75 ns, +100 % of the baseline with an
  exception; the threshold is 25 %.
  ([#23](https://github.com/ddeuchert/logaperture/issues/23), milestone 1.0.0.)
- **#24 stays out of 1.0.** At 8 threads on separate handlers, `idle` takes 1.73 ± 0.04× its
  single-thread time and the baseline 1.61 ± 0.06×; the rule asks for `idle` over 1.5× while
  the baseline is not.
- **#18 moves to 1.x.** It is not on the logging path, so no benchmark can make it matter.

## Component benchmarks

Each hot-path piece alone, with nothing around it, to cross-check the differences above.

| Benchmark | ns per call | Allocated |
|---|--:|--:|
| `gate-empty`: rule gate, no rules | 1.3 | 0 B |
| `gate-n`: rule gate, 1 / 5 / 20 / 100 rules | 583 / 682 / 780 / 1,488 | ~217 B |
| `storm-observe`: storm detector, template seen before | 50 | 24 B |
| `storm-observe`: storm detector, new string per call | 358 | 120 B |
| `storm-hash`: the normalizing scan alone | 159 | 0 B |
| `storm-normalize`: the normalized text, built for a new fingerprint | 305 | 248 B |
| `top-record`: `top`'s per-record count | 18.6 | 0 B |
| `trace-bytes`: `top`'s second stack-trace render (#23) | 4,073 | 16,960 B |

`storm-observe` with a new string includes building that string, which the benchmark does
inside the measured call.

## Flame graphs

async-profiler, CPU, the idle agent on each message (interval-timer sampling, since this
machine's `perf_event_paranoid` is 2):

- [Template](overhead/2026-10-06-jdk21/flamegraphs/idle-template.html)
  ([reversed](overhead/2026-10-06-jdk21/flamegraphs/idle-template-reverse.html))
- [Concatenated](overhead/2026-10-06-jdk21/flamegraphs/idle-concatenated.html)
  ([reversed](overhead/2026-10-06-jdk21/flamegraphs/idle-concatenated-reverse.html)): the
  storm filter's scan (`StormMessageNormalizer.hashScan`) is its largest LogAperture frame.
- [Exception](overhead/2026-10-06-jdk21/flamegraphs/idle-throwable.html)
  ([reversed](overhead/2026-10-06-jdk21/flamegraphs/idle-throwable-reverse.html)): the
  second `printStackTrace` under `ByteCountingFormatter` is #23.

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
| OS | Fedora Linux 44, kernel 7.2.8, clock source `tsc` (`System.nanoTime()` 24.7 ns) |
| JDK | Temurin 21.0.12 |
| JMH | 1.37; 3 forks, 5 × 1 s warmup, 10 × 1 s measurement; `-prof gc` |
| Code | `feature/128-overhead-benchmarks` at `5ba0d6b` (`develop` at `98380b1` plus the benchmarks) |
| Date | 2026-10-06 |

Raw results: [`doc/overhead/2026-10-06-jdk21/`](overhead/2026-10-06-jdk21/), the JMH JSON
for each run, `machine.txt`, and the generated [`report.md`](overhead/2026-10-06-jdk21/report.md)
with every row.

To run the same thing on your own machine (Linux, macOS or Windows), see
[`logaperture-bench/README.md`](../logaperture-bench/README.md):

```
logaperture-bench/run-bench.sh --quick     # about 10 minutes, indicative only
logaperture-bench/run-bench.sh             # the full run, about an hour
```
