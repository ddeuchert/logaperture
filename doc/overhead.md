# LogAperture overhead

The published overhead numbers moved to the user guide's **Overhead** page:
[`guide/overhead.md`](../guide/overhead.md), at <https://logaperture.org/latest/overhead/>. The
guide is versioned per minor release, so each version's page carries the numbers for the code it
describes.

Each run's raw results stay here, one folder per run: the JMH JSON, `machine.txt`, the
async-profiler flame graphs, and a generated `report.md` with every row, including the
per-component benchmarks.

| Run | Published | |
|---|---|---|
| [`2026-11-12-jdk21-log4j2`](overhead/2026-11-12-jdk21-log4j2/2.25.5/report.md) | the guide's Overhead page, Log4j 2 section, from 1.1.0 (replaces 2026-11-11) | after #371: type-bound `trim` cost (R6, R7) on Log4j 2.25.5 and the 2.20.0 floor ([report](overhead/2026-11-12-jdk21-log4j2/2.20.0-floor/report.md)); JUL `trim-typed` rerun after R7 on JUL, `rules-t1` and `idle-t1` only ([rules](overhead/2026-11-12-jdk21-log4j2/jul-rerun-0100d23/rules/report.md), [idle](overhead/2026-11-12-jdk21-log4j2/jul-rerun-0100d23/idle/report.md)) |
| [`2026-11-11-jdk21-log4j2`](overhead/2026-11-11-jdk21-log4j2/2.25.5/report.md) | the guide's Overhead page, Log4j 2 section, from 1.1.0 | Log4j 2.25.5 (all modes) and the 2.20.0 floor ([report](overhead/2026-11-11-jdk21-log4j2/2.20.0-floor/report.md)); `trim-typed` over budget, #371 |
| [`2026-10-09-jdk21`](overhead/2026-10-09-jdk21/report.md) | the guide's Overhead page, from 1.0.0-beta.1 | storm detection off by default (#151), #148, #23 |
| [`2026-10-06-jdk21`](overhead/2026-10-06-jdk21/report.md) | here, before the guide existed | the first run; storm detection on |

How the runs are designed, the budgets, and how the #23 / #24 / #18 decision rule came out:
[`doc/specs/overhead-benchmarks.md`](specs/overhead-benchmarks.md). To run the benchmarks:
[`logaperture-bench/README.md`](../logaperture-bench/README.md).
