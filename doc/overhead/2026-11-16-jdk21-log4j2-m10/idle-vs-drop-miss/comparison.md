# `drop-miss` − `idle` on Log4j 2.25.5, 2026-11-16 (issue #79, decision M10)

Commit `d6f0d35` (develop, after the multi-context rules change), JDK 21.0.12, one thread, one shared appender,
the CPU pinned (boost off, `performance` governor, about 2.95 GHz). `idle` and `drop-miss` ran in one JMH
invocation with the class defaults of `Log4jCallBenchmark` (3 forks, 5 warmup and 10 measurement iterations of 1 s):
`java -jar benchmarks.jar 'Log4jCallBenchmark.info$' -t 1 -p mode=... -p scenario=idle,drop-miss -p message=... -p handlers=shared`.
Errors are JMH's 99.9 % confidence intervals, combined for the difference. The previous column is the pinned run of
2026-11-12 (`../../2026-11-12-jdk21-log4j2/2.25.5/`), computed the same way.

Decision #32 budgets the plain-`String` message (`concatenated`) at 50 ns; `template` and `throwable` read a
parameterized message, so they are reported.

| Mode | Message | `idle` (ns) | `drop-miss` (ns) | `drop-miss` − `idle` (ns) | 2026-11-12 (ns) | Budget (ns) | Result |
|---|---|--:|--:|--:|--:|--:|---|
| sync | concatenated | 443.7 ± 13.9 | 468.6 ± 3.8 | +24.9 ± 14.4 | -2.3 ± 12.8 | 50 | pass |
| sync | template | 569.0 ± 6.5 | 588.0 ± 17.1 | +19.0 ± 18.3 | +7.7 ± 14.5 | — | reported |
| sync | throwable | 5769.9 ± 38.7 | 5834.5 ± 55.4 | +64.6 ± 67.6 | +78.7 ± 129.8 | — | reported |
| async-appender | concatenated | 423.1 ± 15.0 | 413.1 ± 27.6 | -10.0 ± 31.4 | -15.8 ± 44.0 | 50 | pass |
| async-appender | template | 748.2 ± 11.6 | 761.8 ± 10.7 | +13.6 ± 15.8 | -30.7 ± 26.2 | — | reported |
| async-appender | throwable | 7315.6 ± 126.9 | 7215.4 ± 83.8 | -100.2 ± 152.0 | +65.4 ± 144.5 | — | reported |
| async-loggers | concatenated | 305.6 ± 2.6 | 310.6 ± 6.8 | +5.0 ± 7.3 | -14.0 ± 11.3 | 50 | pass |
| async-loggers | template | 466.1 ± 6.2 | 540.3 ± 4.2 | +74.2 ± 7.5 | +66.6 ± 2.1 | — | reported |
| async-loggers | throwable | 5976.8 ± 68.7 | 6003.0 ± 89.0 | +26.2 ± 112.4 | +95.3 ± 174.1 | — | reported |
