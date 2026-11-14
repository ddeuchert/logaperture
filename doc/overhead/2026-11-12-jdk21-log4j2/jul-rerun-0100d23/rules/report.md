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
| `drop-miss` | template | 1 | shared | — | — | 756 ± 6.5 | — | 1296 |
| `drop-hit` | template | 1 | shared | — | — | 1116 ± 130 | — | 649 |
| `trim` | template | 1 | shared | — | — | 978 ± 10.2 | — | 1709 |
| `trim-typed` | template | 1 | shared | — | — | 771 ± 15.3 | — | 1296 |
| `drop-miss` | concatenated | 1 | shared | — | — | 795 ± 3.9 | — | 1419 |
| `drop-hit` | concatenated | 1 | shared | — | — | 1225 ± 153 | — | 761 |
| `trim` | concatenated | 1 | shared | — | — | 1006 ± 20.5 | — | 1803 |
| `trim-typed` | concatenated | 1 | shared | — | — | 807 ± 20.7 | — | 1411 |
| `drop-miss` | throwable | 1 | shared | — | — | 4474 ± 33.4 | — | 18664 |
| `drop-hit` | throwable | 1 | shared | — | — | 1105 ± 136 | — | 633 |
| `trim` | throwable | 1 | shared | — | — | 3431 ± 325 | — | 8866 |
| `trim-typed` | throwable | 1 | shared | — | — | 3368 ± 314 | — | 8847 |

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

## Machine

```
label:            jul-rules
quick:            false
date:             2026-11-13T17:29:02-06:00
computer:         fedora
cpu:              AMD Ryzen 5 3400G with Radeon Vega Graphics
cores/threads:    4 / 8
memory (GB):      60.8
os:               Fedora Linux 44 (Workstation Edition), kernel 7.3.3-200.fc44.x86_64
clocksource:      tsc
governor:         performance
boost:            0
git commit:       0100d2337fc6ccc5fa532873c3fb119a59cbf912
git branch:       feature/371-log4j2-trim-typed-cost
nanoTime (ns):    25.0
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
