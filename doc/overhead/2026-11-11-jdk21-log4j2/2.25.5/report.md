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
| `rule` | template | 1 | shared | +2.3 % ± 2.2 % | +16.1 ± 15.6 | 713 ± 11.7 | 697 ± 10.4 | 1205 |
| `rule+storm` | template | 1 | shared | +11.8 % ± 2.0 % | +82.5 ± 13.9 | 780 ± 9.2 | 697 ± 10.4 | 1211 |
| `rule+storm-off` | template | 1 | shared | -0.8 % ± 2.3 % | -5.7 ± 16.0 | 691 ± 12.2 | 697 ± 10.4 | 1205 |
| `rule+storm+trim` | template | 1 | shared | +13.3 % ± 1.7 % | +92.8 ± 11.8 | 790 ± 5.5 | 697 ± 10.4 | 1219 |
| `idle+storm` | template | 1 | shared | +19.6 % ± 1.9 % | +136 ± 12.7 | 834 ± 7.4 | 697 ± 10.4 | 1347 |
| `idle` | template | 1 | shared | +6.2 % ± 1.9 % | +43.5 ± 12.9 | 741 ± 7.6 | 697 ± 10.4 | 1296 |
| `drop-miss` | template | 1 | shared | +9.9 % ± 1.7 % | +68.7 ± 11.9 | 766 ± 5.8 | 697 ± 10.4 | 1304 |
| `drop-hit` | template | 1 | shared | +60.9 % ± 16.5 % | +424 ± 115 | 1122 ± 115 | 697 ± 10.4 | 633 |
| `trim` | template | 1 | shared | +39.0 % ± 3.5 % | +272 ± 24.0 | 969 ± 21.6 | 697 ± 10.4 | 1680 |
| `trim-typed` | template | 1 | shared | +14.3 % ± 2.2 % | +99.6 ± 15.0 | 797 ± 10.8 | 697 ± 10.4 | 1296 |
| `rule` | concatenated | 1 | shared | +0.2 % ± 1.2 % | +1.7 ± 8.9 | 743 ± 7.1 | 741 ± 5.3 | 1301 |
| `rule+storm` | concatenated | 1 | shared | +48.6 % ± 1.3 % | +360 ± 9.2 | 1101 ± 7.5 | 741 ± 5.3 | 1344 |
| `rule+storm-off` | concatenated | 1 | shared | +0.6 % ± 1.7 % | +4.8 ± 12.8 | 746 ± 11.7 | 741 ± 5.3 | 1301 |
| `rule+storm+trim` | concatenated | 1 | shared | +47.5 % ± 1.4 % | +352 ± 10.4 | 1093 ± 9.0 | 741 ± 5.3 | 1288 |
| `idle+storm` | concatenated | 1 | shared | +53.8 % ± 1.9 % | +399 ± 13.6 | 1140 ± 12.5 | 741 ± 5.3 | 1427 |
| `idle` | concatenated | 1 | shared | +4.1 % ± 1.5 % | +30.2 ± 11.4 | 771 ± 10.1 | 741 ± 5.3 | 1403 |
| `drop-miss` | concatenated | 1 | shared | +7.6 % ± 1.2 % | +56.4 ± 8.7 | 797 ± 6.9 | 741 ± 5.3 | 1421 |
| `drop-hit` | concatenated | 1 | shared | +59.2 % ± 17.0 % | +439 ± 126 | 1180 ± 126 | 741 ± 5.3 | 737 |
| `trim` | concatenated | 1 | shared | +35.5 % ± 1.3 % | +263 ± 9.1 | 1004 ± 7.5 | 741 ± 5.3 | 1792 |
| `trim-typed` | concatenated | 1 | shared | +9.5 % ± 1.3 % | +70.6 ± 9.9 | 812 ± 8.4 | 741 ± 5.3 | 1421 |
| `rule` | throwable | 1 | shared | -1.3 % ± 1.2 % | -52.6 ± 50.5 | 4084 ± 49.3 | 4137 ± 11.0 | 16888 |
| `rule+storm` | throwable | 1 | shared | +3.7 % ± 1.7 % | +152 ± 71.7 | 4289 ± 70.8 | 4137 ± 11.0 | 16469 |
| `rule+storm-off` | throwable | 1 | shared | -1.0 % ± 0.5 % | -42.6 ± 21.7 | 4094 ± 18.7 | 4137 ± 11.0 | 16936 |
| `rule+storm+trim` | throwable | 1 | shared | +2.2 % ± 0.7 % | +92.5 ± 27.1 | 4229 ± 24.8 | 4137 ± 11.0 | 16461 |
| `idle+storm` | throwable | 1 | shared | +9.2 % ± 0.5 % | +379 ± 19.3 | 4516 ± 15.9 | 4137 ± 11.0 | 18696 |
| `idle` | throwable | 1 | shared | +8.4 % ± 1.0 % | +347 ± 42.8 | 4484 ± 41.4 | 4137 ± 11.0 | 18645 |
| `drop-miss` | throwable | 1 | shared | +10.1 % ± 0.9 % | +418 ± 35.2 | 4554 ± 33.5 | 4137 ± 11.0 | 18672 |
| `drop-hit` | throwable | 1 | shared | -73.0 % ± 3.2 % | -3020 ± 133 | 1117 ± 133 | 4137 ± 11.0 | 633 |
| `trim` | throwable | 1 | shared | -16.2 % ± 8.9 % | -669 ± 367 | 3468 ± 367 | 4137 ± 11.0 | 8858 |
| `trim-typed` | throwable | 1 | shared | +19.2 % ± 8.7 % | +793 ± 359 | 4929 ± 359 | 4137 ± 11.0 | 8855 |
| `rule+storm` | template | 4 | shared | -5.1 % ± 2.5 % | -155 ± 77.8 | 2899 ± 65.7 | 3054 ± 41.6 | 1258 |
| `idle` | template | 4 | shared | -2.6 % ± 1.7 % | -79.3 ± 50.9 | 2975 ± 29.3 | 3054 ± 41.6 | 1372 |
| `rule+storm` | concatenated | 4 | shared | +12.2 % ± 3.7 % | +361 ± 110 | 3309 ± 90.4 | 2948 ± 63.0 | 1373 |
| `idle` | concatenated | 4 | shared | +0.9 % ± 2.8 % | +26.0 ± 81.7 | 2974 ± 52.0 | 2948 ± 63.0 | 1468 |
| `rule+storm` | template | 8 | shared | -5.2 % ± 3.7 % | -308 ± 217 | 5613 ± 183 | 5920 ± 117 | 1249 |
| `idle` | template | 8 | shared | -6.1 % ± 3.3 % | -360 ± 194 | 5560 ± 154 | 5920 ± 117 | 1353 |
| `rule+storm` | concatenated | 8 | shared | +12.2 % ± 1.8 % | +728 ± 107 | 6674 ± 85.9 | 5946 ± 63.7 | 1365 |
| `idle` | concatenated | 8 | shared | -8.7 % ± 2.7 % | -515 ± 161 | 5431 ± 148 | 5946 ± 63.7 | 1449 |
| `idle` | template | 1 | per-thread | +5.6 % ± 2.3 % | +39.5 ± 16.5 | 747 ± 13.2 | 707 ± 10.0 | 1325 |
| `rule+storm` | template | 4 | per-thread | +10.5 % ± 2.0 % | +76.5 ± 14.3 | 807 ± 11.8 | 730 ± 8.1 | 1229 |
| `idle` | template | 4 | per-thread | +28.3 % ± 3.5 % | +207 ± 25.7 | 937 ± 24.4 | 730 ± 8.1 | 1325 |
| `rule+storm` | concatenated | 4 | per-thread | +52.7 % ± 3.8 % | +416 ± 28.2 | 1206 ± 18.4 | 790 ± 21.4 | 1307 |
| `idle` | concatenated | 4 | per-thread | +24.3 % ± 3.7 % | +192 ± 28.8 | 981 ± 19.4 | 790 ± 21.4 | 1448 |
| `rule+storm` | template | 8 | per-thread | +7.8 % ± 3.0 % | +90.7 ± 35.0 | 1251 ± 14.4 | 1160 ± 31.9 | 1229 |
| `idle` | template | 8 | per-thread | +23.4 % ± 3.4 % | +272 ± 38.8 | 1432 ± 22.2 | 1160 ± 31.9 | 1296 |
| `rule+storm` | concatenated | 8 | per-thread | +45.1 % ± 2.2 % | +548 ± 26.5 | 1765 ± 22.4 | 1216 ± 14.2 | 1308 |
| `idle` | concatenated | 8 | per-thread | +22.0 % ± 3.5 % | +267 ± 42.3 | 1484 ± 39.8 | 1216 ± 14.2 | 1411 |

## Budgets

Single thread, one shared handler. A check passes when the difference *plus* its error is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the logger.

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| rule filter: `rule` − `baseline` | template | +16.1 ± 15.6 | 50.0 | pass |
| rule filter: `rule` − `baseline` | concatenated | +1.7 ± 8.9 | 50.0 | pass |
| rule filter: `rule` − `baseline` | throwable | -52.6 ± 50.5 | 50.0 | pass |
| storm filter: `rule+storm` − `rule` | template | +66.3 ± 14.9 | 100 | pass |
| storm filter: `rule+storm` − `rule` | concatenated | +358 ± 10.4 | 308 | **over** |
| storm filter, disabled: `storm-off` − `storm-off-delegate` (component) | any | +1.2 ± 0.0 | 10.0 | pass |
| storm filter, disabled: `rule+storm-off` − `rule` | template | -21.8 ± 16.9 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | concatenated | +3.1 ± 13.7 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | throwable | +9.9 ± 52.7 | — | reported |
| no rule can match: `drop-miss` − `idle` | template | +25.2 ± 9.6 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | concatenated | +26.2 ± 12.2 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | throwable | +70.7 ± 53.2 | 50.0 | **over** |
| 20 rules: `drop-hit` − `idle` | template | +381 ± 115 | 204 | **over** |
| 20 rules: `drop-hit` − `idle` | concatenated | +408 ± 126 | 204 | **over** |
| 20 rules: `drop-hit` − `idle` | throwable | -3367 ± 139 | 204 | pass |
| 20 rules: `trim` − `idle` | template | +228 ± 22.9 | 204 | **over** |
| 20 rules: `trim` − `idle` | concatenated | +233 ± 12.6 | 204 | **over** |
| 20 rules: `trim` − `idle` | throwable | -1016 ± 369 | 204 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | template | +56.1 ± 13.2 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | +40.3 ± 13.1 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | +446 ± 361 | 204 | **over** |
| gate alone, 1 rules: `gate-n` − `gate-empty` | template | +33.5 ± 0.2 | 85.0 | pass |
| gate alone, 5 rules: `gate-n` − `gate-empty` | template | +78.3 ± 0.2 | 140 | pass |
| gate alone, 20 rules: `gate-n` − `gate-empty` | template | +168 ± 7.1 | 204 | pass |
| gate alone, 100 rules: `gate-n` − `gate-empty` | template | +201 ± 1.0 | 283 | pass |

## What a match saves

One `INFO` call through a real file handler (autoflush on), single thread: the whole agent with 20 rules, the last of which matches, against no LogAperture through the same handler. `throwable` carries a 100-frame exception. A negative change means the matched record cost less than with no agent at all. Reported, not budgeted (Decision #22).

| Scenario | Message | Change | Change (ns) | Score (ns/op) | No agent (ns/op) |
|---|---|--:|--:|--:|--:|
| `drop-hit` | template | -50.6 % ± 5.8 % | -1138 ± 131 | 1112 ± 128 | 2251 ± 23.2 |
| `trim` | template | +18.1 % ± 2.2 % | +407 ± 48.4 | 2658 ± 42.5 | 2251 ± 23.2 |
| `drop-hit` | throwable | -95.6 % ± 1.4 % | -23698 ± 258 | 1100 ± 123 | 24799 ± 227 |
| `trim` | throwable | -77.0 % ± 2.0 % | -19098 ± 453 | 5700 ± 392 | 24799 ± 227 |

## Decision rules

- **#23** (`top` renders a throwable's stack trace a second time): the `top` layer costs +287 ± 29.5 ns, +6.9 % ± 0.7 % of the baseline with a throwable; the threshold is 25 %. Stays out of 1.0.
- **#24** (`top`'s one lock across independent handlers): at 8 threads on separate handlers, `idle` takes 1.92 ± 0.05× its single-thread time and `baseline` 1.64 ± 0.05×; the rule is `idle` over 1.5× while `baseline` is not. Stays out of 1.0.
- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.

## Log4j 2

**Log4j 2 version: 2.25.5**. The published run uses the latest tested 2.25 for every mode, and 2.20.0, the adapter's floor, for `idle` and `disabled` in a separate results folder (Decision #26); a trim regression of #333's kind shows on 2.25 and not on 2.20, so a number is only as good as the version printed here.

The Log4j 2 adapter's gate on a real `LoggerContext`, one `INFO` call, in each of Log4j's three modes. Overhead is against `baseline` (bare Log4j 2) of the same mode, message, thread count and appender shape. There is no `top` layer: byte counting is not built for Log4j 2. `template` is a parameterized message (`{}` with three arguments), so a rule that reads it formats it first; `concatenated` is a plain `String`; `throwable` is `template` plus a 20-frame exception. Budgets are checked in synchronous mode only; the two asynchronous modes are reported, because a hot loop into a queue measures the queue (Decision #27). A rule that reads a parameterized message pays for formatting it, which the 1.0 curve never included, so those rows are reported and the budgeted rule rows use the plain message (Decision #32).

### Overhead per log call (Log4j 2)

| Mode | Scenario | Message | Threads | Appenders | Overhead | Added (ns) | Score (ns/op) | Baseline (ns/op) | Allocated (B/op) |
|---|---|---|--:|---|--:|--:|--:|--:|--:|
| sync | `gate` | template | 1 | shared | +4.0 % ± 1.9 % | +21.9 ± 10.2 | 575 ± 8.7 | 553 ± 5.3 | 24 |
| sync | `gate+storm` | template | 1 | shared | +25.6 % ± 4.0 % | +142 ± 21.9 | 695 ± 21.2 | 553 ± 5.3 | 160 |
| sync | `gate+storm-off` | template | 1 | shared | +2.1 % ± 2.0 % | +11.4 ± 11.3 | 565 ± 9.9 | 553 ± 5.3 | 24 |
| sync | `gate+storm+trim-wrap` | template | 1 | shared | +17.3 % ± 1.1 % | +95.8 ± 6.3 | 649 ± 3.3 | 553 ± 5.3 | 160 |
| sync | `idle` | template | 1 | shared | +2.5 % ± 1.9 % | +13.9 ± 10.5 | 567 ± 9.0 | 553 ± 5.3 | 24 |
| sync | `drop-miss` | template | 1 | shared | +4.7 % ± 2.0 % | +25.9 ± 10.9 | 579 ± 9.5 | 553 ± 5.3 | 24 |
| sync | `drop-hit` | template | 1 | shared | +38.1 % ± 1.4 % | +211 ± 7.5 | 764 ± 5.2 | 553 ± 5.3 | 904 |
| sync | `trim` | template | 1 | shared | +96.7 % ± 14.6 % | +535 ± 80.7 | 1088 ± 80.5 | 553 ± 5.3 | 880 |
| sync | `trim-typed` | template | 1 | shared | +20.0 % ± 1.9 % | +110 ± 10.4 | 664 ± 9.0 | 553 ± 5.3 | 16 |
| sync | `gate` | concatenated | 1 | shared | +3.5 % ± 4.1 % | +14.6 ± 17.4 | 435 ± 13.9 | 421 ± 10.4 | 120 |
| sync | `gate+storm` | concatenated | 1 | shared | +90.7 % ± 3.9 % | +381 ± 13.5 | 802 ± 8.7 | 421 ± 10.4 | 216 |
| sync | `gate+storm-off` | concatenated | 1 | shared | +6.8 % ± 3.1 % | +28.7 ± 13.0 | 449 ± 7.8 | 421 ± 10.4 | 120 |
| sync | `gate+storm+trim-wrap` | concatenated | 1 | shared | +125.4 % ± 5.6 % | +527 ± 19.4 | 948 ± 16.4 | 421 ± 10.4 | 208 |
| sync | `idle` | concatenated | 1 | shared | +7.4 % ± 2.9 % | +31.2 ± 12.1 | 452 ± 6.3 | 421 ± 10.4 | 120 |
| sync | `drop-miss` | concatenated | 1 | shared | +9.5 % ± 3.4 % | +39.8 ± 14.5 | 460 ± 10.1 | 421 ± 10.4 | 120 |
| sync | `drop-hit` | concatenated | 1 | shared | +38.7 % ± 5.3 % | +163 ± 21.8 | 583 ± 19.1 | 421 ± 10.4 | 608 |
| sync | `trim` | concatenated | 1 | shared | +55.3 % ± 4.1 % | +233 ± 16.2 | 653 ± 12.4 | 421 ± 10.4 | 576 |
| sync | `trim-typed` | concatenated | 1 | shared | +21.0 % ± 3.6 % | +88.5 ± 14.9 | 509 ± 10.7 | 421 ± 10.4 | 104 |
| sync | `gate` | throwable | 1 | shared | -0.7 % ± 1.1 % | -41.8 ± 66.0 | 5915 ± 20.0 | 5956 ± 62.9 | 16040 |
| sync | `gate+storm` | throwable | 1 | shared | +1.7 % ± 1.2 % | +103 ± 69.6 | 6060 ± 29.7 | 5956 ± 62.9 | 16208 |
| sync | `gate+storm-off` | throwable | 1 | shared | -1.6 % ± 1.1 % | -93.7 ± 65.5 | 5863 ± 18.1 | 5956 ± 62.9 | 16320 |
| sync | `gate+storm+trim-wrap` | throwable | 1 | shared | +4.8 % ± 1.5 % | +285 ± 91.4 | 6241 ± 66.3 | 5956 ± 62.9 | 16784 |
| sync | `idle` | throwable | 1 | shared | -1.8 % ± 2.2 % | -108 ± 129 | 5849 ± 113 | 5956 ± 62.9 | 15760 |
| sync | `drop-miss` | throwable | 1 | shared | -4.3 % ± 1.7 % | -257 ± 100 | 5699 ± 78.3 | 5956 ± 62.9 | 15760 |
| sync | `drop-hit` | throwable | 1 | shared | -85.7 % ± 1.4 % | -5103 ± 63.1 | 853 ± 4.1 | 5956 ± 62.9 | 952 |
| sync | `trim` | throwable | 1 | shared | +19.2 % ± 2.5 % | +1142 ± 149 | 7099 ± 135 | 5956 ± 62.9 | 6032 |
| sync | `trim-typed` | throwable | 1 | shared | +50.6 % ± 2.3 % | +3016 ± 135 | 8972 ± 120 | 5956 ± 62.9 | 5488 |
| async-appender | `gate` | template | 1 | shared | +1.2 % ± 5.7 % | +8.4 ± 39.7 | 711 ± 12.1 | 702 ± 37.8 | 298 |
| async-appender | `gate+storm` | template | 1 | shared | +21.1 % ± 10.8 % | +148 ± 75.6 | 851 ± 65.4 | 702 ± 37.8 | 431 |
| async-appender | `gate+storm-off` | template | 1 | shared | +4.0 % ± 7.1 % | +28.0 ± 50.1 | 730 ± 32.8 | 702 ± 37.8 | 361 |
| async-appender | `gate+storm+trim-wrap` | template | 1 | shared | +14.2 % ± 5.9 % | +100 ± 41.2 | 802 ± 16.2 | 702 ± 37.8 | 431 |
| async-appender | `idle` | template | 1 | shared | -2.0 % ± 6.2 % | -13.9 ± 43.3 | 688 ± 21.1 | 702 ± 37.8 | 289 |
| async-appender | `drop-miss` | template | 1 | shared | +2.3 % ± 6.4 % | +16.0 ± 44.9 | 718 ± 24.1 | 702 ± 37.8 | 299 |
| async-appender | `drop-hit` | template | 1 | shared | +12.6 % ± 6.4 % | +88.8 ± 44.6 | 791 ± 23.6 | 702 ± 37.8 | 904 |
| async-appender | `trim` | template | 1 | shared | +102.1 % ± 8.8 % | +717 ± 48.3 | 1419 ± 30.0 | 702 ± 37.8 | 1144 |
| async-appender | `trim-typed` | template | 1 | shared | +24.4 % ± 8.7 % | +172 ± 60.7 | 874 ± 47.4 | 702 ± 37.8 | 288 |
| async-appender | `gate` | concatenated | 1 | shared | -1.9 % ± 14.1 % | -7.9 ± 59.6 | 415 ± 48.9 | 423 ± 34.2 | 427 |
| async-appender | `gate+storm` | concatenated | 1 | shared | +141.7 % ± 14.6 % | +599 ± 38.1 | 1022 ± 16.8 | 423 ± 34.2 | 480 |
| async-appender | `gate+storm-off` | concatenated | 1 | shared | +13.2 % ± 10.1 % | +56.0 ± 42.3 | 479 ± 24.9 | 423 ± 34.2 | 436 |
| async-appender | `gate+storm+trim-wrap` | concatenated | 1 | shared | +186.9 % ± 18.1 % | +790 ± 42.2 | 1213 ± 24.7 | 423 ± 34.2 | 471 |
| async-appender | `idle` | concatenated | 1 | shared | -1.5 % ± 11.2 % | -6.1 ± 47.2 | 417 ± 32.5 | 423 ± 34.2 | 371 |
| async-appender | `drop-miss` | concatenated | 1 | shared | -3.8 % ± 13.6 % | -15.9 ± 57.7 | 407 ± 46.4 | 423 ± 34.2 | 364 |
| async-appender | `drop-hit` | concatenated | 1 | shared | +40.4 % ± 9.2 % | +171 ± 36.6 | 594 ± 13.1 | 423 ± 34.2 | 608 |
| async-appender | `trim` | concatenated | 1 | shared | +86.5 % ± 11.1 % | +366 ± 36.7 | 789 ± 13.3 | 423 ± 34.2 | 847 |
| async-appender | `trim-typed` | concatenated | 1 | shared | +42.8 % ± 10.2 % | +181 ± 40.7 | 604 ± 22.1 | 423 ± 34.2 | 361 |
| async-appender | `gate` | throwable | 1 | shared | +0.2 % ± 1.2 % | +13.7 ± 81.7 | 6979 ± 52.3 | 6965 ± 62.8 | 17408 |
| async-appender | `gate+storm` | throwable | 1 | shared | +1.0 % ± 1.1 % | +70.1 ± 73.7 | 7035 ± 38.5 | 6965 ± 62.8 | 17588 |
| async-appender | `gate+storm-off` | throwable | 1 | shared | +1.2 % ± 2.1 % | +80.6 ± 143 | 7045 ± 129 | 6965 ± 62.8 | 17419 |
| async-appender | `gate+storm+trim-wrap` | throwable | 1 | shared | +4.9 % ± 2.2 % | +341 ± 150 | 7306 ± 136 | 6965 ± 62.8 | 17542 |
| async-appender | `idle` | throwable | 1 | shared | +0.1 % ± 1.4 % | +9.9 ± 96.5 | 6975 ± 73.3 | 6965 ± 62.8 | 16754 |
| async-appender | `drop-miss` | throwable | 1 | shared | +1.7 % ± 1.5 % | +119 ± 104 | 7084 ± 83.0 | 6965 ± 62.8 | 16768 |
| async-appender | `drop-hit` | throwable | 1 | shared | -87.6 % ± 1.2 % | -6102 ± 63.7 | 862 ± 10.8 | 6965 ± 62.8 | 952 |
| async-appender | `trim` | throwable | 1 | shared | +8.1 % ± 1.4 % | +568 ± 96.5 | 7532 ± 73.3 | 6965 ± 62.8 | 6293 |
| async-appender | `trim-typed` | throwable | 1 | shared | +29.6 % ± 2.0 % | +2058 ± 135 | 9023 ± 120 | 6965 ± 62.8 | 5816 |
| async-loggers | `gate` | template | 1 | shared | +1.7 % ± 0.9 % | +7.8 ± 4.4 | 477 ± 2.3 | 469 ± 3.7 | 0 |
| async-loggers | `gate+storm` | template | 1 | shared | +21.7 % ± 1.1 % | +102 ± 5.2 | 571 ± 3.6 | 469 ± 3.7 | 136 |
| async-loggers | `gate+storm-off` | template | 1 | shared | +5.8 % ± 3.8 % | +27.1 ± 18.0 | 496 ± 17.6 | 469 ± 3.7 | 0 |
| async-loggers | `gate+storm+trim-wrap` | template | 1 | shared | +25.5 % ± 3.9 % | +120 ± 18.4 | 589 ± 18.0 | 469 ± 3.7 | 136 |
| async-loggers | `idle` | template | 1 | shared | +1.3 % ± 1.1 % | +6.2 ± 5.0 | 475 ± 3.3 | 469 ± 3.7 | 0 |
| async-loggers | `drop-miss` | template | 1 | shared | +15.3 % ± 1.1 % | +71.7 ± 5.0 | 541 ± 3.3 | 469 ± 3.7 | 0 |
| async-loggers | `drop-hit` | template | 1 | shared | +62.9 % ± 1.7 % | +295 ± 7.6 | 764 ± 6.6 | 469 ± 3.7 | 904 |
| async-loggers | `trim` | template | 1 | shared | +194.9 % ± 3.6 % | +914 ± 15.3 | 1383 ± 14.8 | 469 ± 3.7 | 840 |
| async-loggers | `trim-typed` | template | 1 | shared | +24.1 % ± 1.5 % | +113 ± 7.1 | 582 ± 6.0 | 469 ± 3.7 | 0 |
| async-loggers | `gate` | concatenated | 1 | shared | -2.0 % ± 4.9 % | -6.2 ± 15.3 | 303 ± 13.1 | 309 ± 7.8 | 96 |
| async-loggers | `gate+storm` | concatenated | 1 | shared | +209.5 % ± 8.2 % | +648 ± 19.3 | 957 ± 17.6 | 309 ± 7.8 | 200 |
| async-loggers | `gate+storm-off` | concatenated | 1 | shared | +1.5 % ± 4.7 % | +4.6 ± 14.4 | 314 ± 12.1 | 309 ± 7.8 | 96 |
| async-loggers | `gate+storm+trim-wrap` | concatenated | 1 | shared | +265.3 % ± 10.5 % | +820 ± 24.8 | 1130 ± 23.5 | 309 ± 7.8 | 200 |
| async-loggers | `idle` | concatenated | 1 | shared | +4.8 % ± 4.8 % | +14.7 ± 14.8 | 324 ± 12.6 | 309 ± 7.8 | 96 |
| async-loggers | `drop-miss` | concatenated | 1 | shared | -1.2 % ± 2.6 % | -3.7 ± 8.1 | 306 ± 2.2 | 309 ± 7.8 | 96 |
| async-loggers | `drop-hit` | concatenated | 1 | shared | +97.4 % ± 13.9 % | +301 ± 42.3 | 610 ± 41.6 | 309 ± 7.8 | 608 |
| async-loggers | `trim` | concatenated | 1 | shared | +113.8 % ± 7.6 % | +352 ± 21.7 | 661 ± 20.3 | 309 ± 7.8 | 568 |
| async-loggers | `trim-typed` | concatenated | 1 | shared | +25.7 % ± 12.7 % | +79.4 ± 39.2 | 389 ± 38.4 | 309 ± 7.8 | 96 |
| async-loggers | `gate` | throwable | 1 | shared | +0.5 % ± 2.1 % | +28.0 ± 120 | 5764 ± 74.5 | 5736 ± 93.6 | 15757 |
| async-loggers | `gate+storm` | throwable | 1 | shared | +4.1 % ± 3.1 % | +236 ± 175 | 5972 ± 148 | 5736 ± 93.6 | 16222 |
| async-loggers | `gate+storm-off` | throwable | 1 | shared | -0.5 % ± 2.1 % | -28.6 ± 120 | 5708 ± 74.3 | 5736 ± 93.6 | 15758 |
| async-loggers | `gate+storm+trim-wrap` | throwable | 1 | shared | +3.0 % ± 2.0 % | +170 ± 115 | 5906 ± 66.5 | 5736 ± 93.6 | 15944 |
| async-loggers | `idle` | throwable | 1 | shared | +3.6 % ± 2.1 % | +208 ± 119 | 5944 ± 73.1 | 5736 ± 93.6 | 15753 |
| async-loggers | `drop-miss` | throwable | 1 | shared | +2.1 % ± 2.1 % | +123 ± 118 | 5859 ± 72.4 | 5736 ± 93.6 | 15751 |
| async-loggers | `drop-hit` | throwable | 1 | shared | -85.2 % ± 2.1 % | -4886 ± 93.7 | 850 ± 4.2 | 5736 ± 93.6 | 952 |
| async-loggers | `trim` | throwable | 1 | shared | -20.3 % ± 2.0 % | -1167 ± 111 | 4569 ± 59.6 | 5736 ± 93.6 | 5989 |
| async-loggers | `trim-typed` | throwable | 1 | shared | -0.0 % ± 1.9 % | -0.0 ± 109 | 5736 ± 55.2 | 5736 ± 93.6 | 5624 |
| sync | `gate+storm` | template | 4 | shared | +12.3 % ± 7.0 % | +110 ± 62.1 | 1000 ± 44.2 | 890 ± 43.6 | 160 |
| sync | `idle` | template | 4 | shared | +2.9 % ± 6.8 % | +25.5 ± 60.5 | 916 ± 41.9 | 890 ± 43.6 | 24 |
| sync | `gate+storm` | concatenated | 4 | shared | +20.0 % ± 10.0 % | +184 ± 91.0 | 1106 ± 61.7 | 921 ± 66.9 | 216 |
| sync | `idle` | concatenated | 4 | shared | -0.8 % ± 11.1 % | -7.2 ± 103 | 914 ± 77.9 | 921 ± 66.9 | 120 |
| sync | `gate+storm` | template | 8 | shared | +3.6 % ± 2.8 % | +90.5 ± 70.4 | 2618 ± 53.5 | 2527 ± 45.7 | 152 |
| sync | `idle` | template | 8 | shared | +0.6 % ± 3.1 % | +14.6 ± 77.5 | 2542 ± 62.6 | 2527 ± 45.7 | 16 |
| sync | `gate+storm` | concatenated | 8 | shared | +14.3 % ± 7.2 % | +344 ± 172 | 2753 ± 153 | 2409 ± 78.2 | 224 |
| sync | `idle` | concatenated | 8 | shared | -2.3 % ± 4.1 % | -54.8 ± 99.9 | 2354 ± 62.2 | 2409 ± 78.2 | 104 |
| sync | `idle` | template | 1 | per-thread | +0.2 % ± 2.4 % | +1.4 ± 13.5 | 564 ± 7.2 | 562 ± 11.5 | 24 |
| sync | `idle` | concatenated | 1 | per-thread | +8.9 % ± 0.9 % | +38.0 ± 3.8 | 463 ± 3.3 | 426 ± 1.9 | 120 |
| sync | `gate+storm` | template | 4 | per-thread | +23.7 % ± 6.2 % | +149 ± 38.8 | 778 ± 31.6 | 629 ± 22.4 | 152 |
| sync | `idle` | template | 4 | per-thread | -0.4 % ± 9.8 % | -2.4 ± 61.5 | 626 ± 57.3 | 629 ± 22.4 | 8 |
| sync | `gate+storm` | concatenated | 4 | per-thread | +88.0 % ± 6.6 % | +416 ± 29.0 | 890 ± 26.0 | 474 ± 12.8 | 208 |
| sync | `idle` | concatenated | 4 | per-thread | +0.2 % ± 5.3 % | +0.9 ± 24.9 | 474 ± 21.3 | 474 ± 12.8 | 112 |
| sync | `gate+storm` | template | 8 | per-thread | +32.0 % ± 4.9 % | +308 ± 45.9 | 1269 ± 35.5 | 961 ± 29.1 | 136 |
| sync | `idle` | template | 8 | per-thread | +1.5 % ± 8.6 % | +13.9 ± 83.1 | 975 ± 77.8 | 961 ± 29.1 | 8 |
| sync | `gate+storm` | concatenated | 8 | per-thread | +77.3 % ± 6.0 % | +602 ± 45.6 | 1381 ± 43.0 | 779 ± 15.2 | 200 |
| sync | `idle` | concatenated | 8 | per-thread | +0.5 % ± 2.9 % | +4.2 ± 22.7 | 783 ± 16.8 | 779 ± 15.2 | 112 |

### Log4j 2 budgets

Synchronous mode, single thread, one shared appender. A check passes when the difference *plus* its error is within the budget (Decisions #8, #13, #21, #23, #28, #32). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1). Rows marked *parameterized* include the cost of formatting the message for the rules and are reported, not budgeted (Decision #32).

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| gate: `gate` − `baseline` | template | +21.9 ± 10.2 | 50.0 | pass |
| gate: `gate` − `baseline` | concatenated | +14.6 ± 17.4 | 50.0 | pass |
| gate: `gate` − `baseline` | throwable | -41.8 ± 66.0 | 50.0 | pass |
| storm filter: `gate+storm` − `gate` | template | +120 ± 23.0 | 100 | **over** |
| storm filter: `gate+storm` − `gate` | concatenated | +367 ± 16.4 | 308 | **over** |
| storm filter, disabled: `gate+storm-off` − `gate` | template | -10.5 ± 13.2 | — | reported |
| storm filter, disabled: `gate+storm-off` − `gate` | concatenated | +14.1 ± 16.0 | — | reported |
| storm filter, disabled: `gate+storm-off` − `gate` | throwable | -51.9 ± 27.0 | — | reported |
| idle (shipped default): `idle` − `baseline` | template | +13.9 ± 10.5 | 50.0 | pass |
| idle (shipped default): `idle` − `baseline` | concatenated | +31.2 ± 12.1 | 50.0 | pass |
| idle (shipped default): `idle` − `baseline` | throwable | -108 ± 129 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | concatenated | +8.6 ± 11.9 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` *(parameterized)* | template | +12.1 ± 13.1 | — | reported |
| 20 rules: `drop-hit` − `idle` *(parameterized)* | template | +197 ± 10.4 | — | reported |
| 20 rules: `drop-hit` − `idle` | concatenated | +131 ± 20.1 | 204 | pass |
| 20 rules: `drop-hit` − `idle` *(parameterized)* | throwable | -4995 ± 113 | — | reported |
| 20 rules: `trim` − `idle` *(parameterized)* | template | +521 ± 81.0 | — | reported |
| 20 rules: `trim` − `idle` | concatenated | +201 ± 13.9 | 204 | **over** |
| 20 rules: `trim` − `idle` *(parameterized)* | throwable | +1250 ± 176 | — | reported |
| 20 type-bound trims: `trim-typed` − `idle` | template | +96.6 ± 12.7 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | +57.3 ± 12.4 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | +3124 ± 165 | 204 | **over** |
| disabled call, sync: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |
| disabled call, async-appender: `idle` − `baseline` | template | +1.2 ± 0.0 | 10.0 | pass |
| disabled call, async-loggers: `idle` − `baseline` | template | +1.2 ± 0.0 | 10.0 | pass |

### Concurrency (Log4j 2)

`idle` − `baseline` in synchronous mode, per message and appender shape. With an appender per thread the number should stay flat: a rise means the gate, the storm filter or the trim wrapper serializes threads that Log4j does not (the #24 shape). With one shared appender, `baseline` itself rises, because Log4j locks the appender.

| Appenders | Message | 1 thread | 4 threads | 8 threads | `baseline`, 1 → 8 threads (ns) |
|---|---|--:|--:|--:|--:|
| shared | template | +13.9 ± 10.5 | +25.5 ± 60.5 | +14.6 ± 77.5 | 553 ± 5.3 → 2527 ± 45.7 |
| shared | concatenated | +31.2 ± 12.1 | -7.2 ± 103 | -54.8 ± 99.9 | 421 ± 10.4 → 2409 ± 78.2 |
| per-thread | template | +1.4 ± 13.5 | -2.4 ± 61.5 | +13.9 ± 83.1 | 562 ± 11.5 → 961 ± 29.1 |
| per-thread | concatenated | +38.0 ± 3.8 | +0.9 ± 24.9 | +4.2 ± 22.7 | 426 ± 1.9 → 779 ± 15.2 |

### Log4j 2 gate, called directly

`GateFilter` with *n* non-matching rules effective on the logger, over the same call with no rules, checked against #21's curve. What the rules read decides the cost: `level-only` rules read no message, `plain` rules read a `String`, and `pattern` rules read a parameterized message, which is formatted once per call (Decision #32: that difference is reported as its own row, not budgeted).

| Check | Reads | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| `gate-n`, 1 rules, − `gate-empty` | level-only | +61.7 ± 0.3 | 85.0 | pass |
| `gate-n`, 5 rules, − `gate-empty` | level-only | +70.7 ± 0.6 | 140 | pass |
| `gate-n`, 20 rules, − `gate-empty` | level-only | +88.9 ± 2.0 | 204 | pass |
| `gate-n`, 100 rules, − `gate-empty` | level-only | +184 ± 1.9 | 283 | pass |
| `gate-n`, 1 rules, − `gate-empty` | plain | +70.2 ± 1.4 | 85.0 | pass |
| `gate-n`, 5 rules, − `gate-empty` | plain | +145 ± 1.1 | 140 | **over** |
| `gate-n`, 20 rules, − `gate-empty` | plain | +206 ± 1.7 | 204 | **over** |
| `gate-n`, 100 rules, − `gate-empty` | plain | +239 ± 1.9 | 283 | pass |
| reading a parameterized message, 1 rules: `pattern` − `plain` | pattern | +461 ± 3.0 | — | reported |
| reading a parameterized message, 5 rules: `pattern` − `plain` | pattern | +309 ± 8.4 | — | reported |
| reading a parameterized message, 20 rules: `pattern` − `plain` | pattern | +243 ± 3.4 | — | reported |
| reading a parameterized message, 100 rules: `pattern` − `plain` | pattern | +242 ± 3.0 | — | reported |
| disabled call through the filter, 20 non-matching rules (Decision #28) | any | 1.7 ± 0.0 | 10 | pass |

## Component benchmarks

Each hot-path piece alone, to cross-check the differences above.

| Benchmark | Score (ns/op) | Allocated (B/op) |
|---|--:|--:|
| `GateCurveBenchmark.gate (rules=1)` | 35.8 ± 0.2 | 120 |
| `GateCurveBenchmark.gate (rules=100)` | 203 ± 1.0 | 384 |
| `GateCurveBenchmark.gate (rules=20)` | 170 ± 7.1 | 379 |
| `GateCurveBenchmark.gate (rules=5)` | 80.5 ± 0.2 | 120 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=0)` | 4.3 ± 0.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=1)` | 66.0 ± 0.3 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=100)` | 188 ± 1.9 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=20)` | 93.2 ± 2.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=5)` | 75.0 ± 0.6 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=0)` | 4.3 ± 0.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=1)` | 536 ± 2.6 | 400 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=100)` | 486 ± 2.3 | 640 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=20)` | 454 ± 2.9 | 640 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=5)` | 459 ± 8.3 | 576 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=0)` | 5.2 ± 0.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=1)` | 75.4 ± 1.4 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=100)` | 244 ± 1.9 | 331 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=20)` | 211 ± 1.7 | 264 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=5)` | 150 ± 1.1 | 200 |
| `GateFilterDisabledComponentsBenchmark.disabled` | 1.7 ± 0.0 | 0 |
| `IdleComponentsBenchmark.gateEmpty` | 2.2 ± 0.0 | 0 |
| `IdleComponentsBenchmark.stormHash` | 158 ± 0.7 | 0 |
| `IdleComponentsBenchmark.stormNormalize` | 301 ± 2.9 | 248 |
| `IdleComponentsBenchmark.stormObserve` | 50.9 ± 0.3 | 24 |
| `IdleComponentsBenchmark.stormObserveConcatenated` | 363 ± 4.6 | 120 |
| `StormFilterComponentsBenchmark.stormOff` | 2.1 ± 0.0 | 0 |
| `StormFilterComponentsBenchmark.stormOffDelegate` | 1.0 ± 0.0 | 0 |
| `StormGateComponentsBenchmark.stormConcatenated` | 396 ± 5.7 | 200 |
| `StormGateComponentsBenchmark.stormTemplate` | 106 ± 2.0 | 136 |
| `TopComponentsBenchmark.topRecord` | 18.8 ± 0.3 | 0 |
| `TopComponentsBenchmark.traceBytes` | 4154 ± 13.2 | 16960 |
| `TopComponentsBenchmark.traceShare` | 150 ± 5.7 | 96 |
| `TrimQueryComponentsBenchmark.trimQuery` | 46.4 ± 0.3 | 24 |

## Machine

```
label:            log4j2
quick:            false
date:             2026-11-10T21:58:45-06:00
computer:         fedora
cpu:              AMD Ryzen 5 3400G with Radeon Vega Graphics
cores/threads:    4 / 8
memory (GB):      60.8
os:               Fedora Linux 44 (Workstation Edition), kernel 7.3.3-200.fc44.x86_64
clocksource:      tsc
governor:         performance
boost:            0
git commit:       725bb017051ad20902c5644d2bb6018a17d01891
git branch:       develop
nanoTime (ns):    24.8
java:
    openjdk version "21.0.12" 2026-07-21 LTS
    OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.12+8 (build 21.0.12+8-LTS, mixed mode, sharing)
```
