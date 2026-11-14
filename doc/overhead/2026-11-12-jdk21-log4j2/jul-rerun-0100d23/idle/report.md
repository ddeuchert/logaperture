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
| `rule` | template | 1 | shared | +1.0 % ± 1.3 % | +6.8 ± 9.2 | 703 ± 8.5 | 696 ± 3.7 | 1205 |
| `rule+storm` | template | 1 | shared | +13.7 % ± 1.9 % | +95.2 ± 13.2 | 791 ± 12.6 | 696 ± 3.7 | 1211 |
| `rule+storm-off` | template | 1 | shared | +2.3 % ± 2.0 % | +16.1 ± 13.7 | 712 ± 13.2 | 696 ± 3.7 | 1205 |
| `rule+storm+trim` | template | 1 | shared | +15.4 % ± 0.9 % | +107 ± 6.3 | 803 ± 5.1 | 696 ± 3.7 | 1248 |
| `idle+storm` | template | 1 | shared | +17.0 % ± 0.8 % | +119 ± 5.8 | 815 ± 4.5 | 696 ± 3.7 | 1331 |
| `idle` | template | 1 | shared | +8.0 % ± 1.2 % | +55.6 ± 8.0 | 752 ± 7.1 | 696 ± 3.7 | 1307 |
| `rule` | concatenated | 1 | shared | -1.5 % ± 2.3 % | -10.9 ± 16.9 | 735 ± 3.6 | 746 ± 16.5 | 1301 |
| `rule+storm` | concatenated | 1 | shared | +49.5 % ± 3.2 % | +369 ± 22.3 | 1115 ± 14.9 | 746 ± 16.5 | 1344 |
| `rule+storm-off` | concatenated | 1 | shared | -1.0 % ± 2.6 % | -7.1 ± 19.6 | 739 ± 10.6 | 746 ± 16.5 | 1301 |
| `rule+storm+trim` | concatenated | 1 | shared | +47.2 % ± 2.9 % | +352 ± 20.0 | 1098 ± 11.3 | 746 ± 16.5 | 1288 |
| `idle+storm` | concatenated | 1 | shared | +54.3 % ± 3.3 % | +405 ± 23.0 | 1151 ± 16.0 | 746 ± 16.5 | 1435 |
| `idle` | concatenated | 1 | shared | +6.1 % ± 2.5 % | +45.7 ± 18.7 | 791 ± 8.8 | 746 ± 16.5 | 1403 |
| `rule` | throwable | 1 | shared | -1.3 % ± 0.8 % | -52.0 ± 31.6 | 4086 ± 18.0 | 4138 ± 26.0 | 16888 |
| `rule+storm` | throwable | 1 | shared | +1.2 % ± 0.8 % | +47.6 ± 34.9 | 4185 ± 23.2 | 4138 ± 26.0 | 16941 |
| `rule+storm-off` | throwable | 1 | shared | -0.6 % ± 1.0 % | -22.8 ± 39.9 | 4115 ± 30.2 | 4138 ± 26.0 | 16925 |
| `rule+storm+trim` | throwable | 1 | shared | +1.9 % ± 0.8 % | +77.3 ± 33.4 | 4215 ± 21.0 | 4138 ± 26.0 | 16923 |
| `idle+storm` | throwable | 1 | shared | +9.7 % ± 1.1 % | +401 ± 45.1 | 4539 ± 36.8 | 4138 ± 26.0 | 18349 |
| `idle` | throwable | 1 | shared | +9.4 % ± 0.8 % | +390 ± 33.2 | 4528 ± 20.6 | 4138 ± 26.0 | 18664 |

## Budgets

Single thread, one shared handler. A check passes when the difference *plus* its error is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the logger.

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| rule filter: `rule` − `baseline` | template | +6.8 ± 9.2 | 50.0 | pass |
| rule filter: `rule` − `baseline` | concatenated | -10.9 ± 16.9 | 50.0 | pass |
| rule filter: `rule` − `baseline` | throwable | -52.0 ± 31.6 | 50.0 | pass |
| storm filter: `rule+storm` − `rule` | template | +88.4 ± 15.2 | 100 | **over** |
| storm filter: `rule+storm` − `rule` | concatenated | +380 ± 15.4 | 308 | **over** |
| storm filter, disabled: `storm-off` − `storm-off-delegate` (component) | any | not run | 10.0 | — |
| storm filter, disabled: `rule+storm-off` − `rule` | template | +9.4 ± 15.7 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | concatenated | +3.7 ± 11.2 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | throwable | +29.2 ± 35.2 | — | reported |
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

- **#23** (`top` renders a throwable's stack trace a second time): the `top` layer costs +324 ± 42.4 ns, +7.8 % ± 1.0 % of the baseline with a throwable; the threshold is 25 %. Stays out of 1.0.
- **#24** (`top`'s one lock across independent handlers): not run (needs `baseline` and `idle`, template, per-thread handlers, at 1 thread and at least one more).
- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.

## Machine

```
label:            jul-idle
quick:            false
date:             2026-11-13T17:38:33-06:00
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
nanoTime (ns):    24.9
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
