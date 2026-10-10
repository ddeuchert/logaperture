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
| `rule` | template | 1 | shared | -0.4 % ± 1.3 % | -2.9 ± 9.2 | 698 ± 7.4 | 701 ± 5.4 | 1187 |
| `rule+storm` | template | 1 | shared | +17.3 % ± 4.5 % | +122 ± 31.2 | 823 ± 30.8 | 701 ± 5.4 | 1248 |
| `rule+storm+trim` | template | 1 | shared | +11.4 % ± 2.3 % | +79.8 ± 16.0 | 781 ± 15.1 | 701 ± 5.4 | 1200 |
| `idle` | template | 1 | shared | +17.9 % ± 1.2 % | +126 ± 8.3 | 827 ± 6.4 | 701 ± 5.4 | 1349 |
| `drop-miss` | template | 1 | shared | +21.2 % ± 1.2 % | +148 ± 8.6 | 850 ± 6.8 | 701 ± 5.4 | 1349 |
| `drop-hit` | template | 1 | shared | +27.4 % ± 8.1 % | +192 ± 56.7 | 894 ± 56.4 | 701 ± 5.4 | 371 |
| `trim` | template | 1 | shared | +154.4 % ± 31.0 % | +1083 ± 217 | 1784 ± 217 | 701 ± 5.4 | 1525 |
| `trim-typed` | template | 1 | shared | +128.6 % ± 37.2 % | +901 ± 260 | 1603 ± 260 | 701 ± 5.4 | 1499 |
| `rule` | concatenated | 1 | shared | -3.4 % ± 1.4 % | -25.4 ± 10.8 | 719 ± 4.6 | 744 ± 9.7 | 1264 |
| `rule+storm` | concatenated | 1 | shared | +46.8 % ± 2.2 % | +348 ± 16.0 | 1093 ± 12.7 | 744 ± 9.7 | 1307 |
| `rule+storm+trim` | concatenated | 1 | shared | +48.1 % ± 2.7 % | +358 ± 19.5 | 1102 ± 16.9 | 744 ± 9.7 | 1325 |
| `idle` | concatenated | 1 | shared | +54.3 % ± 2.5 % | +404 ± 18.1 | 1148 ± 15.3 | 744 ± 9.7 | 1445 |
| `drop-miss` | concatenated | 1 | shared | +54.7 % ± 2.2 % | +407 ± 15.5 | 1151 ± 12.0 | 744 ± 9.7 | 1445 |
| `drop-hit` | concatenated | 1 | shared | +19.7 % ± 11.5 % | +146 ± 85.5 | 891 ± 85.0 | 744 ± 9.7 | 465 |
| `trim` | concatenated | 1 | shared | +180.5 % ± 29.5 % | +1344 ± 219 | 2088 ± 218 | 744 ± 9.7 | 1638 |
| `trim-typed` | concatenated | 1 | shared | +152.5 % ± 32.9 % | +1135 ± 244 | 1879 ± 244 | 744 ± 9.7 | 1613 |
| `rule` | throwable | 1 | shared | +1.6 % ± 1.4 % | +66.6 ± 55.5 | 4171 ± 42.1 | 4104 ± 36.2 | 16896 |
| `rule+storm` | throwable | 1 | shared | +0.7 % ± 1.2 % | +29.5 ± 48.8 | 4134 ± 32.7 | 4104 ± 36.2 | 16619 |
| `rule+storm+trim` | throwable | 1 | shared | +1.8 % ± 1.2 % | +73.9 ± 50.8 | 4178 ± 35.7 | 4104 ± 36.2 | 16424 |
| `idle` | throwable | 1 | shared | +102.1 % ± 2.0 % | +4189 ± 75.3 | 8293 ± 66.1 | 4104 ± 36.2 | 35203 |
| `drop-miss` | throwable | 1 | shared | +102.7 % ± 2.0 % | +4217 ± 72.9 | 8321 ± 63.2 | 4104 ± 36.2 | 35536 |
| `drop-hit` | throwable | 1 | shared | -77.8 % ± 2.1 % | -3193 ± 80.0 | 911 ± 71.3 | 4104 ± 36.2 | 371 |
| `trim` | throwable | 1 | shared | +123.8 % ± 8.6 % | +5081 ± 352 | 9185 ± 350 | 4104 ± 36.2 | 26305 |
| `trim-typed` | throwable | 1 | shared | +131.1 % ± 10.7 % | +5381 ± 438 | 9485 ± 437 | 4104 ± 36.2 | 26279 |
| `rule+storm` | template | 4 | shared | -2.1 % ± 2.5 % | -65.1 ± 77.2 | 2965 ± 60.4 | 3030 ± 48.0 | 1276 |
| `idle` | template | 4 | shared | -0.2 % ± 2.6 % | -5.2 ± 80.0 | 3024 ± 63.9 | 3030 ± 48.0 | 1396 |
| `rule+storm` | concatenated | 4 | shared | +11.4 % ± 3.3 % | +340 ± 97.4 | 3332 ± 74.7 | 2992 ± 62.4 | 1373 |
| `idle` | concatenated | 4 | shared | +7.4 % ± 6.1 % | +223 ± 182 | 3214 ± 171 | 2992 ± 62.4 | 1493 |
| `rule+storm` | template | 8 | shared | +0.8 % ± 1.8 % | +45.4 ± 104 | 5816 ± 74.8 | 5771 ± 71.8 | 1239 |
| `idle` | template | 8 | shared | -5.7 % ± 2.7 % | -330 ± 158 | 5441 ± 140 | 5771 ± 71.8 | 1358 |
| `rule+storm` | concatenated | 8 | shared | +10.3 % ± 3.5 % | +618 ± 210 | 6627 ± 146 | 6009 ± 152 | 1354 |
| `idle` | concatenated | 8 | shared | +22.5 % ± 3.5 % | +1353 ± 207 | 7362 ± 141 | 6009 ± 152 | 1493 |
| `idle` | template | 1 | per-thread | +19.5 % ± 3.4 % | +139 ± 24.0 | 853 ± 6.3 | 714 ± 23.2 | 1368 |
| `rule+storm` | template | 4 | per-thread | +9.1 % ± 1.8 % | +68.3 ± 13.7 | 822 ± 10.0 | 754 ± 9.3 | 1211 |
| `idle` | template | 4 | per-thread | +32.5 % ± 5.2 % | +245 ± 39.3 | 999 ± 38.1 | 754 ± 9.3 | 1331 |
| `rule+storm` | concatenated | 4 | per-thread | +48.7 % ± 3.8 % | +389 ± 29.0 | 1187 ± 23.6 | 798 ± 16.9 | 1326 |
| `idle` | concatenated | 4 | per-thread | +69.3 % ± 6.5 % | +553 ± 50.9 | 1351 ± 48.0 | 798 ± 16.9 | 1464 |
| `rule+storm` | template | 8 | per-thread | +12.9 % ± 3.9 % | +149 ± 44.9 | 1299 ± 41.8 | 1150 ± 16.4 | 1211 |
| `idle` | template | 8 | per-thread | +28.8 % ± 3.1 % | +331 ± 35.1 | 1481 ± 31.0 | 1150 ± 16.4 | 1331 |
| `rule+storm` | concatenated | 8 | per-thread | +42.9 % ± 2.9 % | +534 ± 35.4 | 1779 ± 33.3 | 1244 ± 12.1 | 1326 |
| `idle` | concatenated | 8 | per-thread | +60.4 % ± 1.7 % | +752 ± 20.2 | 1996 ± 16.1 | 1244 ± 12.1 | 1435 |

## Budgets

Single thread, one shared handler. A check passes when the difference *plus* its error is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the logger.

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| rule filter: `rule` − `baseline` | template | -2.9 ± 9.2 | 50.0 | pass |
| rule filter: `rule` − `baseline` | concatenated | -25.4 ± 10.8 | 50.0 | pass |
| rule filter: `rule` − `baseline` | throwable | +66.6 ± 55.5 | 50.0 | **over** |
| storm filter: `rule+storm` − `rule` | template | +125 ± 31.6 | 100 | **over** |
| storm filter: `rule+storm` − `rule` | concatenated | +374 ± 13.5 | 308 | **over** |
| no rule can match: `drop-miss` − `idle` | template | +22.6 ± 9.3 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | concatenated | +2.9 ± 19.4 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | throwable | +28.1 ± 91.5 | 50.0 | **over** |
| 20 rules: `drop-hit` − `idle` | template | +66.6 ± 56.8 | 204 | pass |
| 20 rules: `drop-hit` − `idle` | concatenated | -258 ± 86.3 | 204 | pass |
| 20 rules: `drop-hit` − `idle` | throwable | -7382 ± 97.2 | 204 | pass |
| 20 rules: `trim` − `idle` | template | +957 ± 217 | 204 | **over** |
| 20 rules: `trim` − `idle` | concatenated | +939 ± 219 | 204 | **over** |
| 20 rules: `trim` − `idle` | throwable | +892 ± 356 | 204 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | template | +776 ± 260 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | +731 ± 244 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | +1192 ± 442 | 204 | **over** |
| gate alone, 1 rules: `gate-n` − `gate-empty` | template | +582 ± 60.2 | 85.0 | **over** |
| gate alone, 5 rules: `gate-n` − `gate-empty` | template | +681 ± 129 | 140 | **over** |
| gate alone, 20 rules: `gate-n` − `gate-empty` | template | +778 ± 70.7 | 204 | **over** |
| gate alone, 100 rules: `gate-n` − `gate-empty` | template | +1486 ± 88.2 | 283 | **over** |

## What a match saves

One `INFO` call through a real file handler (autoflush on), single thread: the whole agent with 20 rules, the last of which matches, against no LogAperture through the same handler. `throwable` carries a 100-frame exception. A negative change means the matched record cost less than with no agent at all. Reported, not budgeted (Decision #22).

| Scenario | Message | Change | Change (ns) | Score (ns/op) | No agent (ns/op) |
|---|---|--:|--:|--:|--:|
| `drop-hit` | template | -59.9 % ± 3.1 % | -1348 ± 69.7 | 903 ± 67.8 | 2251 ± 16.0 |
| `trim` | template | +43.7 % ± 6.1 % | +984 ± 137 | 3235 ± 136 | 2251 ± 16.0 |
| `drop-hit` | throwable | -96.2 % ± 0.7 % | -23042 ± 137 | 921 ± 85.2 | 23963 ± 107 |
| `trim` | throwable | +16.6 % ± 2.0 % | +3981 ± 478 | 27945 ± 466 | 23963 ± 107 |

## Decision rules

- **#23** (`top` renders a throwable's stack trace a second time): the `top` layer costs +4115 ± 75.1 ns, +100.3 % ± 2.0 % of the baseline with a throwable; the threshold is 25 %. **Goes into 1.0.**
- **#24** (`top`'s one lock across independent handlers): at 8 threads on separate handlers, `idle` takes 1.73 ± 0.04× its single-thread time and `baseline` 1.61 ± 0.06×; the rule is `idle` over 1.5× while `baseline` is not. Stays out of 1.0.
- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.

## Component benchmarks

Each hot-path piece alone, to cross-check the differences above.

| Benchmark | Score (ns/op) | Allocated (B/op) |
|---|--:|--:|
| `GateCurveBenchmark.gate (rules=1)` | 583 ± 60.2 | 217 |
| `GateCurveBenchmark.gate (rules=100)` | 1488 ± 88.2 | 217 |
| `GateCurveBenchmark.gate (rules=20)` | 780 ± 70.7 | 211 |
| `GateCurveBenchmark.gate (rules=5)` | 682 ± 129 | 218 |
| `IdleComponentsBenchmark.gateEmpty` | 1.3 ± 0.0 | 0 |
| `IdleComponentsBenchmark.stormHash` | 159 ± 2.1 | 0 |
| `IdleComponentsBenchmark.stormNormalize` | 305 ± 2.4 | 248 |
| `IdleComponentsBenchmark.stormObserve` | 50.1 ± 0.7 | 24 |
| `IdleComponentsBenchmark.stormObserveConcatenated` | 358 ± 2.6 | 120 |
| `TopComponentsBenchmark.topRecord` | 18.6 ± 0.3 | 0 |
| `TopComponentsBenchmark.traceBytes` | 4073 ± 87.7 | 16960 |

## Machine

```
label:            published
quick:            false
date:             2026-10-06T00:57:54-05:00
computer:         fedora
cpu:              AMD Ryzen 5 3400G with Radeon Vega Graphics
cores/threads:    4 / 8
memory (GB):      60.8
os:               Fedora Linux 44 (Workstation Edition), kernel 7.2.8-200.fc44.x86_64
clocksource:      tsc
governor:         performance
boost:            0
git commit:       5ba0d6bcfdd67790817ff0e44c39e32ecf508673
git branch:       feature/128-overhead-benchmarks
nanoTime (ns):    24.7
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
