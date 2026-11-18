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

**Log4j 2 version: 2.25.5**. The published run uses the latest tested 2.25 for every mode, and 2.20.0, the adapter's floor, for `idle` and `disabled` in a separate results folder (Decision #26); a trim regression of #333's kind shows on 2.25 and not on 2.20, so a number is only as good as the version printed here.

The Log4j 2 adapter's gate on a real `LoggerContext`, one `INFO` call, in each of Log4j's three modes. Overhead is against `baseline` (bare Log4j 2) of the same mode, message, thread count and appender shape. There is no `top` layer: byte counting is not built for Log4j 2. `template` is a parameterized message (`{}` with three arguments), so a rule that reads it formats it first; `concatenated` is a plain `String`; `throwable` is `template` plus a 20-frame exception. Budgets are checked in synchronous mode only; the two asynchronous modes are reported, because a hot loop into a queue measures the queue (Decision #27). A rule that reads a parameterized message pays for formatting it, which the 1.0 curve never included, so those rows are reported and the budgeted rule rows use the plain message (Decision #32).

### Overhead per log call (Log4j 2)

| Mode | Scenario | Message | Threads | Appenders | Overhead | Added (ns) | Score (ns/op) | Baseline (ns/op) | Allocated (B/op) |
|---|---|---|--:|---|--:|--:|--:|--:|--:|
| sync | `drop-miss` | template | 1 | shared | — | — | 580 ± 6.9 | — | 24 |
| sync | `drop-hit` | template | 1 | shared | — | — | 766 ± 2.9 | — | 904 |
| sync | `trim` | template | 1 | shared | — | — | 1039 ± 13.1 | — | 880 |
| sync | `trim-typed` | template | 1 | shared | — | — | 579 ± 5.7 | — | 24 |
| sync | `drop-miss` | concatenated | 1 | shared | — | — | 472 ± 8.0 | — | 120 |
| sync | `drop-hit` | concatenated | 1 | shared | — | — | 578 ± 3.9 | — | 608 |
| sync | `trim` | concatenated | 1 | shared | — | — | 647 ± 5.7 | — | 568 |
| sync | `trim-typed` | concatenated | 1 | shared | — | — | 441 ± 13.9 | — | 104 |
| sync | `drop-miss` | throwable | 1 | shared | — | — | 5909 ± 93.5 | — | 16320 |
| sync | `drop-hit` | throwable | 1 | shared | — | — | 866 ± 3.6 | — | 952 |
| sync | `trim` | throwable | 1 | shared | — | — | 7130 ± 81.7 | — | 6048 |
| sync | `trim-typed` | throwable | 1 | shared | — | — | 6750 ± 80.6 | — | 5536 |
| async-appender | `drop-miss` | template | 1 | shared | — | — | 759 ± 18.0 | — | 302 |
| async-appender | `drop-hit` | template | 1 | shared | — | — | 768 ± 5.3 | — | 904 |
| async-appender | `trim` | template | 1 | shared | — | — | 1547 ± 60.2 | — | 1136 |
| async-appender | `trim-typed` | template | 1 | shared | — | — | 750 ± 16.5 | — | 299 |
| async-appender | `drop-miss` | concatenated | 1 | shared | — | — | 495 ± 19.7 | — | 401 |
| async-appender | `drop-hit` | concatenated | 1 | shared | — | — | 589 ± 11.3 | — | 608 |
| async-appender | `trim` | concatenated | 1 | shared | — | — | 791 ± 19.9 | — | 838 |
| async-appender | `trim-typed` | concatenated | 1 | shared | — | — | 432 ± 51.9 | — | 358 |
| async-appender | `drop-miss` | throwable | 1 | shared | — | — | 7403 ± 93.8 | — | 16851 |
| async-appender | `drop-hit` | throwable | 1 | shared | — | — | 861 ± 4.6 | — | 952 |
| async-appender | `trim` | throwable | 1 | shared | — | — | 7783 ± 72.2 | — | 6229 |
| async-appender | `trim-typed` | throwable | 1 | shared | — | — | 7556 ± 62.7 | — | 5676 |
| async-loggers | `drop-miss` | template | 1 | shared | — | — | 538 ± 5.9 | — | 0 |
| async-loggers | `drop-hit` | template | 1 | shared | — | — | 776 ± 4.4 | — | 904 |
| async-loggers | `trim` | template | 1 | shared | — | — | 1304 ± 35.4 | — | 864 |
| async-loggers | `trim-typed` | template | 1 | shared | — | — | 540 ± 5.6 | — | 0 |
| async-loggers | `drop-miss` | concatenated | 1 | shared | — | — | 313 ± 6.6 | — | 96 |
| async-loggers | `drop-hit` | concatenated | 1 | shared | — | — | 585 ± 4.1 | — | 608 |
| async-loggers | `trim` | concatenated | 1 | shared | — | — | 651 ± 19.9 | — | 568 |
| async-loggers | `trim-typed` | concatenated | 1 | shared | — | — | 309 ± 4.6 | — | 96 |
| async-loggers | `drop-miss` | throwable | 1 | shared | — | — | 5926 ± 115 | — | 15758 |
| async-loggers | `drop-hit` | throwable | 1 | shared | — | — | 866 ± 8.3 | — | 952 |
| async-loggers | `trim` | throwable | 1 | shared | — | — | 4581 ± 42.8 | — | 5949 |
| async-loggers | `trim-typed` | throwable | 1 | shared | — | — | 4720 ± 65.0 | — | 5582 |

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
| idle (shipped default): `idle` − `baseline` | template | not run | 50.0 | — |
| idle (shipped default): `idle` − `baseline` | concatenated | not run | 50.0 | — |
| idle (shipped default): `idle` − `baseline` | throwable | not run | 50.0 | — |
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
| disabled call, sync: `idle` − `baseline` | template | not run | 10.0 | — |
| disabled call, async-appender: `idle` − `baseline` | template | not run | 10.0 | — |
| disabled call, async-loggers: `idle` − `baseline` | template | not run | 10.0 | — |

## Machine

```
label:            m10-rules
quick:            false
date:             2026-11-16T22:49:54-06:00
computer:         fedora
cpu:              AMD Ryzen 5 3400G with Radeon Vega Graphics
cores/threads:    4 / 8
memory (GB):      60.8
os:               Fedora Linux 45 (Workstation Edition), kernel 7.3.3-300.fc45.x86_64
clocksource:      tsc
governor:         performance
boost:            0
git commit:       d6f0d3585bc26476cea6c9519f20b41c2e9c3e7f
git branch:       develop
nanoTime (ns):    24.6
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
