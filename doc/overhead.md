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
| [`2026-10-09-jdk21`](overhead/2026-10-09-jdk21/report.md) | the guide's Overhead page, from 1.0.0-beta.1 | storm detection off by default (#151), #148, #23 |
| [`2026-10-06-jdk21`](overhead/2026-10-06-jdk21/report.md) | here, before the guide existed | the first run; storm detection on |

How the runs are designed, the budgets, and how the #23 / #24 / #18 decision rule came out:
[`doc/specs/overhead-benchmarks.md`](specs/overhead-benchmarks.md). To run the benchmarks:
[`logaperture-bench/README.md`](../logaperture-bench/README.md).
