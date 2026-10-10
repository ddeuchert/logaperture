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
| `rule` | template | 1 | shared | -0.2 % ± 2.3 % | -1.7 ± 15.7 | 695 ± 10.5 | 697 ± 11.7 | 1187 |
| `rule+storm` | template | 1 | shared | +13.1 % ± 2.1 % | +91.4 ± 14.7 | 788 ± 8.9 | 697 ± 11.7 | 1248 |
| `rule+storm-off` | template | 1 | shared | +0.9 % ± 1.8 % | +6.1 ± 12.5 | 703 ± 4.4 | 697 ± 11.7 | 1205 |
| `rule+storm+trim` | template | 1 | shared | +11.3 % ± 1.9 % | +78.9 ± 13.1 | 776 ± 5.8 | 697 ± 11.7 | 1211 |
| `idle+storm` | template | 1 | shared | +18.4 % ± 2.2 % | +128 ± 15.3 | 825 ± 9.9 | 697 ± 11.7 | 1357 |
| `idle` | template | 1 | shared | +13.4 % ± 2.3 % | +93.4 ± 16.1 | 790 ± 10.9 | 697 ± 11.7 | 1325 |
| `drop-miss` | template | 1 | shared | +8.5 % ± 1.8 % | +58.9 ± 12.3 | 756 ± 3.6 | 697 ± 11.7 | 1341 |
| `drop-hit` | template | 1 | shared | +55.8 % ± 20.0 % | +389 ± 139 | 1086 ± 139 | 697 ± 11.7 | 633 |
| `trim` | template | 1 | shared | +31.3 % ± 1.9 % | +218 ± 12.8 | 915 ± 5.2 | 697 ± 11.7 | 1699 |
| `trim-typed` | template | 1 | shared | +14.7 % ± 3.1 % | +103 ± 21.4 | 800 ± 17.8 | 697 ± 11.7 | 1307 |
| `rule` | concatenated | 1 | shared | +1.8 % ± 2.5 % | +13.4 ± 17.8 | 739 ± 13.0 | 725 ± 12.2 | 1301 |
| `rule+storm` | concatenated | 1 | shared | +52.0 % ± 2.8 % | +377 ± 19.6 | 1102 ± 15.3 | 725 ± 12.2 | 1333 |
| `rule+storm-off` | concatenated | 1 | shared | +3.4 % ± 2.8 % | +24.5 ± 20.1 | 750 ± 16.0 | 725 ± 12.2 | 1320 |
| `rule+storm+trim` | concatenated | 1 | shared | +53.4 % ± 2.9 % | +388 ± 20.4 | 1113 ± 16.3 | 725 ± 12.2 | 1325 |
| `idle+storm` | concatenated | 1 | shared | +56.9 % ± 2.6 % | +413 ± 17.6 | 1138 ± 12.6 | 725 ± 12.2 | 1445 |
| `idle` | concatenated | 1 | shared | +9.3 % ± 2.0 % | +67.2 ± 14.2 | 793 ± 7.3 | 725 ± 12.2 | 1403 |
| `drop-miss` | concatenated | 1 | shared | +9.3 % ± 2.3 % | +67.3 ± 16.6 | 793 ± 11.1 | 725 ± 12.2 | 1403 |
| `drop-hit` | concatenated | 1 | shared | +61.0 % ± 18.4 % | +443 ± 134 | 1168 ± 133 | 725 ± 12.2 | 761 |
| `trim` | concatenated | 1 | shared | +34.5 % ± 2.8 % | +250 ± 19.8 | 976 ± 15.6 | 725 ± 12.2 | 1795 |
| `trim-typed` | concatenated | 1 | shared | +11.1 % ± 1.8 % | +80.2 ± 13.1 | 806 ± 4.8 | 725 ± 12.2 | 1384 |
| `rule` | throwable | 1 | shared | +1.0 % ± 0.9 % | +42.1 ± 37.4 | 4081 ± 36.1 | 4039 ± 9.9 | 16907 |
| `rule+storm` | throwable | 1 | shared | +3.0 % ± 0.8 % | +121 ± 31.2 | 4160 ± 29.5 | 4039 ± 9.9 | 16480 |
| `rule+storm-off` | throwable | 1 | shared | +2.1 % ± 0.5 % | +83.6 ± 19.9 | 4123 ± 17.2 | 4039 ± 9.9 | 16915 |
| `rule+storm+trim` | throwable | 1 | shared | +4.3 % ± 1.2 % | +172 ± 50.1 | 4211 ± 49.1 | 4039 ± 9.9 | 16603 |
| `idle+storm` | throwable | 1 | shared | +13.1 % ± 1.2 % | +528 ± 50.2 | 4567 ± 49.2 | 4039 ± 9.9 | 18688 |
| `idle` | throwable | 1 | shared | +12.8 % ± 2.2 % | +517 ± 87.8 | 4556 ± 87.3 | 4039 ± 9.9 | 18672 |
| `drop-miss` | throwable | 1 | shared | +12.0 % ± 0.7 % | +486 ± 29.1 | 4525 ± 27.4 | 4039 ± 9.9 | 18664 |
| `drop-hit` | throwable | 1 | shared | -72.7 % ± 3.3 % | -2937 ± 132 | 1102 ± 131 | 4039 ± 9.9 | 641 |
| `trim` | throwable | 1 | shared | -14.9 % ± 8.5 % | -604 ± 342 | 3435 ± 341 | 4039 ± 9.9 | 8834 |
| `trim-typed` | throwable | 1 | shared | +26.1 % ± 9.0 % | +1054 ± 366 | 5093 ± 365 | 4039 ± 9.9 | 8822 |
| `rule+storm` | template | 4 | shared | -5.5 % ± 2.5 % | -170 ± 75.3 | 2898 ± 55.0 | 3067 ± 51.5 | 1276 |
| `idle` | template | 4 | shared | -3.0 % ± 2.9 % | -91.8 ± 89.0 | 2976 ± 72.7 | 3067 ± 51.5 | 1372 |
| `rule+storm` | concatenated | 4 | shared | +9.6 % ± 3.0 % | +287 ± 88.6 | 3273 ± 71.6 | 2985 ± 52.2 | 1373 |
| `idle` | concatenated | 4 | shared | +1.8 % ± 2.2 % | +53.5 ± 66.9 | 3039 ± 41.8 | 2985 ± 52.2 | 1468 |
| `rule+storm` | template | 8 | shared | -8.2 % ± 2.4 % | -515 ± 154 | 5786 ± 126 | 6302 ± 89.2 | 1257 |
| `idle` | template | 8 | shared | -11.3 % ± 3.7 % | -712 ± 233 | 5589 ± 215 | 6302 ± 89.2 | 1353 |
| `rule+storm` | concatenated | 8 | shared | +19.9 % ± 4.4 % | +1153 ± 254 | 6950 ± 172 | 5796 ± 187 | 1355 |
| `idle` | concatenated | 8 | shared | +0.1 % ± 3.4 % | +7.7 ± 200 | 5804 ± 71.8 | 5796 ± 187 | 1468 |
| `idle` | template | 1 | per-thread | +6.5 % ± 0.9 % | +46.5 ± 6.1 | 756 ± 2.4 | 710 ± 5.7 | 1325 |
| `rule+storm` | template | 4 | per-thread | +8.6 % ± 4.1 % | +66.0 ± 31.6 | 837 ± 18.4 | 771 ± 25.7 | 1248 |
| `idle` | template | 4 | per-thread | +16.2 % ± 4.5 % | +125 ± 34.5 | 896 ± 23.0 | 771 ± 25.7 | 1333 |
| `rule+storm` | concatenated | 4 | per-thread | +56.1 % ± 3.6 % | +435 ± 27.6 | 1212 ± 26.9 | 776 ± 6.2 | 1326 |
| `idle` | concatenated | 4 | per-thread | +21.4 % ± 1.7 % | +166 ± 12.9 | 942 ± 11.3 | 776 ± 6.2 | 1440 |
| `rule+storm` | template | 8 | per-thread | +10.3 % ± 3.4 % | +117 ± 38.6 | 1248 ± 32.5 | 1131 ± 20.9 | 1192 |
| `idle` | template | 8 | per-thread | +25.0 % ± 3.2 % | +283 ± 35.6 | 1415 ± 28.9 | 1131 ± 20.9 | 1288 |
| `rule+storm` | concatenated | 8 | per-thread | +49.0 % ± 3.0 % | +585 ± 33.1 | 1777 ± 19.2 | 1193 ± 27.0 | 1307 |
| `idle` | concatenated | 8 | per-thread | +24.2 % ± 3.4 % | +288 ± 40.2 | 1481 ± 29.9 | 1193 ± 27.0 | 1384 |

## Budgets

Single thread, one shared handler. A check passes when the difference *plus* its error is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the logger.

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| rule filter: `rule` − `baseline` | template | -1.7 ± 15.7 | 50.0 | pass |
| rule filter: `rule` − `baseline` | concatenated | +13.4 ± 17.8 | 50.0 | pass |
| rule filter: `rule` − `baseline` | throwable | +42.1 ± 37.4 | 50.0 | **over** |
| storm filter: `rule+storm` − `rule` | template | +93.1 ± 13.7 | 100 | **over** |
| storm filter: `rule+storm` − `rule` | concatenated | +364 ± 20.0 | 308 | **over** |
| storm filter, disabled: `storm-off` − `storm-off-delegate` (component) | any | +1.2 ± 0.0 | 10.0 | pass |
| storm filter, disabled: `rule+storm-off` − `rule` | template | +7.8 ± 11.3 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | concatenated | +11.1 ± 20.6 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | throwable | +41.5 ± 40.0 | — | reported |
| no rule can match: `drop-miss` − `idle` | template | -34.5 ± 11.5 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | concatenated | +0.1 ± 13.3 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | throwable | -31.0 ± 91.4 | 50.0 | **over** |
| 20 rules: `drop-hit` − `idle` | template | +296 ± 139 | 204 | **over** |
| 20 rules: `drop-hit` − `idle` | concatenated | +375 ± 133 | 204 | **over** |
| 20 rules: `drop-hit` − `idle` | throwable | -3454 ± 158 | 204 | pass |
| 20 rules: `trim` − `idle` | template | +125 ± 12.1 | 204 | pass |
| 20 rules: `trim` − `idle` | concatenated | +183 ± 17.2 | 204 | pass |
| 20 rules: `trim` − `idle` | throwable | -1120 ± 352 | 204 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | template | +9.2 ± 20.9 | 50.0 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | +13.1 ± 8.7 | 50.0 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | +538 ± 376 | 204 | **over** |
| gate alone, 1 rules: `gate-n` − `gate-empty` | template | +33.1 ± 0.1 | 85.0 | pass |
| gate alone, 5 rules: `gate-n` − `gate-empty` | template | +78.5 ± 0.3 | 140 | pass |
| gate alone, 20 rules: `gate-n` − `gate-empty` | template | +171 ± 2.4 | 204 | pass |
| gate alone, 100 rules: `gate-n` − `gate-empty` | template | +199 ± 3.6 | 283 | pass |

## What a match saves

One `INFO` call through a real file handler (autoflush on), single thread: the whole agent with 20 rules, the last of which matches, against no LogAperture through the same handler. `throwable` carries a 100-frame exception. A negative change means the matched record cost less than with no agent at all. Reported, not budgeted (Decision #22).

| Scenario | Message | Change | Change (ns) | Score (ns/op) | No agent (ns/op) |
|---|---|--:|--:|--:|--:|
| `drop-hit` | template | -51.7 % ± 6.2 % | -1156 ± 139 | 1079 ± 137 | 2235 ± 18.4 |
| `trim` | template | +14.2 % ± 1.3 % | +317 ± 30.0 | 2551 ± 23.8 | 2235 ± 18.4 |
| `drop-hit` | throwable | -95.5 % ± 4.0 % | -23416 ± 719 | 1099 ± 139 | 24515 ± 706 |
| `trim` | throwable | -76.5 % ± 4.0 % | -18752 ± 810 | 5763 ± 397 | 24515 ± 706 |

## Decision rules

- **#23** (`top` renders a throwable's stack trace a second time): the `top` layer costs +356 ± 69.5 ns, +8.8 % ± 1.7 % of the baseline with a throwable; the threshold is 25 %. Stays out of 1.0.
- **#24** (`top`'s one lock across independent handlers): at 8 threads on separate handlers, `idle` takes 1.87 ± 0.04× its single-thread time and `baseline` 1.59 ± 0.03×; the rule is `idle` over 1.5× while `baseline` is not. Stays out of 1.0.
- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.

## Component benchmarks

Each hot-path piece alone, to cross-check the differences above.

| Benchmark | Score (ns/op) | Allocated (B/op) |
|---|--:|--:|
| `GateCurveBenchmark.gate (rules=1)` | 35.4 ± 0.1 | 120 |
| `GateCurveBenchmark.gate (rules=100)` | 201 ± 3.6 | 384 |
| `GateCurveBenchmark.gate (rules=20)` | 174 ± 2.4 | 384 |
| `GateCurveBenchmark.gate (rules=5)` | 80.8 ± 0.3 | 120 |
| `IdleComponentsBenchmark.gateEmpty` | 2.2 ± 0.0 | 0 |
| `IdleComponentsBenchmark.stormHash` | 156 ± 1.0 | 0 |
| `IdleComponentsBenchmark.stormNormalize` | 301 ± 2.9 | 248 |
| `IdleComponentsBenchmark.stormObserve` | 51.1 ± 1.2 | 24 |
| `IdleComponentsBenchmark.stormObserveConcatenated` | 347 ± 11.8 | 120 |
| `StormFilterComponentsBenchmark.stormOff` | 2.1 ± 0.0 | 0 |
| `StormFilterComponentsBenchmark.stormOffDelegate` | 1.0 ± 0.0 | 0 |
| `TopComponentsBenchmark.topRecord` | 18.8 ± 0.3 | 0 |
| `TopComponentsBenchmark.traceBytes` | 4208 ± 16.2 | 16960 |
| `TopComponentsBenchmark.traceShare` | 149 ± 2.9 | 96 |

## Machine

```
label:            published
quick:            false
date:             2026-10-09T21:35:21-05:00
computer:         fedora
cpu:              AMD Ryzen 5 3400G with Radeon Vega Graphics
cores/threads:    4 / 8
memory (GB):      60.8
os:               Fedora Linux 44 (Workstation Edition), kernel 7.2.8-200.fc44.x86_64
clocksource:      tsc
governor:         performance
boost:            0
git commit:       39a80d40a6fa4ab3898ea1bfd7408e3d7dcf6e72
git branch:       feature/128-overhead-benchmarks
nanoTime (ns):    24.6
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
