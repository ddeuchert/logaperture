# Overhead

What LogAperture adds to a log call, measured with [JMH](https://github.com/openjdk/jmh): with
nothing configured, with rules, and with storm detection turned on. The numbers below are for
`java.util.logging` and JBoss LogManager (WildFly, Quarkus); [Log4j 2](#on-log4j-2) is covered
separately.

!!! info "Measured on 2026-10-09, before 1.0"
    These numbers are from the 1.0 code before beta 1, on one machine ([the run](#the-run)).
    Everything an idle agent or a logger with message rules does is within its budget. Three
    checks are over, each with an open issue: see [Over budget](#over-budget). The page is
    replaced by a new run when any of those are fixed.

## At a glance

One `INFO` log call on one thread. **Overhead** is how much longer the call takes with
LogAperture than without it, measured in the same run.

| What's installed | Template message | Concatenated message | With a 20-frame exception |
|---|--:|--:|--:|
| Idle agent: nothing configured | **+13 %** (+93 ns) | **+9 %** (+67 ns) | **+13 %** (+517 ns) |
| 20 `drop` rules on other loggers | +8 % (+59 ns) | +9 % (+67 ns) | +12 % (+486 ns) |
| 20 rules on this logger, a `trim` matches | +31 % (+218 ns) | +35 % (+250 ns) | **−15 %** (−604 ns) |
| 20 rules on this logger, a `drop` matches | +56 % (+389 ns) | +61 % (+443 ns) | **−73 %** (−2,937 ns) |
| Idle agent, storm detection turned on | +18 % (+128 ns) | +57 % (+413 ns) | +13 % (+528 ns) |

How to read it:

- **Only calls that pass the logger's level check pay anything.** A disabled `DEBUG` call never
  reaches LogAperture. (That holds on `java.util.logging`. On Log4j 2 a disabled call does pass
  through LogAperture's gate; see [On Log4j 2](#on-log4j-2).)
- **Template or concatenated.** A template is the same `String` on every call: what JBoss
  Logging, message loggers and `{0}`/`%s` parameterized logging pass. Most WildFly logging is
  in the first column. A concatenated message (`"order " + id + " failed"`) is a new string on
  every call. It costs about the same as a template, unless storm detection is on, which scans
  every message.
- **A negative number** means the call took less time than with no agent: the matching rule
  dropped the record, or cut its stack trace, before it was written.
- **The baseline is about the cheapest real log call there is:** JBoss LogManager, a short
  pattern formatter, output thrown away. About 0.7 µs, or 4 µs with the exception. Against a
  real file handler the same nanoseconds are a smaller share; see
  [What a match saves](#what-a-match-saves).
- **The nanoseconds describe this machine only.** It has a 2019 CPU, run without boost, so a
  newer one reports fewer. The percentages carry across hardware better.
- **Run to run, the idle agent moves by a few points.** The same code measured +8.5 % on a
  template on 2026-10-07, and +13 % here: between JVMs, the JIT doesn't always compile the
  same way.
- Storm detection is off unless you turn it on (`--storm-detection=on`, or
  `logctl enable storms`). See [Log storms](how-to/storms.md).

## What a match saves

One `INFO` call through a real JBoss `FileHandler` (autoflush on), with 20 rules on the logger,
the last of which matches, against no agent through the same handler. The exception has 100
frames.

| Matching rule | Message | Change vs no agent | Per call |
|---|---|--:|--:|
| `drop` | template | **−52 %** | 1,079 ns vs 2,235 ns |
| `trim` | template | +14 % | 2,551 ns vs 2,235 ns |
| `drop` | exception | **−96 %** | 1,099 ns vs 24,515 ns |
| `trim` | exception | **−77 %** | 5,763 ns vs 24,515 ns |

A dropped record skips formatting and the write. A trimmed one is written with 5 frames
instead of 100, which on a real handler saves far more than the rules cost. On a template
message a `trim` has no stack trace to cut, so it only costs its rules.

## Several threads

The idle agent at 1, 4 and 8 threads, each thread logging through its own logger:

- **Through one shared handler,** the handler's own lock dominates and LogAperture adds nothing
  measurable: −3 % at 4 threads and −11 % at 8 on a template message, +2 % and 0 % on a
  concatenated one. The negative numbers are the lock's scheduling, not a speed-up.
- **Through a handler per thread,** with no shared lock, the agent adds +6.5 %, +16 % and
  +25 % on a template at 1, 4 and 8 threads.

The machine has 4 cores and 8 hardware threads, so the 8-thread runs share cores.

## On Log4j 2

!!! info "Log4j 2 numbers come with 1.1"
    Log4j 2 support is new in 1.1. Its numbers are measured with the same benchmark suite and
    will be published here, from a quiet machine with the CPU pinned, before 1.1.0 ships. Until
    then, [measure it yourself](#measure-it-yourself). What follows is what is different on
    Log4j 2, and what the benchmarks measure because of it.

- **A disabled call still passes through the gate.** Log4j 2 runs a filter ahead of its level
  check, so `logger.debug(...)` on a logger whose level rejects it reaches LogAperture. The gate
  leaves at once when the call would not be written, and allocates nothing, but it is not free the
  way a disabled call is on `java.util.logging`. It has a budget of 10 ns, and the report checks it
  in every Log4j mode.
- **A rule that reads the message formats it first.** For a parameterized message (`{}`), the
  gate formats the message once so a `--message-contains` rule can read it, and Log4j formats it
  again when it writes the line. That formatting is the larger part of what a message rule costs,
  so the report shows it as its own row, apart from the rules' own cost. A `drop` rule that
  matches on the logger, the level or the unformatted pattern never does this. A message built by
  concatenation is already a plain string and has nothing to format. See
  [the limits on Log4j 2](get-started/install-plain-jvm.md#limits-to-know).
- **There is no `top` layer.** `logctl top` does not count Log4j 2 output yet, so an idle agent
  there does not pay for byte counting.
- **Log4j's three modes are measured separately.** With synchronous loggers, with an `<Async>`
  appender and with all-async loggers, the gate runs on the calling thread. A `trim` rule's work
  runs on the calling thread too, except with all-async loggers, where it moves to Log4j's own
  consumer thread and is off the call. The two asynchronous modes are reported but not held to a
  budget: a tight loop into a queue measures the queue.
- **Two Log4j versions are measured.** The newest tested 2.25 for every mode, and 2.20.0, the
  oldest LogAperture supports, for the idle agent and the disabled call. Log4j changed how it
  copies and renders events between those versions, so a cost on one is not a promise about the
  other.

## What these numbers don't say

JMH measures a hot loop in steady state: code and data in cache, and the JIT specialized on one
call site. A real application's log path is colder, and its lock contention depends on its
threading. Read these numbers as a lower bound for the fast path.

## Over budget

Each part of the log path has a budget, fixed before measuring. Three are over:

- **A record a `drop` rule matches:** +296 ns on a template against a 204 ns budget
  ([#158](https://github.com/ddeuchert/logaperture/issues/158)). The record still goes through
  the cache that makes one event count once across handlers. A dropped record still costs far
  less than writing it.
- **Type-bound `trim` rules on a record with an exception:** +538 ns for 20 rules against
  204 ns ([#177](https://github.com/ddeuchert/logaperture/issues/177)). Each rule walks the
  exception's class hierarchy.
- **Storm detection, when it is turned on:** +364 ns on a concatenated message against 308 ns
  ([#150](https://github.com/ddeuchert/logaperture/issues/150)). It is off by default.

The first two are accepted for 1.0 as measured and scheduled for a 1.x release; the third is
planned for 1.1.0.

## Details

### Where the idle agent's time goes

| Layer | Template | Concatenated | Exception |
|---|--:|--:|--:|
| Rule filter, no rules | −2 ± 16 ns | +13 ± 18 ns | +42 ± 37 ns |
| Storm filter, installed but off | +8 ± 11 ns | +11 ± 21 ns | +42 ± 40 ns |
| `trim` support and `top`'s byte counting | +87 ± 12 ns | +43 ± 18 ns | +433 ± 89 ns |

Each row is the difference between two adjacent layers of the same run; rows near zero are within
their own error. With an exception, most of `top`'s share is encoding the whole record, trace
included, to count its bytes.

With storm detection turned on:

| Layer (storm detection on) | Template | Concatenated | Exception |
|---|--:|--:|--:|
| Storm detection | +93 ± 14 ns | +364 ± 20 ns | +79 ± 46 ns |
| `trim` support | −12 ± 11 ns | +11 ± 22 ns | +51 ± 57 ns |
| `top`'s byte counting | +49 ± 12 ns | +25 ± 21 ns | +356 ± 70 ns |

### Budgets

Each check is the measured difference **plus its error** against the budget. Single thread, one
shared handler. Errors are JMH's 99.9 % confidence intervals.

| Check | Message | Measured | Budget | Result |
|---|---|--:|--:|---|
| Rule filter, no rules | template | −2 ± 16 ns | 50 ns | pass |
| | concatenated | +13 ± 18 ns | 50 ns | pass |
| | exception | +42 ± 37 ns | 50 ns | over, on its error bar ¹ |
| Storm filter, installed but off | any | +1.2 ns | 10 ns | pass |
| Rules on other loggers, none can match | template | −35 ± 12 ns | 50 ns | pass |
| | concatenated | 0 ± 13 ns | 50 ns | pass |
| | exception | −31 ± 91 ns | 50 ns | over, on its error bar ¹ |
| 20 rules, a `trim` matches | template | +125 ± 12 ns | 204 ns | pass |
| | concatenated | +183 ± 17 ns | 204 ns | pass |
| | exception | −1,120 ± 352 ns | 204 ns | pass |
| 20 type-bound `trim` rules | template | +9 ± 21 ns | 50 ns | pass |
| | concatenated | +13 ± 9 ns | 50 ns | pass |
| | exception | +538 ± 376 ns | 204 ns | **over** ([#177](https://github.com/ddeuchert/logaperture/issues/177)) |
| 20 rules, a `drop` matches | template | +296 ± 139 ns | 204 ns | **over** ([#158](https://github.com/ddeuchert/logaperture/issues/158)) |
| | concatenated | +375 ± 133 ns | 204 ns | **over** ([#158](https://github.com/ddeuchert/logaperture/issues/158)) |
| | exception | −3,454 ± 158 ns | 204 ns | pass |
| Rule matching alone, 1 / 5 / 20 / 100 rules | template | +33 / +79 / +171 / +199 ns | 85 / 140 / 204 / 283 ns | pass |
| Storm detection, turned on | template | +93 ± 14 ns | 100 ns | over, on its error bar ² |
| | concatenated | +364 ± 20 ns | 308 ns | **over** ([#150](https://github.com/ddeuchert/logaperture/issues/150)) ² |

1. The measured difference is under budget; the check fails on the upper end of an error bar
   that the 4 µs exception render widens. In each case one of the three JVMs ran about 100 ns
   slower than the other two: a JIT outcome, not a slower code path.
2. Storm detection is off unless you turn it on, so these don't apply to the agent as shipped.

### Since the first run

The first published run, on 2026-10-06, had storm detection on by default. Same machine and
settings:

| | 2026-10-06 | 2026-10-09 |
|---|--:|--:|
| Idle agent, template / concatenated | +18 % / +54 % | +13 % / +9 % (storm detection off by default) |
| Idle agent, with an exception | +102 % | +13 % (`top` no longer renders the stack trace a second time) |
| Rule matching alone, 1 / 5 / 20 / 100 rules | 582 / 681 / 778 / 1,486 ns | 33 / 79 / 171 / 199 ns |
| 20 rules, a `trim` matches, template / exception | +1,083 / +5,081 ns | +218 / −604 ns |

## The run

| | |
|---|---|
| CPU | AMD Ryzen 5 3400G, 4 cores / 8 threads, `performance` governor, boost off |
| OS | Fedora Linux 44, kernel 7.2.8, clock source `tsc` |
| JDK | Temurin 21.0.12 |
| JMH | 1.37; 3 forks, 5 × 1 s warmup, 10 × 1 s measurement |
| Code | the 1.0 code before beta 1, at commit `39a80d4` |
| Date | 2026-10-09 |

The raw results are in the repository:
[`doc/overhead/2026-10-09-jdk21/`](https://github.com/ddeuchert/logaperture/tree/develop/doc/overhead/2026-10-09-jdk21),
with the JMH JSON for each benchmark, the machine description, async-profiler flame graphs and a
[report](https://github.com/ddeuchert/logaperture/blob/develop/doc/overhead/2026-10-09-jdk21/report.md)
with every row, including the per-component benchmarks.

## Measure it yourself

The benchmarks are in the source tree, and run on Linux, macOS or Windows. From a checkout:

```sh
logaperture-bench/run-bench.sh --quick     # about 10 minutes, indicative only
logaperture-bench/run-bench.sh             # the full run, about an hour
```

For Log4j 2, the run uses Log4j 2.25.5 unless you pass `--log4j2-version`, and the report says which
version it measured. The oldest supported version is a separate run, because the jar holds one
version:

```sh
logaperture-bench/run-bench.sh --log4j2-version 2.20.0 --only log4j2-floor --label floor
```

On Windows, `powershell -ExecutionPolicy Bypass -File logaperture-bench\run-bench.ps1 -Quick`. See
[`logaperture-bench/README.md`](https://github.com/ddeuchert/logaperture/blob/develop/logaperture-bench/README.md)
for what each scenario measures and how to read the report. For numbers you can compare with
this page, run on a quiet machine with frequency boost off.
