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
| `rule` | template | 1 | shared | +0.1 % ± 0.7 % | +1.0 ± 4.9 | 695 ± 2.0 | 694 ± 4.4 | 1205 |
| `rule+storm` | template | 1 | shared | +14.9 % ± 1.3 % | +103 ± 9.1 | 797 ± 7.9 | 694 ± 4.4 | 1248 |
| `rule+storm-off` | template | 1 | shared | -1.8 % ± 0.8 % | -12.6 ± 5.7 | 681 ± 3.7 | 694 ± 4.4 | 1168 |
| `rule+storm+trim` | template | 1 | shared | +13.7 % ± 1.4 % | +95.0 ± 9.8 | 789 ± 8.8 | 694 ± 4.4 | 1237 |
| `idle+storm` | template | 1 | shared | +21.6 % ± 3.3 % | +150 ± 23.0 | 843 ± 22.6 | 694 ± 4.4 | 1339 |
| `idle` | template | 1 | shared | +4.3 % ± 0.9 % | +29.9 ± 6.5 | 723 ± 4.7 | 694 ± 4.4 | 1304 |
| `drop-miss` | template | 1 | shared | +9.2 % ± 1.7 % | +63.6 ± 11.9 | 757 ± 11.0 | 694 ± 4.4 | 1333 |
| `drop-hit` | template | 1 | shared | +56.6 % ± 19.5 % | +393 ± 135 | 1086 ± 135 | 694 ± 4.4 | 633 |
| `trim` | template | 1 | shared | +37.9 % ± 1.8 % | +263 ± 12.7 | 957 ± 11.9 | 694 ± 4.4 | 1717 |
| `trim-typed` | template | 1 | shared | +14.9 % ± 1.3 % | +104 ± 9.2 | 797 ± 8.0 | 694 ± 4.4 | 1307 |
| `rule` | concatenated | 1 | shared | +2.0 % ± 0.9 % | +14.3 ± 6.8 | 739 ± 6.4 | 724 ± 2.4 | 1320 |
| `rule+storm` | concatenated | 1 | shared | +50.2 % ± 2.1 % | +364 ± 15.0 | 1088 ± 14.8 | 724 ± 2.4 | 1307 |
| `rule+storm-off` | concatenated | 1 | shared | +1.7 % ± 1.3 % | +12.5 ± 9.7 | 737 ± 9.4 | 724 ± 2.4 | 1301 |
| `rule+storm+trim` | concatenated | 1 | shared | +53.7 % ± 2.4 % | +389 ± 17.1 | 1114 ± 17.0 | 724 ± 2.4 | 1325 |
| `idle+storm` | concatenated | 1 | shared | +58.6 % ± 2.0 % | +425 ± 14.2 | 1149 ± 14.0 | 724 ± 2.4 | 1445 |
| `idle` | concatenated | 1 | shared | +7.0 % ± 1.5 % | +50.8 ± 10.9 | 775 ± 10.6 | 724 ± 2.4 | 1403 |
| `drop-miss` | concatenated | 1 | shared | +8.5 % ± 2.1 % | +61.8 ± 15.4 | 786 ± 15.2 | 724 ± 2.4 | 1421 |
| `drop-hit` | concatenated | 1 | shared | +66.7 % ± 22.0 % | +483 ± 160 | 1208 ± 160 | 724 ± 2.4 | 738 |
| `trim` | concatenated | 1 | shared | +42.7 % ± 3.9 % | +309 ± 28.4 | 1033 ± 28.3 | 724 ± 2.4 | 1784 |
| `trim-typed` | concatenated | 1 | shared | +15.3 % ± 1.3 % | +111 ± 9.1 | 835 ± 8.8 | 724 ± 2.4 | 1384 |
| `rule` | throwable | 1 | shared | +1.6 % ± 1.1 % | +66.7 ± 44.2 | 4108 ± 39.7 | 4041 ± 19.4 | 16917 |
| `rule+storm` | throwable | 1 | shared | +3.7 % ± 0.9 % | +150 ± 36.0 | 4190 ± 30.3 | 4041 ± 19.4 | 16923 |
| `rule+storm-off` | throwable | 1 | shared | +0.6 % ± 1.0 % | +22.9 ± 39.9 | 4064 ± 34.9 | 4041 ± 19.4 | 16925 |
| `rule+storm+trim` | throwable | 1 | shared | +3.2 % ± 1.1 % | +131 ± 45.9 | 4171 ± 41.6 | 4041 ± 19.4 | 16949 |
| `idle+storm` | throwable | 1 | shared | +10.8 % ± 0.6 % | +437 ± 25.9 | 4478 ± 17.2 | 4041 ± 19.4 | 18688 |
| `idle` | throwable | 1 | shared | +8.8 % ± 1.7 % | +357 ± 67.9 | 4398 ± 65.1 | 4041 ± 19.4 | 18645 |
| `drop-miss` | throwable | 1 | shared | +10.0 % ± 0.8 % | +404 ± 33.8 | 4445 ± 27.7 | 4041 ± 19.4 | 18664 |
| `drop-hit` | throwable | 1 | shared | -72.6 % ± 3.2 % | -2934 ± 127 | 1107 ± 126 | 4041 ± 19.4 | 633 |
| `trim` | throwable | 1 | shared | -15.7 % ± 8.8 % | -634 ± 356 | 3406 ± 356 | 4041 ± 19.4 | 8847 |
| `trim-typed` | throwable | 1 | shared | -20.3 % ± 8.4 % | -819 ± 339 | 3222 ± 339 | 4041 ± 19.4 | 8791 |
| `rule+storm` | template | 4 | shared | -0.4 % ± 3.1 % | -13.2 ± 92.0 | 2941 ± 71.2 | 2954 ± 58.1 | 1276 |
| `idle` | template | 4 | shared | -3.1 % ± 3.0 % | -91.8 ± 88.0 | 2862 ± 66.0 | 2954 ± 58.1 | 1353 |
| `rule+storm` | concatenated | 4 | shared | -0.1 % ± 9.4 % | -2.4 ± 298 | 3158 ± 264 | 3160 ± 138 | 1372 |
| `idle` | concatenated | 4 | shared | -6.7 % ± 5.4 % | -212 ± 169 | 2948 ± 98.8 | 3160 ± 138 | 1468 |
| `rule+storm` | template | 8 | shared | +3.0 % ± 1.8 % | +175 ± 106 | 6071 ± 67.7 | 5896 ± 81.6 | 1257 |
| `idle` | template | 8 | shared | -2.3 % ± 2.2 % | -136 ± 127 | 5759 ± 97.9 | 5896 ± 81.6 | 1353 |
| `rule+storm` | concatenated | 8 | shared | +26.6 % ± 4.6 % | +1541 ± 258 | 7335 ± 141 | 5794 ± 216 | 1374 |
| `idle` | concatenated | 8 | shared | -3.9 % ± 4.3 % | -229 ± 247 | 5565 ± 122 | 5794 ± 216 | 1430 |
| `idle` | template | 1 | per-thread | +7.4 % ± 1.9 % | +51.8 ± 13.0 | 756 ± 7.4 | 704 ± 10.7 | 1344 |
| `rule+storm` | template | 4 | per-thread | +8.3 % ± 2.6 % | +62.7 ± 19.4 | 820 ± 17.7 | 757 ± 7.9 | 1229 |
| `idle` | template | 4 | per-thread | +18.5 % ± 1.5 % | +140 ± 11.5 | 897 ± 8.3 | 757 ± 7.9 | 1352 |
| `rule+storm` | concatenated | 4 | per-thread | +57.8 % ± 4.0 % | +453 ± 30.2 | 1236 ± 26.4 | 784 ± 14.7 | 1344 |
| `idle` | concatenated | 4 | per-thread | +25.7 % ± 4.5 % | +201 ± 35.1 | 985 ± 31.9 | 784 ± 14.7 | 1437 |
| `rule+storm` | template | 8 | per-thread | +11.3 % ± 1.9 % | +128 ± 21.1 | 1268 ± 17.0 | 1140 ± 12.6 | 1219 |
| `idle` | template | 8 | per-thread | +26.0 % ± 2.2 % | +297 ± 25.4 | 1437 ± 22.1 | 1140 ± 12.6 | 1296 |
| `rule+storm` | concatenated | 8 | per-thread | +42.7 % ± 3.8 % | +529 ± 44.9 | 1767 ± 29.0 | 1238 ± 34.3 | 1326 |
| `idle` | concatenated | 8 | per-thread | +21.0 % ± 4.0 % | +260 ± 49.5 | 1498 ± 35.7 | 1238 ± 34.3 | 1429 |

## Budgets

Single thread, one shared handler. A check passes when the difference *plus* its error is within the budget (doc/specs/overhead-benchmarks.md Decisions #8, #13 and #21). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1) for *n* rules effective on the logger.

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| rule filter: `rule` − `baseline` | template | +1.0 ± 4.9 | 50.0 | pass |
| rule filter: `rule` − `baseline` | concatenated | +14.3 ± 6.8 | 50.0 | pass |
| rule filter: `rule` − `baseline` | throwable | +66.7 ± 44.2 | 50.0 | **over** |
| storm filter: `rule+storm` − `rule` | template | +102 ± 8.2 | 100 | **over** |
| storm filter: `rule+storm` − `rule` | concatenated | +349 ± 16.1 | 308 | **over** |
| storm filter, disabled: `storm-off` − `storm-off-delegate` (component) | any | +1.2 ± 0.0 | 10.0 | pass |
| storm filter, disabled: `rule+storm-off` − `rule` | template | -13.6 ± 4.2 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | concatenated | -1.9 ± 11.4 | — | reported |
| storm filter, disabled: `rule+storm-off` − `rule` | throwable | -43.7 ± 52.9 | — | reported |
| no rule can match: `drop-miss` − `idle` | template | +33.7 ± 12.0 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | concatenated | +11.0 ± 18.6 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | throwable | +47.3 ± 70.8 | 50.0 | **over** |
| 20 rules: `drop-hit` − `idle` | template | +363 ± 135 | 204 | **over** |
| 20 rules: `drop-hit` − `idle` | concatenated | +432 ± 160 | 204 | **over** |
| 20 rules: `drop-hit` − `idle` | throwable | -3291 ± 142 | 204 | pass |
| 20 rules: `trim` − `idle` | template | +233 ± 12.8 | 204 | **over** |
| 20 rules: `trim` − `idle` | concatenated | +258 ± 30.2 | 204 | **over** |
| 20 rules: `trim` − `idle` | throwable | -991 ± 362 | 204 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | template | +73.8 ± 9.3 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | +60.1 ± 13.8 | 50.0 | **over** |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | -1176 ± 345 | 204 | pass |
| gate alone, 1 rules: `gate-n` − `gate-empty` | template | +33.8 ± 0.1 | 85.0 | pass |
| gate alone, 5 rules: `gate-n` − `gate-empty` | template | +77.8 ± 0.1 | 140 | pass |
| gate alone, 20 rules: `gate-n` − `gate-empty` | template | +161 ± 14.3 | 204 | pass |
| gate alone, 100 rules: `gate-n` − `gate-empty` | template | +204 ± 2.3 | 283 | pass |

## What a match saves

One `INFO` call through a real file handler (autoflush on), single thread: the whole agent with 20 rules, the last of which matches, against no LogAperture through the same handler. `throwable` carries a 100-frame exception. A negative change means the matched record cost less than with no agent at all. Reported, not budgeted (Decision #22).

| Scenario | Message | Change | Change (ns) | Score (ns/op) | No agent (ns/op) |
|---|---|--:|--:|--:|--:|
| `drop-hit` | template | -51.4 % ± 6.1 % | -1156 ± 138 | 1093 ± 137 | 2249 ± 15.3 |
| `trim` | template | +13.6 % ± 1.1 % | +307 ± 23.9 | 2556 ± 18.3 | 2249 ± 15.3 |
| `drop-hit` | throwable | -95.5 % ± 2.8 % | -23083 ± 493 | 1092 ± 144 | 24175 ± 471 |
| `trim` | throwable | -76.4 % ± 3.1 % | -18460 ± 646 | 5716 ± 442 | 24175 ± 471 |

## Decision rules

- **#23** (`top` renders a throwable's stack trace a second time): the `top` layer costs +307 ± 45.0 ns, +7.6 % ± 1.1 % of the baseline with a throwable; the threshold is 25 %. Stays out of 1.0.
- **#24** (`top`'s one lock across independent handlers): at 8 threads on separate handlers, `idle` takes 1.90 ± 0.03× its single-thread time and `baseline` 1.62 ± 0.03×; the rule is `idle` over 1.5× while `baseline` is not. Stays out of 1.0.
- **#18** (handler lookup by name): not on the logging path, so no benchmark; moves to 1.x.

## Log4j 2

**Log4j 2 version: 2.25.5**. The published run uses the latest tested 2.25 for every mode, and 2.20.0, the adapter's floor, for `idle` and `disabled` in a separate results folder (Decision #26); a trim regression of #333's kind shows on 2.25 and not on 2.20, so a number is only as good as the version printed here.

The Log4j 2 adapter's gate on a real `LoggerContext`, one `INFO` call, in each of Log4j's three modes. Overhead is against `baseline` (bare Log4j 2) of the same mode, message, thread count and appender shape. There is no `top` layer: byte counting is not built for Log4j 2. `template` is a parameterized message (`{}` with three arguments), so a rule that reads it formats it first; `concatenated` is a plain `String`; `throwable` is `template` plus a 20-frame exception. Budgets are checked in synchronous mode only; the two asynchronous modes are reported, because a hot loop into a queue measures the queue (Decision #27). A rule that reads a parameterized message pays for formatting it, which the 1.0 curve never included, so those rows are reported and the budgeted rule rows use the plain message (Decision #32).

### Overhead per log call (Log4j 2)

| Mode | Scenario | Message | Threads | Appenders | Overhead | Added (ns) | Score (ns/op) | Baseline (ns/op) | Allocated (B/op) |
|---|---|---|--:|---|--:|--:|--:|--:|--:|
| sync | `gate` | template | 1 | shared | +2.1 % ± 2.3 % | +11.5 ± 12.8 | 562 ± 10.9 | 551 ± 6.8 | 24 |
| sync | `gate+storm` | template | 1 | shared | +23.2 % ± 2.5 % | +128 ± 13.5 | 678 ± 11.7 | 551 ± 6.8 | 152 |
| sync | `gate+storm-off` | template | 1 | shared | +3.7 % ± 2.4 % | +20.2 ± 13.4 | 571 ± 11.6 | 551 ± 6.8 | 24 |
| sync | `gate+storm+trim-wrap` | template | 1 | shared | +21.4 % ± 1.7 % | +118 ± 9.1 | 668 ± 6.0 | 551 ± 6.8 | 160 |
| sync | `idle` | template | 1 | shared | +3.4 % ± 1.8 % | +18.7 ± 9.9 | 570 ± 7.1 | 551 ± 6.8 | 24 |
| sync | `drop-miss` | template | 1 | shared | +4.8 % ± 2.6 % | +26.4 ± 14.3 | 577 ± 12.6 | 551 ± 6.8 | 24 |
| sync | `drop-hit` | template | 1 | shared | +39.3 % ± 1.5 % | +217 ± 7.9 | 767 ± 3.9 | 551 ± 6.8 | 904 |
| sync | `trim` | template | 1 | shared | +97.3 % ± 8.3 % | +536 ± 45.1 | 1086 ± 44.6 | 551 ± 6.8 | 872 |
| sync | `trim-typed` | template | 1 | shared | +5.9 % ± 1.8 % | +32.5 ± 10.2 | 583 ± 7.5 | 551 ± 6.8 | 16 |
| sync | `gate` | concatenated | 1 | shared | -1.9 % ± 1.3 % | -8.5 ± 5.7 | 431 ± 2.1 | 440 ± 5.3 | 120 |
| sync | `gate+storm` | concatenated | 1 | shared | +81.9 % ± 3.2 % | +360 ± 13.5 | 799 ± 12.4 | 440 ± 5.3 | 208 |
| sync | `gate+storm-off` | concatenated | 1 | shared | -3.3 % ± 4.0 % | -14.6 ± 17.4 | 425 ± 16.6 | 440 ± 5.3 | 112 |
| sync | `gate+storm+trim-wrap` | concatenated | 1 | shared | +116.8 % ± 3.9 % | +514 ± 15.8 | 953 ± 14.9 | 440 ± 5.3 | 216 |
| sync | `idle` | concatenated | 1 | shared | +3.7 % ± 1.8 % | +16.5 ± 8.1 | 456 ± 6.1 | 440 ± 5.3 | 120 |
| sync | `drop-miss` | concatenated | 1 | shared | +3.2 % ± 2.8 % | +14.1 ± 12.5 | 454 ± 11.3 | 440 ± 5.3 | 112 |
| sync | `drop-hit` | concatenated | 1 | shared | +36.9 % ± 3.2 % | +162 ± 14.1 | 602 ± 13.0 | 440 ± 5.3 | 608 |
| sync | `trim` | concatenated | 1 | shared | +48.4 % ± 1.8 % | +213 ± 7.5 | 653 ± 5.3 | 440 ± 5.3 | 568 |
| sync | `trim-typed` | concatenated | 1 | shared | +4.9 % ± 2.1 % | +21.7 ± 9.3 | 461 ± 7.6 | 440 ± 5.3 | 112 |
| sync | `gate` | throwable | 1 | shared | -0.3 % ± 1.8 % | -16.2 ± 104 | 5872 ± 78.6 | 5888 ± 68.7 | 15760 |
| sync | `gate+storm` | throwable | 1 | shared | +2.6 % ± 1.5 % | +151 ± 90.6 | 6039 ± 59.1 | 5888 ± 68.7 | 16208 |
| sync | `gate+storm-off` | throwable | 1 | shared | -0.3 % ± 1.3 % | -15.7 ± 78.1 | 5872 ± 37.1 | 5888 ± 68.7 | 16336 |
| sync | `gate+storm+trim-wrap` | throwable | 1 | shared | +3.0 % ± 1.2 % | +179 ± 73.0 | 6067 ± 24.8 | 5888 ± 68.7 | 16504 |
| sync | `idle` | throwable | 1 | shared | -2.9 % ± 1.3 % | -172 ± 76.3 | 5717 ± 33.3 | 5888 ± 68.7 | 15760 |
| sync | `drop-miss` | throwable | 1 | shared | -1.6 % ± 2.4 % | -92.8 ± 143 | 5795 ± 125 | 5888 ± 68.7 | 16040 |
| sync | `drop-hit` | throwable | 1 | shared | -85.4 % ± 1.5 % | -5031 ± 68.9 | 857 ± 5.3 | 5888 ± 68.7 | 952 |
| sync | `trim` | throwable | 1 | shared | +19.7 % ± 1.8 % | +1162 ± 106 | 7051 ± 80.5 | 5888 ± 68.7 | 5952 |
| sync | `trim-typed` | throwable | 1 | shared | +12.7 % ± 2.8 % | +748 ± 166 | 6637 ± 151 | 5888 ± 68.7 | 5496 |
| async-appender | `gate` | template | 1 | shared | -0.1 % ± 1.7 % | -0.8 ± 12.6 | 736 ± 6.1 | 737 ± 11.0 | 332 |
| async-appender | `gate+storm` | template | 1 | shared | +15.5 % ± 6.3 % | +114 ± 46.7 | 851 ± 45.4 | 737 ± 11.0 | 432 |
| async-appender | `gate+storm-off` | template | 1 | shared | -1.1 % ± 3.7 % | -8.1 ± 27.4 | 728 ± 25.0 | 737 ± 11.0 | 301 |
| async-appender | `gate+storm+trim-wrap` | template | 1 | shared | +18.2 % ± 9.8 % | +134 ± 72.1 | 870 ± 71.3 | 737 ± 11.0 | 432 |
| async-appender | `idle` | template | 1 | shared | -2.9 % ± 3.4 % | -21.7 ± 24.9 | 715 ± 22.4 | 737 ± 11.0 | 289 |
| async-appender | `drop-miss` | template | 1 | shared | -7.1 % ± 2.4 % | -52.4 ± 17.5 | 684 ± 13.6 | 737 ± 11.0 | 299 |
| async-appender | `drop-hit` | template | 1 | shared | +5.5 % ± 2.6 % | +40.8 ± 19.2 | 777 ± 15.7 | 737 ± 11.0 | 904 |
| async-appender | `trim` | template | 1 | shared | +106.6 % ± 7.2 % | +785 ± 51.7 | 1522 ± 50.5 | 737 ± 11.0 | 1136 |
| async-appender | `trim-typed` | template | 1 | shared | -4.3 % ± 3.7 % | -31.9 ± 27.6 | 705 ± 25.3 | 737 ± 11.0 | 300 |
| async-appender | `gate` | concatenated | 1 | shared | -14.6 % ± 6.9 % | -70.1 ± 33.1 | 410 ± 18.0 | 480 ± 27.8 | 392 |
| async-appender | `gate+storm` | concatenated | 1 | shared | +108.8 % ± 9.2 % | +523 ± 32.3 | 1003 ± 16.5 | 480 ± 27.8 | 480 |
| async-appender | `gate+storm-off` | concatenated | 1 | shared | -7.5 % ± 7.7 % | -35.9 ± 36.8 | 445 ± 24.1 | 480 ± 27.8 | 432 |
| async-appender | `gate+storm+trim-wrap` | concatenated | 1 | shared | +150.4 % ± 12.1 % | +722 ± 40.6 | 1203 ± 29.6 | 480 ± 27.8 | 478 |
| async-appender | `idle` | concatenated | 1 | shared | -2.5 % ± 8.2 % | -12.2 ± 39.5 | 468 ± 28.1 | 480 ± 27.8 | 392 |
| async-appender | `drop-miss` | concatenated | 1 | shared | -5.8 % ± 9.1 % | -28.1 ± 43.8 | 452 ± 33.9 | 480 ± 27.8 | 372 |
| async-appender | `drop-hit` | concatenated | 1 | shared | +25.4 % ± 6.5 % | +122 ± 30.3 | 602 ± 12.0 | 480 ± 27.8 | 608 |
| async-appender | `trim` | concatenated | 1 | shared | +64.9 % ± 8.6 % | +312 ± 37.3 | 792 ± 24.9 | 480 ± 27.8 | 847 |
| async-appender | `trim-typed` | concatenated | 1 | shared | -20.1 % ± 9.7 % | -96.5 ± 46.3 | 384 ± 37.1 | 480 ± 27.8 | 355 |
| async-appender | `gate` | throwable | 1 | shared | -0.3 % ± 2.8 % | -18.5 ± 202 | 7176 ± 141 | 7194 ± 144 | 17438 |
| async-appender | `gate+storm` | throwable | 1 | shared | +3.9 % ± 2.2 % | +283 ± 160 | 7477 ± 68.8 | 7194 ± 144 | 17635 |
| async-appender | `gate+storm-off` | throwable | 1 | shared | +0.4 % ± 2.3 % | +27.6 ± 165 | 7222 ± 79.0 | 7194 ± 144 | 17102 |
| async-appender | `gate+storm+trim-wrap` | throwable | 1 | shared | +6.1 % ± 3.9 % | +438 ± 280 | 7632 ± 240 | 7194 ± 144 | 17320 |
| async-appender | `idle` | throwable | 1 | shared | +1.7 % ± 2.5 % | +126 ± 182 | 7320 ± 110 | 7194 ± 144 | 17437 |
| async-appender | `drop-miss` | throwable | 1 | shared | +2.7 % ± 2.4 % | +191 ± 172 | 7386 ± 93.4 | 7194 ± 144 | 16804 |
| async-appender | `drop-hit` | throwable | 1 | shared | -88.1 % ± 2.7 % | -6337 ± 145 | 857 ± 4.6 | 7194 ± 144 | 952 |
| async-appender | `trim` | throwable | 1 | shared | +10.1 % ± 2.4 % | +729 ± 170 | 7923 ± 90.0 | 7194 ± 144 | 6234 |
| async-appender | `trim-typed` | throwable | 1 | shared | +1.8 % ± 3.2 % | +129 ± 227 | 7323 ± 175 | 7194 ± 144 | 5610 |
| async-loggers | `gate` | template | 1 | shared | +3.3 % ± 1.0 % | +15.4 ± 4.6 | 478 ± 2.9 | 463 ± 3.6 | 0 |
| async-loggers | `gate+storm` | template | 1 | shared | +23.3 % ± 2.5 % | +108 ± 11.6 | 571 ± 11.0 | 463 ± 3.6 | 136 |
| async-loggers | `gate+storm-off` | template | 1 | shared | +5.9 % ± 3.6 % | +27.5 ± 16.5 | 490 ± 16.0 | 463 ± 3.6 | 0 |
| async-loggers | `gate+storm+trim-wrap` | template | 1 | shared | +23.2 % ± 1.2 % | +107 ± 5.7 | 570 ± 4.4 | 463 ± 3.6 | 136 |
| async-loggers | `idle` | template | 1 | shared | +2.1 % ± 0.9 % | +9.8 ± 4.1 | 473 ± 1.9 | 463 ± 3.6 | 0 |
| async-loggers | `drop-miss` | template | 1 | shared | +16.5 % ± 0.8 % | +76.4 ± 3.7 | 539 ± 0.9 | 463 ± 3.6 | 0 |
| async-loggers | `drop-hit` | template | 1 | shared | +65.6 % ± 1.2 % | +304 ± 4.9 | 767 ± 3.2 | 463 ± 3.6 | 904 |
| async-loggers | `trim` | template | 1 | shared | +196.9 % ± 4.1 % | +912 ± 17.7 | 1375 ± 17.3 | 463 ± 3.6 | 848 |
| async-loggers | `trim-typed` | template | 1 | shared | +15.6 % ± 1.4 % | +72.4 ± 6.5 | 535 ± 5.4 | 463 ± 3.6 | 0 |
| async-loggers | `gate` | concatenated | 1 | shared | -0.1 % ± 4.0 % | -0.3 ± 11.8 | 298 ± 9.6 | 298 ± 6.9 | 96 |
| async-loggers | `gate+storm` | concatenated | 1 | shared | +217.3 % ± 5.9 % | +647 ± 9.1 | 945 ± 6.0 | 298 ± 6.9 | 200 |
| async-loggers | `gate+storm-off` | concatenated | 1 | shared | -0.2 % ± 3.8 % | -0.5 ± 11.5 | 297 ± 9.1 | 298 ± 6.9 | 96 |
| async-loggers | `gate+storm+trim-wrap` | concatenated | 1 | shared | +277.3 % ± 8.6 % | +826 ± 17.1 | 1124 ± 15.7 | 298 ± 6.9 | 200 |
| async-loggers | `idle` | concatenated | 1 | shared | +8.0 % ± 4.3 % | +23.8 ± 12.9 | 322 ± 10.9 | 298 ± 6.9 | 96 |
| async-loggers | `drop-miss` | concatenated | 1 | shared | +3.3 % ± 2.5 % | +9.8 ± 7.6 | 308 ± 3.2 | 298 ± 6.9 | 96 |
| async-loggers | `drop-hit` | concatenated | 1 | shared | +98.5 % ± 4.8 % | +293 ± 12.4 | 591 ± 10.3 | 298 ± 6.9 | 608 |
| async-loggers | `trim` | concatenated | 1 | shared | +117.9 % ± 7.1 % | +351 ± 19.5 | 649 ± 18.3 | 298 ± 6.9 | 568 |
| async-loggers | `trim-typed` | concatenated | 1 | shared | +3.1 % ± 2.4 % | +9.3 ± 7.0 | 307 ± 1.4 | 298 ± 6.9 | 96 |
| async-loggers | `gate` | throwable | 1 | shared | +0.4 % ± 1.9 % | +25.2 ± 106 | 5758 ± 86.8 | 5733 ± 61.6 | 15757 |
| async-loggers | `gate+storm` | throwable | 1 | shared | -0.6 % ± 1.2 % | -34.9 ± 67.2 | 5698 ± 26.9 | 5733 ± 61.6 | 15942 |
| async-loggers | `gate+storm-off` | throwable | 1 | shared | +0.9 % ± 2.4 % | +52.6 ± 135 | 5785 ± 120 | 5733 ± 61.6 | 15756 |
| async-loggers | `gate+storm+trim-wrap` | throwable | 1 | shared | +4.5 % ± 2.2 % | +259 ± 124 | 5992 ± 107 | 5733 ± 61.6 | 15926 |
| async-loggers | `idle` | throwable | 1 | shared | +2.8 % ± 2.8 % | +160 ± 159 | 5892 ± 147 | 5733 ± 61.6 | 15759 |
| async-loggers | `drop-miss` | throwable | 1 | shared | +4.4 % ± 2.0 % | +255 ± 112 | 5988 ± 94.1 | 5733 ± 61.6 | 16040 |
| async-loggers | `drop-hit` | throwable | 1 | shared | -85.0 % ± 1.4 % | -4875 ± 61.6 | 858 ± 2.3 | 5733 ± 61.6 | 952 |
| async-loggers | `trim` | throwable | 1 | shared | -20.4 % ± 1.5 % | -1168 ± 84.5 | 4565 ± 57.8 | 5733 ± 61.6 | 5894 |
| async-loggers | `trim-typed` | throwable | 1 | shared | -17.8 % ± 1.8 % | -1021 ± 102 | 4711 ± 81.8 | 5733 ± 61.6 | 5582 |
| sync | `gate+storm` | template | 4 | shared | +4.3 % ± 7.8 % | +37.6 ± 68.0 | 906 ± 38.2 | 869 ± 56.3 | 152 |
| sync | `idle` | template | 4 | shared | +9.5 % ± 9.2 % | +82.5 ± 79.9 | 951 ± 56.8 | 869 ± 56.3 | 16 |
| sync | `gate+storm` | concatenated | 4 | shared | +20.6 % ± 12.9 % | +189 ± 116 | 1106 ± 49.1 | 917 ± 106 | 224 |
| sync | `idle` | concatenated | 4 | shared | +8.9 % ± 14.4 % | +81.5 ± 132 | 998 ± 78.3 | 917 ± 106 | 112 |
| sync | `gate+storm` | template | 8 | shared | +5.7 % ± 4.1 % | +142 ± 101 | 2617 ± 57.8 | 2475 ± 83.2 | 160 |
| sync | `idle` | template | 8 | shared | +4.0 % ± 4.1 % | +98.4 ± 102 | 2573 ± 58.6 | 2475 ± 83.2 | 8 |
| sync | `gate+storm` | concatenated | 8 | shared | +9.5 % ± 3.2 % | +223 ± 75.1 | 2566 ± 56.6 | 2342 ± 49.4 | 224 |
| sync | `idle` | concatenated | 8 | shared | +5.3 % ± 4.2 % | +123 ± 98.8 | 2465 ± 85.6 | 2342 ± 49.4 | 120 |
| sync | `idle` | template | 1 | per-thread | +2.0 % ± 1.7 % | +11.3 ± 9.3 | 562 ± 4.7 | 551 ± 8.1 | 24 |
| sync | `idle` | concatenated | 1 | per-thread | +5.8 % ± 2.9 % | +25.2 ± 12.6 | 457 ± 9.4 | 432 ± 8.5 | 120 |
| sync | `gate+storm` | template | 4 | per-thread | +12.4 % ± 8.6 % | +83.7 ± 57.6 | 756 ± 44.3 | 673 ± 36.9 | 160 |
| sync | `idle` | template | 4 | per-thread | -4.8 % ± 8.4 % | -32.3 ± 56.7 | 640 ± 43.0 | 673 ± 36.9 | 8 |
| sync | `gate+storm` | concatenated | 4 | per-thread | +108.3 % ± 5.4 % | +467 ± 22.8 | 898 ± 22.3 | 431 ± 4.7 | 208 |
| sync | `idle` | concatenated | 4 | per-thread | +12.5 % ± 6.0 % | +53.9 ± 25.8 | 485 ± 25.4 | 431 ± 4.7 | 120 |
| sync | `gate+storm` | template | 8 | per-thread | +30.7 % ± 7.1 % | +313 ± 70.8 | 1332 ± 46.2 | 1019 ± 53.7 | 136 |
| sync | `idle` | template | 8 | per-thread | -0.9 % ± 6.5 % | -9.3 ± 66.1 | 1010 ± 38.5 | 1019 ± 53.7 | 16 |
| sync | `gate+storm` | concatenated | 8 | per-thread | +84.3 % ± 5.4 % | +623 ± 37.6 | 1363 ± 33.7 | 739 ± 16.7 | 209 |
| sync | `idle` | concatenated | 8 | per-thread | +5.6 % ± 4.0 % | +41.5 ± 29.6 | 781 ± 24.4 | 739 ± 16.7 | 112 |

### Log4j 2 budgets

Synchronous mode, single thread, one shared appender. A check passes when the difference *plus* its error is within the budget (Decisions #8, #13, #21, #23, #28, #32). Rule budgets follow #21's curve, 50 ns + 35 ns × log₂(n + 1). Rows marked *parameterized* include the cost of formatting the message for the rules and are reported, not budgeted (Decision #32).

| Check | Message | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| gate: `gate` − `baseline` | template | +11.5 ± 12.8 | 50.0 | pass |
| gate: `gate` − `baseline` | concatenated | -8.5 ± 5.7 | 50.0 | pass |
| gate: `gate` − `baseline` | throwable | -16.2 ± 104 | 50.0 | **over** |
| storm filter: `gate+storm` − `gate` | template | +116 ± 15.9 | 100 | **over** |
| storm filter: `gate+storm` − `gate` | concatenated | +368 ± 12.5 | 308 | **over** |
| storm filter, disabled: `gate+storm-off` − `gate` | template | +8.7 ± 15.9 | — | reported |
| storm filter, disabled: `gate+storm-off` − `gate` | concatenated | -6.1 ± 16.7 | — | reported |
| storm filter, disabled: `gate+storm-off` − `gate` | throwable | +0.5 ± 86.9 | — | reported |
| idle (shipped default): `idle` − `baseline` | template | +18.7 ± 9.9 | 50.0 | pass |
| idle (shipped default): `idle` − `baseline` | concatenated | +16.5 ± 8.1 | 50.0 | pass |
| idle (shipped default): `idle` − `baseline` | throwable | -172 ± 76.3 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` | concatenated | -2.3 ± 12.8 | 50.0 | pass |
| no rule can match: `drop-miss` − `idle` *(parameterized)* | template | +7.7 ± 14.5 | — | reported |
| 20 rules: `drop-hit` − `idle` *(parameterized)* | template | +198 ± 8.1 | — | reported |
| 20 rules: `drop-hit` − `idle` | concatenated | +146 ± 14.4 | 204 | pass |
| 20 rules: `drop-hit` − `idle` *(parameterized)* | throwable | -4860 ± 33.7 | — | reported |
| 20 rules: `trim` − `idle` *(parameterized)* | template | +517 ± 45.1 | — | reported |
| 20 rules: `trim` − `idle` | concatenated | +196 ± 8.1 | 204 | **over** |
| 20 rules: `trim` − `idle` *(parameterized)* | throwable | +1334 ± 87.1 | — | reported |
| 20 type-bound trims: `trim-typed` − `idle` | template | +13.8 ± 10.4 | 50.0 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | concatenated | +5.3 ± 9.7 | 50.0 | pass |
| 20 type-bound trims: `trim-typed` − `idle` | throwable | +920 ± 155 | 204 | **over** |
| disabled call, sync: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |
| disabled call, async-appender: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |
| disabled call, async-loggers: `idle` − `baseline` | template | +1.1 ± 0.0 | 10.0 | pass |

### Concurrency (Log4j 2)

`idle` − `baseline` in synchronous mode, per message and appender shape. With an appender per thread the number should stay flat: a rise means the gate, the storm filter or the trim wrapper serializes threads that Log4j does not (the #24 shape). With one shared appender, `baseline` itself rises, because Log4j locks the appender.

| Appenders | Message | 1 thread | 4 threads | 8 threads | `baseline`, 1 → 8 threads (ns) |
|---|---|--:|--:|--:|--:|
| shared | template | +18.7 ± 9.9 | +82.5 ± 79.9 | +98.4 ± 102 | 551 ± 6.8 → 2475 ± 83.2 |
| shared | concatenated | +16.5 ± 8.1 | +81.5 ± 132 | +123 ± 98.8 | 440 ± 5.3 → 2342 ± 49.4 |
| per-thread | template | +11.3 ± 9.3 | -32.3 ± 56.7 | -9.3 ± 66.1 | 551 ± 8.1 → 1019 ± 53.7 |
| per-thread | concatenated | +25.2 ± 12.6 | +53.9 ± 25.8 | +41.5 ± 29.6 | 432 ± 8.5 → 739 ± 16.7 |

### Log4j 2 gate, called directly

`GateFilter` with *n* non-matching rules effective on the logger, over the same call with no rules, checked against #21's curve. What the rules read decides the cost: `level-only` rules read no message, `plain` rules read a `String`, and `pattern` rules read a parameterized message, which is formatted once per call (Decision #32: that difference is reported as its own row, not budgeted).

| Check | Reads | Measured (ns) | Budget (ns) | Result |
|---|---|--:|--:|---|
| `gate-n`, 1 rules, − `gate-empty` | level-only | +62.8 ± 1.2 | 85.0 | pass |
| `gate-n`, 5 rules, − `gate-empty` | level-only | +73.4 ± 2.0 | 140 | pass |
| `gate-n`, 20 rules, − `gate-empty` | level-only | +90.2 ± 1.6 | 204 | pass |
| `gate-n`, 100 rules, − `gate-empty` | level-only | +187 ± 3.5 | 283 | pass |
| `gate-n`, 1 rules, − `gate-empty` | plain | +71.3 ± 1.6 | 85.0 | pass |
| `gate-n`, 5 rules, − `gate-empty` | plain | +143 ± 1.9 | 140 | **over** |
| `gate-n`, 20 rules, − `gate-empty` | plain | +208 ± 3.5 | 204 | **over** |
| `gate-n`, 100 rules, − `gate-empty` | plain | +236 ± 2.6 | 283 | pass |
| reading a parameterized message, 1 rules: `pattern` − `plain` | pattern | +688 ± 2.2 | — | reported |
| reading a parameterized message, 5 rules: `pattern` − `plain` | pattern | +339 ± 2.2 | — | reported |
| reading a parameterized message, 20 rules: `pattern` − `plain` | pattern | +251 ± 10.3 | — | reported |
| reading a parameterized message, 100 rules: `pattern` − `plain` | pattern | +241 ± 5.0 | — | reported |
| disabled call through the filter, 20 non-matching rules (Decision #28) | any | 1.7 ± 0.0 | 10 | pass |

## Component benchmarks

Each hot-path piece alone, to cross-check the differences above.

| Benchmark | Score (ns/op) | Allocated (B/op) |
|---|--:|--:|
| `GateCurveBenchmark.gate (rules=1)` | 36.0 ± 0.1 | 120 |
| `GateCurveBenchmark.gate (rules=100)` | 206 ± 2.3 | 384 |
| `GateCurveBenchmark.gate (rules=20)` | 164 ± 14.3 | 384 |
| `GateCurveBenchmark.gate (rules=5)` | 80.0 ± 0.1 | 120 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=0)` | 4.7 ± 0.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=1)` | 67.5 ± 1.2 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=100)` | 192 ± 3.5 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=20)` | 94.9 ± 1.6 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=level-only, rules=5)` | 78.1 ± 2.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=0)` | 4.7 ± 0.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=1)` | 765 ± 1.5 | 400 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=100)` | 481 ± 4.3 | 640 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=20)` | 464 ± 9.7 | 640 |
| `GateFilterComponentsBenchmark.gate (reads=pattern, rules=5)` | 487 ± 1.1 | 576 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=0)` | 5.2 ± 0.0 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=1)` | 76.5 ± 1.6 | 0 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=100)` | 241 ± 2.6 | 264 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=20)` | 213 ± 3.5 | 397 |
| `GateFilterComponentsBenchmark.gate (reads=plain, rules=5)` | 148 ± 1.9 | 200 |
| `GateFilterDisabledComponentsBenchmark.disabled` | 1.7 ± 0.0 | 0 |
| `IdleComponentsBenchmark.gateEmpty` | 2.2 ± 0.0 | 0 |
| `IdleComponentsBenchmark.stormHash` | 157 ± 1.4 | 0 |
| `IdleComponentsBenchmark.stormNormalize` | 305 ± 2.8 | 248 |
| `IdleComponentsBenchmark.stormObserve` | 50.6 ± 0.2 | 24 |
| `IdleComponentsBenchmark.stormObserveConcatenated` | 356 ± 3.8 | 120 |
| `StormFilterComponentsBenchmark.stormOff` | 2.2 ± 0.0 | 0 |
| `StormFilterComponentsBenchmark.stormOffDelegate` | 1.0 ± 0.0 | 0 |
| `StormGateComponentsBenchmark.stormConcatenated` | 390 ± 4.6 | 200 |
| `StormGateComponentsBenchmark.stormTemplate` | 106 ± 1.7 | 136 |
| `TopComponentsBenchmark.topRecord` | 18.3 ± 0.0 | 0 |
| `TopComponentsBenchmark.traceBytes` | 4110 ± 17.6 | 16960 |
| `TopComponentsBenchmark.traceShare` | 150 ± 5.9 | 88 |
| `TrimQueryComponentsBenchmark.trimQuery` | 31.8 ± 0.3 | 24 |

## Machine

```
label:            log4j2
quick:            false
date:             2026-11-12T18:29:28-06:00
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
