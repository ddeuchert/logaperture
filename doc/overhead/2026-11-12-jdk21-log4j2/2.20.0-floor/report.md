# LogAperture overhead report

**Overhead** is how much longer one `INFO` log call takes with LogAperture than without
it (`baseline`), on this machine, in the same run: (scenario − baseline) ÷ baseline.
The baseline is about the cheapest real log call there is (JBoss LogManager, a short
pattern, output thrown away), so against a real file handler the same nanoseconds are
a smaller percentage. The nanosecond columns describe this machine only. Errors are
JMH's 99.9 % confidence intervals, combined for differences and ratios.

## Overhead per log call

| Scenario | Message | Threads | Handlers | Overhead | Added (ns) | Score (ns/op) | Baseline (ns/op) | Allocated (B/op) |
|---|---|--:|---|--:|--:|--:|--:|--:|
| (no scenario results) |||||||||

## Budgets

Single thread, one shared handler. A check passes when the difference *plus* its error is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the logger.

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| rule filter: `rule` − `baseline` | template | not run | 50.0 | — |
| rule filter: `rule` − `baseline` | concatenated | not run | 50.0 | — |
| rule filter: `rule` − `baseline` | throwable | not run | 50.0 | — |
| storm filter: `rule+storm` − `rule` | template | not run | 100 | — |
| storm filter: `rule+storm` − `rule` | concatenated | not run | 308 | — |
| storm filter, disabled: `storm-off` − `storm-off-delegate` (component) | any | not run | 10.0 | — |
| storm filter, disabled: `rule+storm-off` − `rule` | template | not run | — | — |
| storm filter, disabled: `rule+storm-off` − `rule` | concatenated | not run | — | — |
| storm filter, disabled: `rule+storm-off` − `rule` | throwable | not run | — | — |
| no rule can match: `drop-miss` − `idle` | template | not run | 50.0 | — |
| no rule can match: `drop-miss` − `idle` | concatenated | not run | 50.0 | — |
| no rule can match: `drop-miss` − `idle` | throwable | not run | 50.0 | — |
| 20 rules: `drop-hit` − `idle` | template | not run | 204 | — |
| 20 rules: `drop-hit` − `idle` | concatenated | not run | 204 | — |
| 20 rules: `drop-hit` − `idle` | throwable | not run | 204 | — |
| 20 rules: `trim` − `idle` | template | not run | 204 | — |
| 20 rules: `trim` − `idle` | concatenated | not run | 204 | — |
| 20 rules: `trim` − `idle` | throwable | not run | 204 | — |
| 20 type-bound trims: `trim-typed` − `idle` | template | not run | 50.0 | — |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | not run | 50.0 | — |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | not run | 204 | — |

## Decision rules

- **#23** (`top` renders a throwable's stack trace a second time): not run (needs `baseline`, `rule+storm+trim` and `idle+storm` with the throwable message).
- **#24** (`top`'s one lock across independent handlers): not run (needs `baseline` and `idle`, template, per-thread handlers, at 1 thread and at least one more).
- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.

## Log4j 2

**Log4j 2 version: 2.20.0**. The published run uses the latest tested 2.25 for every mode, and 2.20.0, the adapter's floor, for `idle` and `disabled` in a separate results folder (Decision #26); a trim regression of #333's kind shows on 2.25 and not on 2.20, so a number is only as good as the version printed here.

The Log4j 2 adapter's gate on a real `LoggerContext`, one `INFO` call, in each of Log4j's three modes. Overhead is against `baseline` (bare Log4j 2) of the same mode, message, thread count and appender shape. There is no `top` layer: byte counting is not built for Log4j 2. `template` is a parameterized message (`{}` with three arguments), so a rule that reads it formats it first; `concatenated` is a plain `String`; `throwable` is `template` plus a 20-frame exception. Budgets are checked in synchronous mode only; the two asynchronous modes are reported, because a hot loop into a queue measures the queue (Decision #27). A rule that reads a parameterized message pays for formatting it, which the 1.0 curve never included, so those rows are reported and the budgeted rule rows use the plain message (Decision #32).

### Overhead per log call (Log4j 2)

| Mode | Scenario | Message | Threads | Appenders | Overhead | Added (ns) | Score (ns/op) | Baseline (ns/op) | Allocated (B/op) |
|---|---|---|--:|---|--:|--:|--:|--:|--:|
| sync | `idle` | template | 1 | shared | +9.9 % ± 1.2 % | +53.7 ± 6.5 | 595 ± 4.2 | 542 ± 5.0 | 0 |
| sync | `idle` | throwable | 1 | shared | +1.4 % ± 1.5 % | +260 ± 273 | 18477 ± 121 | 18217 ± 245 | 11632 |
| async-appender | `idle` | template | 1 | shared | +8.6 % ± 4.0 % | +52.5 ± 24.5 | 666 ± 20.8 | 613 ± 12.9 | 341 |
| async-appender | `idle` | throwable | 1 | shared | +2.7 % ± 2.9 % | +368 ± 404 | 14221 ± 293 | 13853 ± 278 | 11979 |
| async-loggers | `idle` | template | 1 | shared | +5.1 % ± 1.2 % | +23.0 ± 5.6 | 473 ± 3.8 | 450 ± 4.1 | 0 |
| async-loggers | `idle` | throwable | 1 | shared | -0.2 % ± 2.9 % | -38.6 ± 512 | 17575 ± 285 | 17613 ± 426 | 11618 |

### Log4j 2 budgets

Synchronous mode, single thread, one shared appender. A check passes when the difference *plus* its error is within the budget (Decisions #8, #13, #21, #23, #28, #32). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1). Rows marked *parameterized* include the cost of formatting the message for the rules and are reported, not budgeted (Decision #32).

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| gate: `gate` − `baseline` | template | not run | 50.0 | — |
| gate: `gate` − `baseline` | concatenated | not run | 50.0 | — |
| gate: `gate` − `baseline` | throwable | not run | 50.0 | — |
| storm filter: `gate+storm` − `gate` | template | not run | 100 | — |
| storm filter: `gate+storm` − `gate` | concatenated | not run | 308 | — |
| storm filter, disabled: `gate+storm-off` − `gate` | template | not run | — | — |
| storm filter, disabled: `gate+storm-off` − `gate` | concatenated | not run | — | — |
| storm filter, disabled: `gate+storm-off` − `gate` | throwable | not run | — | — |
| idle (shipped default): `idle` − `baseline` | template | +53.7 ± 6.5 | 50.0 | **over** |
| idle (shipped default): `idle` − `baseline` | concatenated | not run | 50.0 | — |
| idle (shipped default): `idle` − `baseline` | throwable | +260 ± 273 | 50.0 | **over** |
| no rule can match: `drop-miss` − `idle` | concatenated | not run | 50.0 | — |
| no rule can match: `drop-miss` − `idle` *(parameterized)* | template | not run | — | — |
| 20 rules: `drop-hit` − `idle` *(parameterized)* | template | not run | — | — |
| 20 rules: `drop-hit` − `idle` | concatenated | not run | 204 | — |
| 20 rules: `drop-hit` − `idle` *(parameterized)* | throwable | not run | — | — |
| 20 rules: `trim` − `idle` *(parameterized)* | template | not run | — | — |
| 20 rules: `trim` − `idle` | concatenated | not run | 204 | — |
| 20 rules: `trim` − `idle` *(parameterized)* | throwable | not run | — | — |
| 20 type-bound trims: `trim-typed` − `idle` | template | not run | 50.0 | — |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | not run | 50.0 | — |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | not run | 204 | — |
| disabled call, sync: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |
| disabled call, async-appender: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |
| disabled call, async-loggers: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |

## Machine

```
label:            floor
quick:            false
date:             2026-11-12T21:23:29-06:00
computer:         fedora
cpu:              AMD Ryzen 5 3400G with Radeon Vega Graphics
cores/threads:    4 / 8
memory (GB):      60.8
os:               Fedora Linux 44 (Workstation Edition), kernel 7.3.3-200.fc44.x86_64
clocksource:      tsc
governor:         performance
boost:            0
git commit:       13de3acbd4b97655583f43774d7f8d3fd301e5cb
git branch:       feature/371-log4j2-trim-typed-cost
nanoTime (ns):    24.6
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
